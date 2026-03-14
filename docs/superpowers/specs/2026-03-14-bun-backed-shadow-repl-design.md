# Bun-Backed shadow-cljs REPL Design

**Date:** 2026-03-14

## Context

Kiln is a ClojureScript project that targets Bun for execution, but its current `shadow-cljs` REPL workflow is effectively Node-oriented. This matters because Bun-specific APIs such as `Bun.Glob` are not reliably available through the current interactive workflow, which blocks exploratory debugging of Bun-backed code paths while preserving the existing `clj-nrepl-eval` + `(shadow/repl ...)` workflow.

The goal is to design a general solution in `shadow-cljs`, not a Kiln-only workaround.

## Goal

Provide a Bun-backed interactive `shadow-cljs` REPL workflow for Node-family builds while preserving the current user contract:

- `clj-nrepl-eval`
- `(shadow/repl :build-id)`
- build-specific connected runtime semantics

## Non-Goals

- Replacing the existing Node default
- Designing a Bun-specific remote runtime protocol in phase 1
- Solving browser/runtime parity
- Upstreaming polish in the first pass

## Approaches Considered

### 1. First-class Bun runtime backend

Add Bun as a new runtime type beside the existing Node runtime, with its own explicit client/runtime path.

Pros:
- Architecturally clean
- Easiest to reason about if upstreamed later

Cons:
- Largest patch surface
- Highest implementation and maintenance cost

### 2. Runtime selection for Node-family builds

Add a general JS runtime selector for Node-style targets and REPL launch paths. Start by reusing the existing Node client/runtime code and swapping the launched executable from `node` to `bun`.

Pros:
- Preserves the current workflow
- Smallest useful change
- General across Node-family builds
- Can evolve into a first-class Bun runtime later if needed

Cons:
- Depends on Bun being compatible enough with the existing Node client/runtime assumptions
- Some Node-specific assumptions may leak through in edge cases

### 3. Kiln-only dev workflow workaround

Add a Bun-executed exploratory script/build workflow in Kiln without changing `shadow-cljs`.

Pros:
- Cheapest to implement
- Unblocks Bun-specific probes quickly

Cons:
- Does not preserve `clj-nrepl-eval` + `(shadow/repl ...)`
- Not general
- Explicitly rejected by the design constraints

## Recommendation

Use approach 2 first.

The source code shows that `shadow-cljs` already has a strong seam for this approach:

- `shadow.cljs.devtools.server.repl-impl/node-repl*` already launches an external JS process via configurable `node-command` and `node-args`
- runtime registration is generic and keyed on connected runtimes, not on Node-specific server state
- Node-family targets inject the client runtime at target configuration time

That means the most pragmatic phase-1 design is:

- add a general runtime selector
- thread it into process launch paths
- keep the existing Node client/runtime protocol unchanged
- prove Bun can host the existing Node client successfully

If Bun fails that compatibility test, phase 2 becomes introducing a dedicated Bun client namespace.

## Architecture

### Core Idea

Treat Bun as a selectable JS host for the existing Node-family runtime path.

The user-facing workflow remains unchanged:

- start a watch/build
- attach with `clj-nrepl-eval`
- switch to CLJS mode with `(shadow/repl :build-id)`

The change is internal:

- where `shadow-cljs` currently launches `node`, it can launch `bun`
- where Node-family targets autorun with `node`, they can autorun with `bun`

### Configuration Shape

Add an opt-in runtime selector for Node-family builds and REPL launch paths:

```clojure
{:target :node-test
 :js-runtime :bun
 ...}
```

Potential API support:

```clojure
(shadow/node-repl {:js-runtime :bun})
```

Default remains Node when unspecified.

### Phase 1 Compatibility Model

Phase 1 deliberately keeps these pieces unchanged:

- `shadow.cljs.devtools.client.node`
- `shadow.cljs.devtools.client.node-repl`
- `shadow.cljs.devtools.client.node-esm`
- worker/runtime relay protocol
- `shadow/repl` user semantics

This limits the first implementation to launch-path selection and shared runtime configuration.

## Components and Hook Points

### Primary hook points

#### `shadow.cljs.devtools.server.repl-impl/node-repl*`

Relevant because it already exposes:

- `node-command`
- `node-args`

This is the clearest place to generalize from a Node-only launcher to a Node-family runtime launcher.

#### `shadow.build.targets.node-test/autorun-test`

Currently hardcodes `node` when executing the built test bundle. Bun-backed builds must honor the same runtime selector here or the interactive and runner workflows will diverge.

#### `shadow.build.targets.node-script`

Should honor the same runtime selector for consistency across Node-family targets.

#### `shadow.build.targets.shared`

Best place to:

- validate `:js-runtime`
- establish Node-family defaults
- centralize “runtime executable + args” derivation

### Likely untouched in phase 1

- `shadow.cljs.devtools.client.node`
- `shadow.cljs.devtools.client.node-repl`
- `shadow.cljs.devtools.client.node-esm`
- runtime relay registration in worker state

## Risks

### 1. Node assumptions in the client runtime

The existing client namespaces describe themselves as Node clients and may depend on Node behavior Bun only partially emulates.

Mitigation:
- keep phase 1 narrow
- validate with real connected-runtime evals
- only introduce a dedicated Bun client if concrete incompatibilities appear

### 2. Managed runtime lifecycle differences

The source inspection shows a clean seam for `node-repl*`, but watched Node-family builds may have different runtime-launch paths than the standalone `node-repl`.

Mitigation:
- map every launch path used by Node-family targets before implementation
- centralize runtime executable selection instead of patching one call site at a time

### 3. `:node-test` may remain a poor interactive target

Even with Bun selection, `:node-test` may still behave more like a compile-and-run target than a persistent interactive runtime for some workflows.

Mitigation:
- treat “connected Bun-backed runtime for `(shadow/repl :build-id)`” as the phase-1 success gate
- if `:node-test` remains awkward, solve that explicitly rather than hiding it

### 4. ESM/CJS differences

Bun may expose differences around import behavior, preload sequencing, or runtime evaluation semantics compared to Node.

Mitigation:
- validate both common Node-family paths
- start with the existing client namespace appropriate to the module format already selected by `shadow-cljs`

## Success Criteria

Phase 1 is successful when all of the following are true:

- a Node-family build can opt into `:js-runtime :bun`
- `shadow-cljs watch <build>` plus `(shadow/repl <build>)` yields a connected CLJS runtime hosted by Bun
- REPL eval sees `js/Bun`
- REPL eval can use Bun-specific APIs such as `Bun.Glob`
- existing Node behavior is unchanged when Bun is not selected

## Phase 2 Trigger

Move to a first-class Bun client/runtime namespace only if phase 1 reveals concrete incompatibilities in:

- runtime connection
- eval execution
- reload behavior
- client bootstrap

Until then, a runtime-selection layer is the right technical bar.
