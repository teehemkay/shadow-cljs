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

When a watched build has `:js-runtime :bun`, calling `(shadow/repl :build-id)` will:

1. Auto-launch a managed Bun process if no runtime is connected
2. Wait for the runtime to connect (up to 5 seconds)
3. Enter CLJS mode

```clojure
(shadow/watch :test)
(shadow/repl :test)    ;; launches Bun, connects, enters CLJS
```

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
