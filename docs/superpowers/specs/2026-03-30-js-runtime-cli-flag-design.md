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

### Build actions (watch / compile / release)

When `:js-runtime` is present in the parsed CLI options, inject
`{:js-runtime <value>}` into the `:config-merge` vector before options are
passed downstream. This reuses the existing `config-merge` deep-merge path in
`build/configure` (build.clj line 334).

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

When `:js-runtime` is specified (via CLI or config) and the build target is not
in the node family, print a warning to stderr and continue the build. The
warning is emitted from `shared.clj` where `js-runtime` is resolved, so it
covers both CLI and config-file usage uniformly.

### Validation

No CLI-level validation of the runtime value. The string is keywordized and
passed through; invalid values are caught by existing spec validation
downstream.

## Touch points

1. **`cli_opts.cljc`** — add `--js-runtime RUNTIME` to `cli-spec`
2. **`server.clj` (`from-cli`)** — lift `:js-runtime` from parsed options into
   `:config-merge` for build actions
3. **`shared.clj`** — warn when `:js-runtime` is set on a non-node-family
   target
4. **`node-repl` path** — no changes needed (already works)

## Testing

- Unit test: `--js-runtime bun` parses to `{:js-runtime :bun}` in options
- Integration: `shadow-cljs node-repl --js-runtime bun` launches Bun
- Integration: `shadow-cljs watch <node-build> --js-runtime bun` overrides
  config
- Warning: `shadow-cljs watch <browser-build> --js-runtime bun` prints warning
  and continues
