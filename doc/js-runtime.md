# JS Runtime Selection (`:js-runtime`)

shadow-cljs can use Bun instead of Node.js for running compiled output.
This is opt-in per build via the `:js-runtime` config key.

## Supported targets

| Target         | Supported    |
|----------------+--------------|
| `:node-test`   | Yes          |
| `:node-script` | Yes          |
| `:esm`         | No (not yet) |
| `:browser`     | N/A          |

## Configuration

Add `:js-runtime :bun` to a build in `shadow-cljs.edn`:

```clojure
{:builds
 {:test
  {:target    :node-test
   :output-to "out/test.js"
   :js-runtime :bun}

  :my-script
  {:target    :node-script
   :main      my.app/main
   :output-to "out/script.js"
   :js-runtime :bun}}}
```

When omitted, the default is `:node` (unchanged behavior).

## What changes

### Autorun tests

With `:autorun true` on a `:node-test` build, tests run under `bun run <file>` instead of `node <file>`.

### Watched builds and REPL

A watched build with `:js-runtime` gets a managed process: shadow-cljs starts
it when a compile succeeds, and every REPL evaluates in it however it selected
the build — `(shadow/repl :id)`, an editor's `nrepl-select` (CIDER, Calva,
Cursive), the console REPL or `shadow/cljs-eval`.

```clojure
(shadow/watch :my-script)   ;; compiles, then starts Bun
(shadow/repl :my-script)    ;; evaluates in it
```

- **Only builds that carry the REPL client get one.** A `:node-script` does
  unless `:devtools {:enabled false}`. A `:node-test` does only with
  `:ui-driven true`; otherwise it runs its tests and exits, so no process is
  started for it. `node-repl` gets none: it pipes in its own process.
- **One rule keeps it going: after each successful compile, a managed build
  with no live process gets one.** A process that exits — a crash, a `:main`
  that calls `process.exit` — is started again by the next compile (save a
  file), not by itself. A build that stops being managed loses its process.
  A config edit, or a process that hangs after connecting, needs the watch
  restarted.
- **The process's output goes to a log file**,
  `.shadow-cljs/shadow-managed-runtime-<build-id>.log`. Each run appends after
  a `--- bun runtime started ---` line, so an earlier run's crash stays
  readable. Before each start, a log past 1 MB is set aside as `<log>.1`;
  one long-running chatty process can still grow it.
- **An evaluation never starts it.** One that finds no runtime waits for a
  new process to connect, at most 5 seconds from that process's start, so a
  region of forms pays the wait once. Before the build's first successful
  compile it also waits up to 5 seconds for that compile, so a tool that
  evaluates as soon as the watch's nREPL port appears still reaches the
  process; with the connect wait after it, such an eval can take close to
  10 seconds. Otherwise it says at once why there is none: that no compile has
  succeeded yet, the launch error, that the process exited (with its code), or
  that it is running but not connected — the last three with the end of that
  run's output, printed once for a region of forms.
- **Stopping it** (the watch stops, or the build is no longer managed) sends
  SIGTERM, then a forced kill after 2 seconds, since the bootstrap keeps the
  event loop alive and a `:main` that handles SIGTERM would otherwise linger.

The managed process is stopped automatically when the worker shuts down.

### Standalone node-repl

Pass `:js-runtime` in the options map:

```clojure
(shadow/node-repl {:js-runtime :bun})
```

### CLI usage

Pass `--js-runtime` to any CLI command:

```bash
# standalone REPL with Bun
shadow-cljs node-repl --js-runtime bun

# watch a build with Bun
shadow-cljs watch my-build --js-runtime bun

# compile/release with Bun
shadow-cljs compile my-build --js-runtime bun
shadow-cljs release my-build --js-runtime bun
```

The CLI flag overrides `:js-runtime` in `shadow-cljs.edn`. It only takes
effect for node-family targets (`:node-script`, `:node-test`); on other
targets it is ignored with a warning.

## Requirements

- Bun must be installed and on `PATH`
- Java 21+

## Valid values

`:js-runtime` accepts `:node` or `:bun`. Any other value will produce an error
at build time or when starting a REPL.
