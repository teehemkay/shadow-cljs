# Bun-Backed shadow-cljs REPL Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.
>
> **Workspace:** `/Users/tmk/dev/my/shadow-cljs`
>
> **REPL-driven development is mandatory.** Use the `cljs-repl` skill. For `.clj` files, stay in CLJ mode for Explore → Decide (RED) → Build (GREEN) → Persist. Use CLJS mode only for the end-to-end Bun runtime verification task. All REPL work goes through `clj-nrepl-eval`.

**Goal:** Add opt-in Bun-backed managed runtimes for Node-family `shadow-cljs` builds while preserving `clj-nrepl-eval` + `(shadow/repl :build-id)`.

**Architecture:** Add `:js-runtime` as an opt-in selector on Node-family builds. Pure command/bootstrap helpers live in a new server-side helper namespace plus shared target-spec helpers. `shadow/node-repl` and `:node-test` autorun use the selected executable directly. Watched Node-family builds gain a worker-managed external JS runtime that launches on demand when `shadow/repl` selects a build with explicit `:js-runtime` and no runtime is connected; the worker owns process lifecycle and uses a CommonJS bootstrap script to load the watched build output under Bun or Node and stay alive for REPL evals. Phase 1 keeps managed watched runtimes scoped to the existing `:node-script` and `:node-test` CommonJS paths; ESM runtime selection remains on the existing dedicated target path and is not expanded here.

**Important implementation notes:**
- `node-repl*` in `repl_impl.clj` pipes the compiled script into stdin (not a file arg) to control `require()` resolution via `pwd`. For Bun, `bun run -` accepts piped stdin. Verify during implementation that Bun resolves `require()` paths relative to `pwd` when receiving piped input, just as Node does.
- `autorun-test` in `node_test.clj` uses `util/with-logged-time` which calls `(log state ...)` with a precondition `(build-state? state)` — the state must contain `:shadow.build.data/build-state true` and a `:logger`. Tests that call `autorun-test` directly must provide a valid build state, not a minimal mock.
- `:node-test` has no explicit `target-spec` registration (falls through to `config/target-spec ::default` which returns `any?`). An explicit spec must be added alongside `:node-script`.
- The `:do-shutdown` closure lives in `worker.clj` `start` function (not `worker/impl.clj`), so managed runtime cleanup must be called there.
- `api/repl` has two code paths: nREPL (`*nrepl-init*`) and stdin takeover. Both need `ensure-runtime` integration.

**Tech Stack:** Clojure, ClojureScript, shadow-cljs internals, nREPL, Bun, cljs.test

**Design:** `docs/superpowers/specs/2026-03-14-bun-backed-shadow-repl-design.md`

---

## Preconditions

- Run all commands from `/Users/tmk/dev/my/shadow-cljs`.
- Bun must be installed and on `PATH` for Bun-specific integration tests and manual verification.
- If `target/classes` is missing, run `lein javac` once before starting. `deps.edn` expects compiled Java classes in `target/classes`.

---

### Task 1: Start the shadow-cljs Dev REPL

**Files:** None

#### Setup

1. **Step 1: Start the dev nREPL/server**

```bash
cd /Users/tmk/dev/my/shadow-cljs
clojure -M:dev:start
```

Expected: `Started. nREPL ready.`

2. **Step 2: Discover the nREPL port**

```bash
cd /Users/tmk/dev/my/shadow-cljs
clj-nrepl-eval --discover-ports
```

Expected: output includes `.nrepl-port` for `/Users/tmk/dev/my/shadow-cljs`.

3. **Step 3: Verify CLJ mode**

```bash
cd /Users/tmk/dev/my/shadow-cljs
clj-nrepl-eval -p PORT '(+ 1 1)'
```

Expected: `2`

4. **Step 4: Load the namespaces used throughout the plan**

```bash
cd /Users/tmk/dev/my/shadow-cljs
clj-nrepl-eval -p PORT <<'EOF'
(require
  '[clojure.test :as t]
  '[shadow.build.targets.shared :as shared]
  '[shadow.build.targets.node-test :as node-test]
  '[shadow.cljs.devtools.api :as api]
  '[shadow.cljs.devtools.server.repl-impl :as repl-impl]
  '[shadow.cljs.devtools.server.worker :as worker]
  '[shadow.cljs.devtools.server.worker.impl :as worker-impl])
(println :ready)
EOF
```

Expected: `:ready`

5. **Step 5: Confirm Bun is available**

```bash
cd /Users/tmk/dev/my/shadow-cljs
bun --version
```

Expected: Bun version string.

6. **Step 6: Commit nothing**

This is setup only.

---

### Task 2: Add `:js-runtime` Config and Launch Helpers

**Files:**
- Create: `/Users/tmk/dev/my/shadow-cljs/src/main/shadow/cljs/devtools/server/js_runtime.clj`
- Create: `/Users/tmk/dev/my/shadow-cljs/src/repl/shadow/cljs/js_runtime_test.clj`
- Modify: `/Users/tmk/dev/my/shadow-cljs/src/main/shadow/build/targets/shared.clj`
- Modify: `/Users/tmk/dev/my/shadow-cljs/src/main/shadow/build/targets/node_script.clj`
- Modify: `/Users/tmk/dev/my/shadow-cljs/src/main/shadow/build/targets/node_test.clj`

#### Explore

1. **Step 1: Confirm `:js-runtime` is not modeled yet**

```bash
cd /Users/tmk/dev/my/shadow-cljs
clj-nrepl-eval -p PORT <<'EOF'
(require '[shadow.build.targets.shared :as shared] :reload)
(println (contains? (ns-publics 'shadow.build.targets.shared) 'js-runtime-file-argv))
(println (contains? (ns-publics 'shadow.build.targets.shared) 'js-runtime-stdin-argv))
(println (contains? (ns-publics 'shadow.build.targets.shared) 'managed-runtime?))
EOF
```

Expected: all lines print `false`.

2. **Step 2: Inspect current `:node-script` spec acceptance**

```bash
cd /Users/tmk/dev/my/shadow-cljs
clj-nrepl-eval -p PORT <<'EOF'
(require '[clojure.spec.alpha :as s]
         '[shadow.build.config :as config]
         '[shadow.build.targets.node-script]
         :reload)
(println
  (s/valid? (config/target-spec :node-script)
    {:target :node-script
     :main 'demo.script/main
     :output-to "out/demo.js"
     :js-runtime :bun}))
EOF
```

Expected: `false`

#### Decide (RED)

3. **Step 3: Write the failing tests**

Create `/Users/tmk/dev/my/shadow-cljs/src/repl/shadow/cljs/js_runtime_test.clj`:

```clojure
(ns shadow.cljs.js-runtime-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [clojure.spec.alpha :as s]
    [shadow.build.config :as config]
    [shadow.build.targets.node-script]
    [shadow.build.targets.node-test]
    [shadow.build.targets.shared :as shared]
    [shadow.cljs.devtools.server.js-runtime :as js-runtime]))

(deftest test-js-runtime-defaults-to-node
  (is (= :node (shared/js-runtime {:target :node-script}))))

(deftest test-js-runtime-command-selection
  (is (= "node" (shared/js-runtime-command {:target :node-script})))
  (is (= "bun" (shared/js-runtime-command {:target :node-script :js-runtime :bun}))))

(deftest test-js-runtime-argv-selection
  (is (= ["node"]
        (shared/js-runtime-stdin-argv {:target :node-script})))
  (is (= ["bun" "run" "-"]
        (shared/js-runtime-stdin-argv {:target :node-script :js-runtime :bun})))
  (is (= ["node" "/tmp/demo.js"]
        (shared/js-runtime-file-argv {:target :node-script
                                      :output-to "/tmp/demo.js"})))
  (is (= ["bun" "run" "/tmp/demo.js"]
        (shared/js-runtime-file-argv {:target :node-script
                                      :output-to "/tmp/demo.js"
                                      :js-runtime :bun}))))

(deftest test-managed-runtime-is-opt-in
  (is (false? (shared/managed-runtime? {:target :node-script})))
  (is (true? (shared/managed-runtime? {:target :node-script :js-runtime :bun})))
  (is (true? (shared/managed-runtime? {:target :node-test :js-runtime :bun})))
  (is (false? (shared/managed-runtime? {:target :browser :js-runtime :bun}))))

(deftest test-bootstrap-source-keeps-runtime-alive
  (let [src (js-runtime/bootstrap-source
              {:output-to "/tmp/demo.js"})]
    (is (.contains src "require("))
    (is (.contains src "setInterval"))))

(deftest test-node-script-target-spec-accepts-js-runtime
  (is (s/valid? (config/target-spec :node-script)
        {:target :node-script
         :main 'demo.script/main
         :output-to "out/demo.js"
         :js-runtime :bun})))

(deftest test-node-test-target-spec-accepts-js-runtime
  (is (s/valid? (config/target-spec :node-test)
        {:target :node-test
         :output-to "out/test.js"
         :js-runtime :bun})))
```

4. **Step 4: Run the focused runner to confirm RED**

```bash
cd /Users/tmk/dev/my/shadow-cljs
clojure -M:dev -e "(require 'clojure.test 'shadow.cljs.js-runtime-test) (clojure.test/run-tests 'shadow.cljs.js-runtime-test)"
```

Expected: FAIL because `shared/js-runtime`, `shared/js-runtime-command`, `shared/js-runtime-file-argv`, `shared/js-runtime-stdin-argv`, `shared/managed-runtime?`, and `shadow.cljs.devtools.server.js-runtime` do not exist yet.

#### Build (GREEN)

5. **Step 5: Add the shared config helpers**

Update `/Users/tmk/dev/my/shadow-cljs/src/main/shadow/build/targets/shared.clj`:

```clojure
(s/def ::js-runtime #{:node :bun})

(defn node-family-target? [{:keys [target]}]
  (contains? #{:node-script :node-test} target))

(defn js-runtime [{:keys [js-runtime]}]
  (or js-runtime :node))

(defn explicit-js-runtime? [build-config]
  (contains? build-config :js-runtime))

(defn js-runtime-command [build-config]
  (case (js-runtime build-config)
    :bun "bun"
    "node"))

(defn js-runtime-stdin-argv
  "Returns argv for launching a JS runtime that reads from stdin.
   Node accepts piped stdin with no args. Bun requires 'bun run -' to
   read from stdin. Both resolve require() paths relative to pwd when
   receiving piped input — verify this for Bun during implementation."
  [build-config]
  (case (js-runtime build-config)
    :bun ["bun" "run" "-"]
    ["node"]))

(defn js-runtime-file-argv
  "Returns argv for launching a JS runtime with a file argument.
   Uses 'bun run <file>' rather than 'bun <file>' for consistency
   with the stdin path and because 'bun run' is the documented
   general-purpose execution command."
  [{:keys [output-to] :as build-config}]
  (let [output-path (.getPath (io/file output-to))]
    (case (js-runtime build-config)
      :bun ["bun" "run" output-path]
      ["node" output-path])))

(defn managed-runtime? [build-config]
  (and (node-family-target? build-config)
       (explicit-js-runtime? build-config)))

;; Log a warning when :js-runtime is set on unsupported targets so
;; the user knows it is being silently ignored. Add this check in
;; build-configure or the target's configure function:
;;
;; (when (and (explicit-js-runtime? build-config)
;;            (not (node-family-target? build-config)))
;;   (log/warn ::unsupported-js-runtime
;;     {:target (:target build-config)
;;      :js-runtime (:js-runtime build-config)}))
```

6. **Step 6: Allow `:node-script` configs to declare `:js-runtime`**

Update `/Users/tmk/dev/my/shadow-cljs/src/main/shadow/build/targets/node_script.clj`:

```clojure
(s/def ::target
  (s/keys
    :req-un
    [::main
     ::shared/output-to]
    :opt-un
    [::shared/output-dir
     ::shared/js-runtime]))
```

6b. **Step 6b: Add explicit `:node-test` target spec**

Currently `:node-test` has no `defmethod config/target-spec :node-test` — it falls through to the `::default` method which returns `(s/spec any?)`. This means `:js-runtime` is accepted by accident, not by design. Add an explicit spec.

Update `/Users/tmk/dev/my/shadow-cljs/src/main/shadow/build/targets/node_test.clj`, adding after the `ns` form:

```clojure
(s/def ::target
  (s/keys
    :req-un
    [::shared/output-to]
    :opt-un
    [::shared/output-dir
     ::shared/js-runtime]))

(defmethod config/target-spec :node-test [_]
  (s/spec ::target))
```

This requires adding `[clojure.spec.alpha :as s]` and `[shadow.build.config :as config]` to the `:require` vector (they are not currently imported in `node_test.clj`).

Also add a corresponding test in `js_runtime_test.clj`:

```clojure
(deftest test-node-test-target-spec-accepts-js-runtime
  (is (s/valid? (config/target-spec :node-test)
        {:target :node-test
         :output-to "out/test.js"
         :js-runtime :bun})))
```

7. **Step 7: Add the runtime bootstrap helper namespace**

Create `/Users/tmk/dev/my/shadow-cljs/src/main/shadow/cljs/devtools/server/js_runtime.clj`:

```clojure
(ns shadow.cljs.devtools.server.js-runtime
  (:require
    [clojure.java.io :as io]))

(defn bootstrap-file
  [cache-root build-id]
  (io/file
    cache-root
    (str "shadow-managed-runtime-"
         (name build-id)
         ".cjs")))

(defn bootstrap-source
  [{:keys [output-to]}]
  (let [abs-output (.getAbsolutePath (io/file output-to))]
    (str "require(" (pr-str abs-output) ");\n"
         "setInterval(function () {}, 2147483647);\n")))
```

8. **Step 8: Reload and verify in REPL**

```bash
cd /Users/tmk/dev/my/shadow-cljs
clj-nrepl-eval -p PORT <<'EOF'
(require
  '[shadow.build.targets.shared :as shared]
  '[shadow.cljs.devtools.server.js-runtime :as js-runtime]
  :reload)
(println (shared/js-runtime {:target :node-script}))
(println (shared/js-runtime-command {:target :node-script :js-runtime :bun}))
(println (shared/js-runtime-stdin-argv {:target :node-script :js-runtime :bun}))
(println (shared/js-runtime-file-argv {:target :node-script :output-to "/tmp/demo.js" :js-runtime :bun}))
(println (shared/managed-runtime? {:target :node-script}))
(println (shared/managed-runtime? {:target :node-script :js-runtime :bun}))
(println (js-runtime/bootstrap-source {:output-to "/tmp/demo.js"}))
EOF
```

Expected: `:node`, `"bun"`, `["bun" "run" "-"]`, `["bun" "run" "/tmp/demo.js"]`, `false`, `true`, then bootstrap source containing `require(` and `setInterval`.

#### Persist

9. **Step 9: Re-run the focused test namespace**

```bash
cd /Users/tmk/dev/my/shadow-cljs
clojure -M:dev -e "(require 'clojure.test 'shadow.cljs.js-runtime-test :reload) (clojure.test/run-tests 'shadow.cljs.js-runtime-test)"
```

Expected: PASS

10. **Step 10: Commit**

```bash
cd /Users/tmk/dev/my/shadow-cljs
git add src/main/shadow/build/targets/shared.clj \
  src/main/shadow/build/targets/node_script.clj \
  src/main/shadow/build/targets/node_test.clj \
  src/main/shadow/cljs/devtools/server/js_runtime.clj \
  src/repl/shadow/cljs/js_runtime_test.clj
git commit -m "feat: add opt-in JS runtime selection helpers"
```

---

### Task 3: Use the Selected Runtime for `shadow/node-repl` and `:node-test` Autorun

**Files:**
- Create: `/Users/tmk/dev/my/shadow-cljs/src/repl/shadow/cljs/bun_runtime_test.clj`
- Modify: `/Users/tmk/dev/my/shadow-cljs/src/main/shadow/cljs/devtools/server/repl_impl.clj`
- Modify: `/Users/tmk/dev/my/shadow-cljs/src/main/shadow/build/targets/node_test.clj`

#### Explore

1. **Step 1: Confirm both launch paths still hardcode `node`**

```bash
cd /Users/tmk/dev/my/shadow-cljs
clj-nrepl-eval -p PORT <<'EOF'
(require '[shadow.cljs.devtools.server.repl-impl :as repl-impl]
         '[shadow.build.targets.node-test :as node-test]
         :reload)
(println :inspect-source-manually)
EOF
```

Expected: `:inspect-source-manually`

2. **Step 2: Prove current `autorun-test` ignores `:js-runtime :bun`**

Create a temporary script that only succeeds under Bun.

**Note:** `autorun-test` uses `util/with-logged-time` which calls `(shadow.cljs.util/log state ...)`. That `log` function has a precondition `(build-state? state)` requiring `:shadow.build.data/build-state true` and a `:logger` key. The test state must satisfy this.

```bash
cd /Users/tmk/dev/my/shadow-cljs
mkdir -p target/bun-runtime
printf 'process.exit(process.versions.bun ? 0 : 17);\n' > target/bun-runtime/needs-bun.js
clj-nrepl-eval -p PORT <<'EOF'
(require '[shadow.build.targets.node-test :as node-test]
         '[shadow.build.log :as build-log]
         :reload)
(let [noop-logger (reify build-log/BuildLog (log* [_ _ _]))
      state {:shadow.build.data/build-state true
             :logger noop-logger
             :shadow.build/config
             {:output-to "target/bun-runtime/needs-bun.js"
              :js-runtime :bun}}]
  (println
    (::node-test/exit-code
      (node-test/autorun-test state))))
EOF
```

Expected: `17`

#### Decide (RED)

3. **Step 3: Write the failing focused integration tests**

Create `/Users/tmk/dev/my/shadow-cljs/src/repl/shadow/cljs/bun_runtime_test.clj`:

```clojure
(ns shadow.cljs.bun-runtime-test
  (:require
    [clojure.java.io :as io]
    [clojure.java.shell :refer [sh]]
    [clojure.test :refer [deftest is testing]]
    [shadow.build.log :as build-log]
    [shadow.build.targets.node-test :as node-test]))

(defn bun-available? []
  (try
    (zero? (:exit (sh "bun" "--version")))
    (catch Exception _ false)))

(def ^:private noop-logger
  (reify build-log/BuildLog (log* [_ _ _])))

(defn- make-autorun-state
  "Builds a minimal state map that satisfies autorun-test's preconditions.
   autorun-test uses util/with-logged-time which requires a valid build state
   (build-state? checks for :shadow.build.data/build-state true and :logger)."
  [config]
  {:shadow.build.data/build-state true
   :logger noop-logger
   :shadow.build/config config})

(deftest test-node-test-autorun-respects-bun
  (if-not (bun-available?)
    (testing "Bun not installed"
      (is true))
    (let [script (io/file "target" "bun-runtime" "needs-bun.js")]
      (io/make-parents script)
      (spit script "process.exit(process.versions.bun ? 0 : 17);\n")
      (is (= 0
            (::node-test/exit-code
              (node-test/autorun-test
                (make-autorun-state
                  {:output-to (.getPath script)
                   :js-runtime :bun}))))))))
```

4. **Step 4: Run the focused test namespace to confirm RED**

```bash
cd /Users/tmk/dev/my/shadow-cljs
clojure -M:dev -e "(require 'clojure.test 'shadow.cljs.bun-runtime-test) (clojure.test/run-tests 'shadow.cljs.bun-runtime-test)"
```

Expected: FAIL with exit code `17`.

#### Build (GREEN)

5. **Step 5: Update `node-repl*` to use the selected executable**

Update `/Users/tmk/dev/my/shadow-cljs/src/main/shadow/cljs/devtools/server/repl_impl.clj`:

```clojure
(:require
  ...
  [shadow.build.targets.shared :as shared]
  ...)

...

(defn node-repl*
  [{:keys [supervisor config] :as app}
   {:keys [via verbose build-id node-args node-command pwd js-runtime]
    :or {node-args []
         build-id :node-repl}
    :as opts}]
  ...
  (let [runtime-argv
        (if node-command
          (into [node-command] node-args)
          (into (shared/js-runtime-stdin-argv
                  {:target :node-script
                   :js-runtime js-runtime})
                node-args))
        ...
        node-proc
        (-> (ProcessBuilder.
              (into-array runtime-argv))
            ...)]
```

Keep explicit `:node-command` override precedence intact; only derive Bun argv when no explicit command override is supplied.

6. **Step 6: Update `:node-test` autorun to use the same selector**

Update `/Users/tmk/dev/my/shadow-cljs/src/main/shadow/build/targets/node_test.clj`:

```clojure
(defn autorun-test [{::build/keys [config] :as state}]
  (util/with-logged-time
    [state {:type ::autorun}]
    (let [script-args
          (shared/js-runtime-file-argv config)
          proc
          (-> (ProcessBuilder. (into-array script-args))
              (.directory nil)
              (.start))]
      ...
      (assoc state ::exit-code exit-code))))
```

7. **Step 7: Reload and verify in REPL**

```bash
cd /Users/tmk/dev/my/shadow-cljs
clj-nrepl-eval -p PORT <<'EOF'
(require
  '[shadow.build.targets.node-test :as node-test]
  '[shadow.build.log :as build-log]
  '[shadow.cljs.devtools.server.repl-impl :as repl-impl]
  :reload)
(let [noop-logger (reify build-log/BuildLog (log* [_ _ _]))
      state {:shadow.build.data/build-state true
             :logger noop-logger
             :shadow.build/config
             {:output-to "target/bun-runtime/needs-bun.js"
              :js-runtime :bun}}]
  (println
    (::node-test/exit-code
      (node-test/autorun-test state))))
EOF
```

Expected: `0`

#### Persist

8. **Step 8: Re-run the focused Bun runtime tests**

```bash
cd /Users/tmk/dev/my/shadow-cljs
clojure -M:dev -e "(require 'clojure.test 'shadow.cljs.bun-runtime-test :reload) (clojure.test/run-tests 'shadow.cljs.bun-runtime-test)"
```

Expected: PASS

9. **Step 9: Commit**

```bash
cd /Users/tmk/dev/my/shadow-cljs
git add src/main/shadow/cljs/devtools/server/repl_impl.clj \
  src/main/shadow/build/targets/node_test.clj \
  src/repl/shadow/cljs/bun_runtime_test.clj
git commit -m "feat: use selected JS runtime for node launch paths"
```

---

### Task 4: Add Worker-Managed Runtimes for Watched Node-Family Builds

**Files:**
- Modify: `/Users/tmk/dev/my/shadow-cljs/src/main/shadow/cljs/devtools/api.clj`
- Modify: `/Users/tmk/dev/my/shadow-cljs/src/main/shadow/cljs/devtools/server/worker.clj`
- Modify: `/Users/tmk/dev/my/shadow-cljs/src/main/shadow/cljs/devtools/server/worker/impl.clj`
- Modify: `/Users/tmk/dev/my/shadow-cljs/src/repl/shadow/cljs/bun_runtime_test.clj`

#### Explore

1. **Step 1: Confirm watched workers do not currently manage a JS process**

```bash
cd /Users/tmk/dev/my/shadow-cljs
clj-nrepl-eval -p PORT <<'EOF'
(require '[shadow.cljs.devtools.api :as api]
         :reload)
(println (resolve 'api/ensure-runtime))
EOF
```

Expected: `nil`

2. **Step 2: Reproduce the current failure mode with a watched Node build**

```bash
cd /Users/tmk/dev/my/shadow-cljs
clj-nrepl-eval -p PORT <<'EOF'
(require '[shadow.cljs.devtools.api :as api] :reload)
(api/watch
  {:build-id :bun-watch-probe
   :target :node-test
   :ns-regexp "test.(.+)-test$"
   :ui-driven true
   :output-to "target/bun-watch-probe/script.js"
   :js-runtime :bun})
(println (api/repl-runtimes :bun-watch-probe))
EOF
```

Expected: `[]`

#### Decide (RED)

3. **Step 3: Extend the focused integration tests with a watched-build case**

Update `/Users/tmk/dev/my/shadow-cljs/src/repl/shadow/cljs/bun_runtime_test.clj` to add the extra require and watched-build test:

```clojure
(ns shadow.cljs.bun-runtime-test
  (:require
    ...
    [shadow.cljs.devtools.api :as api]))

(deftest test-watched-node-build-can-start-managed-bun-runtime
  (if-not (bun-available?)
    (testing "Bun not installed"
      (is true))
    (api/with-runtime
      (try
        (api/watch
          {:build-id :bun-watch-test
           :target :node-test
           :ns-regexp "test.(.+)-test$"
           :ui-driven true
           :output-to "target/bun-watch-test/script.js"
           :js-runtime :bun}
          {:autobuild false
           :sync true})
        (is (= :connected (api/ensure-runtime :bun-watch-test)))
        (is (seq (api/repl-runtimes :bun-watch-test)))
        (finally
          (api/stop-worker :bun-watch-test))))))
```

4. **Step 4: Run the focused Bun runtime tests to confirm RED**

```bash
cd /Users/tmk/dev/my/shadow-cljs
clojure -M:dev -e "(require 'clojure.test 'shadow.cljs.bun-runtime-test :reload) (clojure.test/run-tests 'shadow.cljs.bun-runtime-test)"
```

Expected: FAIL because `api/ensure-runtime` does not exist yet.

#### Build (GREEN)

5. **Step 5: Add a worker command for starting managed runtimes**

Update `/Users/tmk/dev/my/shadow-cljs/src/main/shadow/cljs/devtools/server/worker.clj`:

```clojure
(defn ensure-managed-runtime
  [{:keys [proc-control] :as proc}]
  {:pre [(impl/proc? proc)]}
  (let [reply-to (async/chan)]
    (>!! proc-control {:type :ensure-managed-runtime :reply-to reply-to})
    (<!! reply-to)))
```

6. **Step 6: Implement managed runtime lifecycle in the worker**

Update `/Users/tmk/dev/my/shadow-cljs/src/main/shadow/cljs/devtools/server/worker/impl.clj`:

```clojure
(:require
  ...
  [shadow.build.targets.shared :as shared]
  [shadow.cljs.devtools.server.js-runtime :as js-runtime]
  ...)

(defn stop-managed-runtime [{:keys [managed-runtime] :as worker-state}]
  (when-let [{:keys [process]} managed-runtime]
    (.destroy ^Process process))
  (dissoc worker-state :managed-runtime))

(defn managed-runtime-running? [{:keys [managed-runtime]}]
  (when-let [{:keys [process]} managed-runtime]
    (.isAlive ^Process process)))

(defn start-managed-runtime
  [{:keys [build-config cache-root channels] :as worker-state}]
  (cond
    (not (shared/managed-runtime? build-config))
    worker-state

    (managed-runtime-running? worker-state)
    worker-state

    :else
    (let [bootstrap-file
          (js-runtime/bootstrap-file cache-root (:build-id build-config))
          bootstrap-source
          (js-runtime/bootstrap-source
            {:output-to (:output-to build-config)})
          _ (spit bootstrap-file bootstrap-source)
          process
          (-> (ProcessBuilder.
                (into-array
                  (shared/js-runtime-file-argv
                    (assoc build-config
                      :output-to (.getAbsolutePath bootstrap-file)))))
              (.directory nil)
              (.start))]
      (assoc worker-state
        :managed-runtime
        {:process process
         :bootstrap-file bootstrap-file})))

(defmethod do-proc-control :ensure-managed-runtime
  [worker-state {:keys [reply-to]}]
  (let [next-state (start-managed-runtime worker-state)]
    (when reply-to
      (>!! reply-to :launched))
    next-state))
```

Also update the `:do-shutdown` closure in **`worker.clj`** `start` function (not `impl.clj`) to call `stop-managed-runtime`. The current code at lines 258-263 of `worker.clj` is:

```clojure
:do-shutdown
(fn [{:keys [reload-npm] :as state}]
  (>!! output {:type :worker-shutdown :proc-id proc-id})
  (when reload-npm
    (reload-npm/stop reload-npm))
  state)
```

Change it to:

```clojure
:do-shutdown
(fn [{:keys [reload-npm] :as state}]
  (>!! output {:type :worker-shutdown :proc-id proc-id})
  (when reload-npm
    (reload-npm/stop reload-npm))
  (impl/stop-managed-runtime state))
```

7. **Step 7: Add the public API that waits for runtime connection**

Update `/Users/tmk/dev/my/shadow-cljs/src/main/shadow/cljs/devtools/api.clj`:

```clojure
(:require
  ...
  [shadow.build.targets.shared :as shared]
  ...)

...

(defn ensure-runtime
  ([build-id]
   (ensure-runtime build-id {}))
  ([build-id {:keys [timeout-ms] :or {timeout-ms 5000}}]
   (if-let [worker (get-worker build-id)]
     (let [build-config (-> worker :state-ref deref :build-config)]
       (cond
         (seq (repl-runtimes build-id))
         :already-connected

         (not (shared/managed-runtime? build-config))
         :not-managed

         :else
         (do
           (worker/ensure-managed-runtime worker)
           (loop [deadline (+ (System/currentTimeMillis) timeout-ms)]
             (cond
               (seq (repl-runtimes build-id))
               :connected

               (> (System/currentTimeMillis) deadline)
               :timeout

               :else
               (do (Thread/sleep 50)
                   (recur deadline)))))))
     :no-worker)))
```

Then update `api/repl` to call `ensure-runtime` before entering CLJS mode when the target build has explicit `:js-runtime`. The current `repl` function has two paths — nREPL and stdin takeover. Both need the `ensure-runtime` call before entering the CLJS REPL loop:

```clojure
(defn repl
  ([build-id]
   (repl build-id {}))
  ([build-id {:keys [stop-on-eof] :as opts}]
   (if *nrepl-init*
     (do
       ;; ensure managed runtime is running before nREPL switches to CLJS
       (ensure-runtime build-id)
       (nrepl-select build-id opts))
     (let [{:keys [supervisor] :as app}
           (runtime/get-instance!)

           worker
           (super/get-worker supervisor build-id)]
       (if-not worker
         :no-worker
         (do
           ;; ensure managed runtime is running before stdin takeover
           (ensure-runtime build-id)
           (repl-impl/stdin-takeover! worker app opts)
           (when stop-on-eof
             (super/stop-worker supervisor build-id))))))))
```

`ensure-runtime` is a no-op (returns `:not-managed`) when the build has no explicit `:js-runtime`, so this is safe for all builds.

8. **Step 8: Reload and verify in REPL**

```bash
cd /Users/tmk/dev/my/shadow-cljs
clj-nrepl-eval -p PORT <<'EOF'
(require
  '[shadow.cljs.devtools.api :as api]
  '[shadow.cljs.devtools.server.worker.impl :as worker-impl]
  :reload)
(api/watch
  {:build-id :bun-watch-probe
   :target :node-test
   :ns-regexp "test.(.+)-test$"
   :ui-driven true
   :output-to "target/bun-watch-probe/script.js"
   :js-runtime :bun}
  {:autobuild false
   :sync true})
(println (api/ensure-runtime :bun-watch-probe))
(println (seq (api/repl-runtimes :bun-watch-probe)))
(api/stop-worker :bun-watch-probe)
EOF
```

Expected: `:connected`, then `true`.

#### Persist

9. **Step 9: Re-run the focused Bun runtime test namespace**

```bash
cd /Users/tmk/dev/my/shadow-cljs
clojure -M:dev -e "(require 'clojure.test 'shadow.cljs.bun-runtime-test :reload) (clojure.test/run-tests 'shadow.cljs.bun-runtime-test)"
```

Expected: PASS

10. **Step 10: Commit**

```bash
cd /Users/tmk/dev/my/shadow-cljs
git add src/main/shadow/cljs/devtools/api.clj \
  src/main/shadow/cljs/devtools/server/worker.clj \
  src/main/shadow/cljs/devtools/server/worker/impl.clj \
  src/repl/shadow/cljs/bun_runtime_test.clj
git commit -m "feat: auto-start managed runtimes for watched node builds"
```

---

### Task 5: End-to-End REPL Verification and Help Text

**Files:**
- Modify: `/Users/tmk/dev/my/shadow-cljs/src/main/shadow/txt/repl-help.txt`

#### Explore

1. **Step 1: Confirm help text still describes Node-only behavior**

```bash
cd /Users/tmk/dev/my/shadow-cljs
clj-nrepl-eval -p PORT <<'EOF'
(slurp "src/main/shadow/txt/repl-help.txt")
EOF
```

Expected: the text says `launches a node process`.

#### Decide (RED)

2. **Step 2: Add a manual verification checklist and confirm the old wording is wrong**

No new automated RED is needed here; this is a docs/help follow-up after passing implementation tests.

#### Build (GREEN)

3. **Step 3: Update the REPL help text**

Update `/Users/tmk/dev/my/shadow-cljs/src/main/shadow/txt/repl-help.txt`:

```text
  (shadow/node-repl) - launches a Node-family process and connects to a CLJS REPL
```

4. **Step 4: Run the end-to-end Bun-backed REPL verification (MANUAL)**

**This step cannot be automated via `clj-nrepl-eval`.** Switching into CLJS mode via `(shadow/repl :build-id)` changes the nREPL session state so subsequent forms are evaluated as ClojureScript. `clj-nrepl-eval` sends all forms in a single CLJ eval, so `js/process.versions.bun` would fail at CLJ compile time. This must be done interactively in an nREPL-connected editor or terminal REPL.

In one terminal, keep `clojure -M:dev:start` running. Connect an nREPL client, then evaluate these forms **one at a time**:

```clojure
;; Form 1 (CLJ): Start the watched build
(require '[shadow.cljs.devtools.api :as shadow])
(shadow/watch
  {:build-id :bun-e2e
   :target :node-test
   :ns-regexp "test.(.+)-test$"
   :ui-driven true
   :output-to "target/bun-e2e/script.js"
   :js-runtime :bun}
  {:autobuild false
   :sync true})

;; Form 2 (CLJ → CLJS): Switch into CLJS mode (this changes session state)
(shadow/repl :bun-e2e)

;; Form 3 (CLJS): Verify Bun is the runtime
(println js/process.versions.bun)
;; Expected: Bun version string

;; Form 4 (CLJS): Verify Bun globals
(println (exists? js/Bun))
;; Expected: true

;; Form 5 (CLJS → CLJ): Exit CLJS mode
:cljs/quit
```

5. **Step 5: Stop the worker**

```clojure
;; Form 6 (CLJ): Clean up
(shadow.cljs.devtools.api/stop-worker :bun-e2e)
```

Expected: `:stopped`

#### Persist

6. **Step 6: Re-run both focused test namespaces**

```bash
cd /Users/tmk/dev/my/shadow-cljs
clojure -M:dev -e "(require 'clojure.test 'shadow.cljs.js-runtime-test 'shadow.cljs.bun-runtime-test :reload) (clojure.test/run-tests 'shadow.cljs.js-runtime-test 'shadow.cljs.bun-runtime-test)"
```

Expected: PASS

7. **Step 7: Commit**

```bash
cd /Users/tmk/dev/my/shadow-cljs
git add src/main/shadow/txt/repl-help.txt
git commit -m "docs: describe Bun-capable node REPL launch"
```

---

## Final Verification

Run this once after all tasks:

```bash
cd /Users/tmk/dev/my/shadow-cljs
clojure -M:dev -e "(require 'clojure.test 'shadow.cljs.js-runtime-test 'shadow.cljs.bun-runtime-test :reload) (clojure.test/run-tests 'shadow.cljs.js-runtime-test 'shadow.cljs.bun-runtime-test)"
```

Expected: all focused Bun runtime tests PASS.

Then re-run the end-to-end REPL verification from Task 5 and confirm `js/process.versions.bun` is present from the `:bun-e2e` CLJS session.
