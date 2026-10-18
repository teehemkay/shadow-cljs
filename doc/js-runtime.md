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
  started for it.
- **The process's output goes to a log file**,
  `.shadow-cljs/shadow-managed-runtime-<build-id>.log`.
- **An evaluation never starts it.** One that finds no runtime waits up to
  5 seconds for a process that is still starting, and otherwise says why there
  is none: the launch error, or the exit code with the end of the log.
- **A process that exits after connecting is restarted once per build**; one
  that exits before connecting waits for the next successful compile, so a
  `:main` that exits is not re-run in a loop.

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
