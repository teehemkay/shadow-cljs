# `--js-runtime` CLI Flag

Add a global `--js-runtime RUNTIME` flag to the shadow-cljs CLI so users can
select an alternative JS runtime (e.g. Bun) without editing `shadow-cljs.edn`.

## Motivation

The `:js-runtime` build config option already supports Bun for node-family
targets and standalone `node-repl` (via the Clojure API). There is no way to
pass this from the CLI (`shadow-cljs node-repl`, `shadow-cljs watch`, etc.).

## Behaviour

### Flag definition

Add to the global `cli-spec` in `cli_opts.cljc`:

```clojure
[nil "--js-runtime RUNTIME" "use alternative JS runtime (e.g. bun) for node-family builds"
 :parse-fn keyword]
```

The value is keywordized (`"bun"` → `:bun`) and placed in the parsed options
map under `:js-runtime`.

### Build actions (watch / compile / release / check / cljs-repl)

When `:js-runtime` is present in the parsed CLI options, inject
`{:js-runtime <value>}` into the `:config-merge` vector in `options`. This
happens once in `cli_actual.clj:main`, immediately after `(opts/parse args)`,
before `options` is passed to any downstream path. This reuses the existing
`config-merge` deep-merge path in `build/configure` (build.clj line 334).

This single injection point covers all paths:
- `compile` / `release` / `check` via `do-build-command` → `api/*`
- `watch` / `cljs-repl` via `from-cli` → `super/start-worker`

Effect: `shadow-cljs watch my-build --js-runtime bun` is equivalent to
`shadow-cljs watch my-build --config-merge '{:js-runtime :bun}'`.

### node-repl

Options flow directly from the CLI into `api/node-repl` → `repl_impl/node-repl*`,
which already destructures `:js-runtime` from the opts map. No changes needed
in the REPL path — the flag just works once it's in the parsed options.

### Override semantics

The CLI flag overrides any `:js-runtime` value already set in the build config
in `shadow-cljs.edn`. This is consistent with how `--source-maps` and
`--pseudo-names` override compiler options.

### Non-node-family builds

To prevent spec validation from rejecting `:js-runtime` on non-node targets,
add `:js-runtime` as an optional key to the base `::config/build` spec in
`config.clj`. This makes it a legal (but ignored-with-warning) key for all
build configs.

When `:js-runtime` is specified (via CLI or config) and the build target is not
in the node family, print a warning and continue the build. The warning is
emitted from `build/configure` (build.clj), after the config-merge and spec
validation steps, where we have both the merged config and the target. This
runs for every build regardless of target, unlike the `shared.clj` runtime
helpers which are only called from node-oriented paths.

### Validation

No CLI-level validation of the runtime value. The string is keywordized and
passed through.

The `shared.clj` helpers (`js-runtime-stdin-argv`, `js-runtime-command`,
`js-runtime-file-argv`) currently use `case` with a default branch that
silently falls back to Node for unrecognized values. Change these to throw
on unknown values so that `--js-runtime nope` produces a clear error instead
of silently running Node.

## Touch points

1. **`cli_opts.cljc`** — add `--js-runtime RUNTIME` to `cli-spec`
2. **`cli_actual.clj` (`main`)** — lift `:js-runtime` from parsed options into
   `:config-merge` in the options map, before any downstream dispatch
3. **`config.clj`** — add `:js-runtime` as optional key to `::config/build`
   base spec
4. **`build.clj` (`configure`)** — warn when `:js-runtime` is set on a
   non-node-family target
5. **`shared.clj`** — throw on unrecognized runtime values instead of silent
   fallback
6. **`node-repl` path** — no changes needed (already works)

## Testing

- Unit test: `--js-runtime bun` parses to `{:js-runtime :bun}` in options
- Integration: `shadow-cljs node-repl --js-runtime bun` launches Bun
- Integration: `shadow-cljs watch <node-build> --js-runtime bun` overrides
  config
- Integration: `shadow-cljs cljs-repl <node-build> --js-runtime bun` works
- Warning: `shadow-cljs watch <browser-build> --js-runtime bun` prints warning
  and continues
- Error: `shadow-cljs node-repl --js-runtime nope` produces a clear error
