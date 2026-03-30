# JS Runtime Architecture

This document describes the internal architecture and implementation of the
`:js-runtime` feature, which enables shadow-cljs to use alternative JavaScript
runtimes (currently Bun) for Node-family build targets. For user-facing
configuration, see [js-runtime.md](js-runtime.md).

## Design Rationale

shadow-cljs already has a strong seam between build configuration and process
launch: `repl-impl/node-repl*` accepts `node-command` and `node-args`, runtime
registration is keyed on connected clients rather than Node-specific server
state, and Node-family targets inject the client runtime at configuration time.

Rather than introduce a first-class Bun runtime backend with its own
client/protocol path (high cost, large patch surface), the feature treats Bun as
a selectable JS host for the existing Node-family runtime path. The existing
`shadow.cljs.devtools.client.node` client runs unmodified under Bun; only the
executable and launch arguments change. If Bun-specific incompatibilities appear
later, a dedicated client namespace can be introduced without changing the
configuration model.

## Components

### Shared Helpers — `shadow.build.targets.shared`

All runtime-dependent behavior flows through a set of centralized helpers at the
bottom of `shared.clj`. These are the only functions that inspect the
`:js-runtime` key:

| Function | Purpose |
|---|---|
| `node-family-target?` | True when `:target` is `:node-script` or `:node-test` |
| `js-runtime` | Returns the configured runtime keyword, defaulting to `:node` |
| `explicit-js-runtime?` | True when `:js-runtime` is present in the build config |
| `js-runtime-command` | Returns the bare executable name (`"node"` or `"bun"`) |
| `js-runtime-stdin-argv` | Returns argv for piped stdin execution: `["node"]` or `["bun" "run" "-"]` |
| `js-runtime-file-argv` | Returns argv for file execution: `["node" path]` or `["bun" "run" path]` |
| `managed-runtime?` | True when the build is both a node-family target and has an explicit `:js-runtime` |

The spec `::js-runtime` is defined as `keyword?` in `shadow.build.config` and
included as an optional key in the base `::build` spec (accepted by all
targets). It is also included in the `:node-script` and `:node-test` target
specs. On non-node-family targets, it is accepted but ignored with a warning.

### Bootstrap — `shadow.cljs.devtools.server.js-runtime`

A small namespace with two functions that generate the CommonJS bootstrap script
used by managed runtimes:

- `bootstrap-file` — returns a `File` in the cache directory, named
  `shadow-managed-runtime-<build-id>.cjs`.
- `bootstrap-source` — returns a JS string that `require()`s the absolute path
  to the build output and keeps the process alive with an infinite `setInterval`.

The bootstrap approach is necessary because watched builds need a persistent
process that stays alive between REPL evaluations, unlike autorun test execution
which is fire-and-forget.

### Managed Runtime Lifecycle — `shadow.cljs.devtools.server.worker.impl`

The worker stores managed runtime state under `:managed-runtime`:

```clojure
{:managed-runtime {:process <java.lang.Process>
                   :bootstrap-file <java.io.File>}}
```

Three functions manage the lifecycle:

- **`start-managed-runtime`** — guards on `managed-runtime?` and
  `managed-runtime-running?`, then generates the bootstrap file, spawns a
  `ProcessBuilder` with `js-runtime-file-argv`, and stores the process reference.
- **`managed-runtime-running?`** — checks `.isAlive` on the stored process.
- **`stop-managed-runtime`** — calls `.destroy` on the process and dissociates
  the state. Called from the worker's `:do-shutdown` closure in `worker.clj`.

The worker exposes a `:ensure-managed-runtime` control message that delegates
to `start-managed-runtime` and replies `:launched` via the message's reply
channel.

### Public API — `shadow.cljs.devtools.api`

`ensure-runtime` bridges the gap between the REPL entry point and the managed
runtime:

1. Check if a runtime is already connected → `:already-connected`
2. Check if the build supports managed runtimes → `:not-managed`
3. Send `:ensure-managed-runtime` to the worker
4. Poll for a connected runtime with a configurable timeout (default 5 s)
5. Return `:connected`, `:timeout`, or `:no-worker`

Both code paths in `api/repl` (nREPL and stdin takeover) call
`ensure-runtime` before entering CLJS mode. This means `(shadow/repl :build-id)`
on a watched build with `:js-runtime :bun` will auto-launch Bun, wait for
connection, and enter the CLJS REPL transparently.

Note that managed runtime behavior is gated on the *presence* of `:js-runtime`
in the build config, not on which runtime is selected. A watched build with no
`:js-runtime` key (the traditional default) requires the user to manually start a
JS process that connects to the relay. Adding `:js-runtime :node` opts into
auto-launch and managed lifecycle just like `:js-runtime :bun` does — the only
difference is which executable is spawned.

### Standalone Node REPL — `shadow.cljs.devtools.server.repl-impl`

`node-repl*` creates a temporary `:node-script` build, compiles it, and pipes
the output to stdin of the selected runtime. The runtime argv comes from
`js-runtime-stdin-argv`:

- Node: `["node"]` — reads script from stdin
- Bun: `["bun" "run" "-"]` — `bun run -` accepts piped stdin

Both runtimes resolve `require()` paths relative to the working directory when
receiving piped input, which is why stdin piping works without an intermediate
file.

The REPL thread monitors the process and auto-restarts on crash until the worker
stops.

### Test Autorun — `shadow.build.targets.node-test`

`autorun-test` runs during the `:flush` stage when `:autorun true` is set. It
calls `js-runtime-file-argv` to get the argv, spawns a `ProcessBuilder`, pipes
stdout/stderr to the build logger, and waits for process exit. The exit code is
stored in build state.

## Data Flow

```
shadow-cljs.edn (:js-runtime :bun)
        │
        ▼
 Build Supervisor ── creates ──▶ Worker (stores build-config)
                                    │
                ┌───────────────────┼───────────────────┐
                ▼                   ▼                   ▼
         Test Autorun        Watched + REPL      Standalone REPL
                │                   │                   │
                ▼                   ▼                   ▼
       js-runtime-file-argv  ensure-runtime      js-runtime-stdin-argv
                │                   │                   │
                ▼                   ▼                   ▼
       ProcessBuilder         start-managed-     ProcessBuilder
       ["bun" "run"           runtime                ["bun" "run" "-"]
        "out/test.js"]              │                   │
                                    ▼                   ▼
                              bootstrap-source    Script piped to stdin
                              → require(output)
                              → setInterval
                                    │
                                    ▼
                              ProcessBuilder
                              ["bun" "run"
                               "cache/bootstrap.cjs"]
                                    │
                ┌───────────────────┘
                ▼
     Node client (client.node)
     connects via WebSocket to
     relay server
                │
                ▼
     Worker registers runtime
     in :runtimes map
                │
                ▼
     REPL evals route to
     connected runtime
```

## Runtime Connection

The existing `shadow.cljs.devtools.client.node` client runs unmodified under
both Node and Bun. It connects via WebSocket to the relay server, sends a
`:hello` message with `{:host :node}`, and the relay maps it to the correct
worker by proc-id. REPL evaluation messages are then routed through the relay to
the connected runtime.

The managed runtime process stays alive via the `setInterval` in the bootstrap
script. When the worker shuts down, `stop-managed-runtime` calls `.destroy` on
the process, which terminates it.

## Process Ownership

Each launch path has clear ownership:

| Path | Owner | Crash behavior | Shutdown |
|---|---|---|---|
| Standalone REPL | REPL thread in `repl-impl` | Auto-restart | Thread exit |
| Watched build | Worker impl | Not restarted | Worker `:do-shutdown` |
| Test autorun | Synchronous in flush stage | N/A (waits for exit) | Process exits naturally |

## Scope and Limitations

The feature currently supports:

- **Targets:** `:node-script`, `:node-test`
- **Module format:** CommonJS only (the bootstrap uses `require()`)
- **Runtimes:** `:node`, `:bun`

Not yet supported:

- `:esm` target (uses a separate launch path)
- ESM module format for managed runtimes
- Bun-specific client optimizations

## Future Considerations

The managed runtime currently launches the JS executable with no user-controlled
arguments, environment variables, or wrapper scripts. If use cases emerge that
need customizable launch (e.g., passing `--inspect` to Node, setting
`BUN_CONFIG_*` env vars, or wrapping the process in a profiler), the launch
configuration in `start-managed-runtime` and `js-runtime-file-argv` would need
to accept additional options.

## Extension Points

Adding a new runtime (e.g., Deno) requires:

1. Add the keyword to the `::js-runtime` spec in `shared.clj`
2. Add cases to `js-runtime-command`, `js-runtime-stdin-argv`, and
   `js-runtime-file-argv`
3. Verify the runtime can host `shadow.cljs.devtools.client.node` unmodified
4. If not, introduce a dedicated client namespace and adjust bootstrap generation
