# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

shadow-cljs is a ClojureScript compiler/bundler with npm integration, dev tooling (live reload, REPL, nREPL), and 18 build targets (browser, node, esm, react-native, chrome-extension, etc.). It self-hosts: shadow-cljs builds its own UI, CLI, and tooling via `shadow-cljs.edn`.

## Build System

**Primary build tool: Leiningen** (not deps.edn — see comment at top of `deps.edn`).

```bash
# Install dependencies
./build-deps.sh

# Run Clojure tests
lein test

# Run a single test namespace
lein test :only shadow.build.ns-form-test

# Compile Java sources (required before using via deps.edn)
lein javac

# Build the npm CLI package
lein with-profiles +cljs run -m shadow.cljs.devtools.cli release cli

# Build everything (CLI, UI, build-report, babel-worker)
./build-all.sh

# Install locally
lein install

# Create uberjar
lein uberjar
```

## Development REPL

Start the dev server from a Leiningen REPL with the `:cljs` profile:

```clojure
;; In src/dev/repl.clj
(repl/go)   ; starts dev server + CSS watcher
(repl/stop) ; stops everything
```

The dev server serves the UI at `http://localhost:9630`. Dev HTTP servers for test builds are configured in `shadow-cljs.edn` under `:dev-http`.

## Project Layout

```
src/main/    — Production source (Clojure, ClojureScript, Java)
src/dev/     — Development helpers (repl.clj, build.clj, demo apps)
src/test/    — Test source (clojure.test)
src/ui-release/ — Pre-built UI assets committed for release
packages/    — npm packages (shadow-cljs, babel-worker, chrome-ext, create-cljs-project)
test-project/ — End-to-end integration test project (used in CI)
```

## Architecture

### Compilation Pipeline

`shadow.build.api` → `shadow.build.compiler` → `shadow.build.closure` → `shadow.build.output`

1. **Config** (`shadow.build.config`) — parses `shadow-cljs.edn`, validates with clojure.spec
2. **Resource discovery** (`shadow.build.classpath`, `shadow.build.npm`) — scans classpath + npm packages
3. **Dependency resolution** (`shadow.build.resolve`) — resolves requires across CLJS and JS
4. **Compilation** (`shadow.build.compiler`) — wraps ClojureScript compiler
5. **Optimization** (`shadow.build.closure`) — Google Closure Compiler pass
6. **Module splitting** (`shadow.build.modules`) — code splitting across output modules
7. **Output** (`shadow.build.output`) — writes target-specific files

### Target System

Each target in `shadow.build.targets.*` implements config spec, init, compilation hooks, and output generation. Shared infrastructure in `targets/shared.clj`. Targets: browser, esm, esm-files, node-script, node-library, node-test, npm-module, react-native, expo, karma, chrome-extension, graaljs, single-file, bootstrap, azure-app, browser-test.

### Dev Server & Tooling

- `shadow.cljs.devtools.server` — main dev server (HTTP, WebSocket, file watching)
- `shadow.cljs.devtools.api` — programmatic API for builds
- `shadow.cljs.devtools.cli-actual` — CLI implementation
- `shadow.cljs.devtools.server.nrepl` — nREPL middleware for editor integration
- `shadow.cljs.devtools.client.*` — client-side devtools (browser, node, react-native)
- `shadow.cljs.ui.*` — web dashboard UI (built with shadow-grove)

### npm Integration

`shadow.build.npm` implements webpack-compatible enhanced-resolve (package.json exports, conditions, browser field overrides). JS processing flows through `shadow.build.babel` for transformation.

## Key Configuration

- `project.clj` — Leiningen project definition (dependencies, profiles, AOT)
- `deps.edn` — only for `:local/root` or git sha consumption; NOT used for building
- `shadow-cljs.edn` — self-hosted builds (`:cli`, `:ui`, `:build-report`, `:babel-worker`) plus demo/test builds
- `flake.nix` — Nix dev shell with babashka, bun, clj-kondo, clojure, jdk

## CI

CircleCI runs `lein test`, `lein install`, builds CLI release, then runs `test-project/ci-run.sh` for end-to-end integration testing with Karma browser tests.

## Java Requirement

Java 21+ (`:javac-options ["--release" "21"]`). Java sources in `src/main/shadow/util/` (FS.java, FileWatcher.java).
