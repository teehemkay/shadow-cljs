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
| `managed-runtime?` | True when the build is both a node-family target and has an explicit `:js-runtime`; the config half of the worker's `managed-build?` |

The spec `::js-runtime` is defined as `keyword?` in `shadow.build.config` and
included as an optional key in the base `::build` spec (accepted by all
targets). It is also included in the `:node-script` and `:node-test` target
specs. On non-node-family targets, it is accepted but ignored with a warning.

### Bootstrap — `shadow.cljs.devtools.server.js-runtime`

A small namespace naming the files a managed runtime uses and generating its
CommonJS bootstrap script:

- `bootstrap-file` — returns a `File` in the cache directory, named
  `shadow-managed-runtime-<build-id>.cjs`.
- `log-file` — returns `shadow-managed-runtime-<build-id>.log` beside it, where
  the process's stdout and stderr go. A file rather than a pipe, so nothing has
  to drain it and a chatty runtime never blocks.
- `bootstrap-source` — returns a JS string that `require()`s the absolute path
  to the build output and keeps the process alive with an infinite `setInterval`.

The bootstrap approach is necessary because watched builds need a persistent
process that stays alive between REPL evaluations, unlike autorun test execution
which is fire-and-forget.

### Managed Runtime Lifecycle — `shadow.cljs.devtools.server.worker.impl`

The worker loop owns the process. Its state lives under `:managed-runtime`:

```clojure
{:managed-runtime {:process    <java.lang.Process>
                   :log-file   <java.io.File>
                   :started-at 1760000000000 ; anchors the REPL's connect wait
                   :connected? true          ; set when a runtime connects while it is alive
                   :restarts   0}}           ; renewed by each successful compile
;; or, when the launch threw:
{:managed-runtime {:error "<exception message>" :restarts 0}}
```

- **`managed-build?`** — asks the *compiled* build: `managed-runtime?` on its
  merged config (where `--js-runtime` lands, through `:config-merge`), and
  `shadow.cljs.devtools.client.node` among its `:last-build-provides`. A
  `node-repl` worker is never managed: it pipes in its own process. Reading the build rather
  than restating each target's injection rules (`:ui-driven` for `:node-test`,
  `:devtools :enabled`) keeps the two from drifting. It is the *presence* of
  `:js-runtime` that opts in, not its value: `:js-runtime :node` gets a managed
  `node` process the same way.
- **`start-managed-runtime-after-build`** — called by `build-compile` after a
  successful compile, outside the compile's `try`, so nothing in it can turn a
  good compile into a reported failure: renews the restart allowance and starts
  a process when the build is managed and none is alive.
- **`start-managed-runtime`** — writes the bootstrap, spawns the process with
  `js-runtime-file-argv` and its output appended to the log file (after a run
  marker; a log past 1 MB is first set aside as `<log>.1`), and posts the
  process's exit back to the loop as `:managed-runtime-exited` with `put!`,
  never a blocking put. A launch that throws leaves `:error` instead.
- **`do-config-watch`** — a changed build config stops the live process; the
  next successful compile starts one from the new config.
- **`:connected?` is an approximation**: any runtime connecting while the
  process is alive sets it, because the node client's hello carries no process
  identity. Adding the pid to `shadow.cljs.devtools.client.node`'s client info
  would make it exact, at the cost of diverging from upstream's client.
- **`:managed-runtime-exited`** — restarts a process that had connected, once
  per build; one that never connected stays down until the next build, so a
  `:main` that exits is not re-run in a loop.
- **`stop-managed-runtime`** — calls `.destroy` on the process and dissociates
  the state. Called from the worker's `:do-shutdown` closure in `worker.clj`.

### The REPL side — `shadow.cljs.devtools.server.worker` and `repl-impl`

Every REPL frontend evaluates through `repl-impl/do-repl` (nREPL, stdin, prepl,
`api/cljs-eval`), and none of them starts the process. When an eval finds no
runtime, `worker/await-managed-runtime` reads the worker's state — never sending
the loop a message, so it cannot block on a busy, stopped or failed worker, and
is safe on the worker's own thread — and waits while one may still come: up to
5 s from the process's `:started-at`, while a restart of a connected process is
due, or before the first compile of a build configured with `:js-runtime`.
Otherwise it returns why there is none (the launch error, or the exit code with
the end of the log), which `do-repl` prints in place of the generic "No
available JS runtime". Anchoring the wait to the process means a region of
forms against a process that cannot connect pays it once, not once per form.

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
       js-runtime-file-argv  compile succeeds    js-runtime-stdin-argv
                │                   │                   │
                ▼                   ▼                   ▼
       ProcessBuilder         start-managed-     ProcessBuilder
       ["bun" "run"           runtime-after-build    ["bun" "run" "-"]
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
| Watched build | Worker impl | Restarted once per build if it had connected | Worker `:do-shutdown` |
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
