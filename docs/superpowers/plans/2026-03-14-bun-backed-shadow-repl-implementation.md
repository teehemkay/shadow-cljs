# Bun-Backed shadow-cljs REPL Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.
>
> **Workspace:** `/Users/tmk/dev/my/shadow-cljs`
>
> **REPL-driven development is mandatory.** Use the `cljs-repl` skill. For `.clj` files, stay in CLJ mode for Explore → Decide (RED) → Build (GREEN) → Persist. Use CLJS mode only for the end-to-end Bun runtime verification task. All REPL work goes through `clj-nrepl-eval`.

**Goal:** Add opt-in Bun-backed managed runtimes for Node-family `shadow-cljs` builds while preserving `clj-nrepl-eval` + `(shadow/repl :build-id)`.

**Architecture:** Add `:js-runtime` as an opt-in selector on Node-family builds. Pure command/bootstrap helpers live in a new server-side helper namespace plus shared target-spec helpers. `shadow/node-repl` and `:node-test` autorun use the selected executable directly. Watched Node-family builds gain a worker-managed external JS runtime that launches on demand when `shadow/repl` selects a build with explicit `:js-runtime` and no runtime is connected; the worker owns process lifecycle and uses a bootstrap script to load the watched build output under Bun or Node and stay alive for REPL evals.

**Tech Stack:** Clojure, ClojureScript, shadow-cljs internals, nREPL, Bun, cljs.test

**Design:** `docs/plans/2026-03-14-bun-backed-shadow-repl-design.md`

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

#### Explore

1. **Step 1: Confirm `:js-runtime` is not modeled yet**

```bash
cd /Users/tmk/dev/my/shadow-cljs
clj-nrepl-eval -p PORT <<'EOF'
(require '[shadow.build.targets.shared :as shared] :reload)
(println (contains? (ns-publics 'shadow.build.targets.shared) 'js-runtime-command))
(println (contains? (ns-publics 'shadow.build.targets.shared) 'managed-runtime?))
EOF
```

Expected: both lines print `false`.

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
    [shadow.build.targets.shared :as shared]
    [shadow.cljs.devtools.server.js-runtime :as js-runtime]))

(deftest test-js-runtime-defaults-to-node
  (is (= :node (shared/js-runtime {:target :node-script}))))

(deftest test-js-runtime-command-selection
  (is (= "node" (shared/js-runtime-command {:target :node-script})))
  (is (= "bun" (shared/js-runtime-command {:target :node-script :js-runtime :bun}))))

(deftest test-managed-runtime-is-opt-in
  (is (false? (shared/managed-runtime? {:target :node-script})))
  (is (true? (shared/managed-runtime? {:target :node-script :js-runtime :bun})))
  (is (false? (shared/managed-runtime? {:target :browser :js-runtime :bun}))))

(deftest test-bootstrap-source-keeps-runtime-alive
  (let [src (js-runtime/bootstrap-source
              {:output-to "/tmp/demo.js"
               :module-format :commonjs})]
    (is (.contains src "require("))
    (is (.contains src "setInterval"))))

(deftest test-bootstrap-source-supports-esm
  (let [src (js-runtime/bootstrap-source
              {:output-to "/tmp/demo.mjs"
               :module-format :esm})]
    (is (.contains src "import("))
    (is (.contains src "setInterval"))))

(deftest test-node-script-target-spec-accepts-js-runtime
  (is (s/valid? (config/target-spec :node-script)
        {:target :node-script
         :main 'demo.script/main
         :output-to "out/demo.js"
         :js-runtime :bun})))
```

4. **Step 4: Run the focused runner to confirm RED**

```bash
cd /Users/tmk/dev/my/shadow-cljs
clojure -M:dev -e "(require 'clojure.test 'shadow.cljs.js-runtime-test) (clojure.test/run-tests 'shadow.cljs.js-runtime-test)"
```

Expected: FAIL because `shared/js-runtime`, `shared/js-runtime-command`, `shared/managed-runtime?`, and `shadow.cljs.devtools.server.js-runtime` do not exist yet.

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

(defn managed-runtime? [build-config]
  (and (node-family-target? build-config)
       (explicit-js-runtime? build-config)))
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

7. **Step 7: Add the runtime bootstrap helper namespace**

Create `/Users/tmk/dev/my/shadow-cljs/src/main/shadow/cljs/devtools/server/js_runtime.clj`:

```clojure
(ns shadow.cljs.devtools.server.js-runtime
  (:require
    [clojure.java.io :as io])
  (:import
    [java.io File]))

(defn bootstrap-file
  [cache-root build-id module-format]
  (io/file
    cache-root
    (str "shadow-managed-runtime-"
         (name build-id)
         (if (= :esm module-format) ".mjs" ".cjs"))))

(defn bootstrap-source
  [{:keys [output-to module-format]}]
  (let [abs-output (.getAbsolutePath (io/file output-to))
        load-form
        (if (= :esm module-format)
          (str "await import(" (pr-str (str "file://" abs-output)) ");")
          (str "require(" (pr-str abs-output) ");"))]
    (str load-form "\n"
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
(println (shared/managed-runtime? {:target :node-script}))
(println (shared/managed-runtime? {:target :node-script :js-runtime :bun}))
(println (js-runtime/bootstrap-source {:output-to "/tmp/demo.js" :module-format :commonjs}))
EOF
```

Expected: `:node`, `"bun"`, `false`, `true`, then bootstrap source containing `require(` and `setInterval`.

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

Create a temporary script that only succeeds under Bun:

```bash
cd /Users/tmk/dev/my/shadow-cljs
mkdir -p target/bun-runtime
printf 'process.exit(process.versions.bun ? 0 : 17);\n' > target/bun-runtime/needs-bun.js
clj-nrepl-eval -p PORT <<'EOF'
(require '[shadow.build.targets.node-test :as node-test] :reload)
(println
  (::node-test/exit-code
    (node-test/autorun-test
      {:shadow.build/config
       {:output-to "target/bun-runtime/needs-bun.js"
        :js-runtime :bun}})))
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
    [shadow.build.targets.node-test :as node-test]))

(defn bun-available? []
  (zero? (:exit (sh "bun" "--version"))))

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
                {:shadow.build/config
                 {:output-to (.getPath script)
                  :js-runtime :bun}})))))))
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
  (let [runtime-command
        (or node-command
            (shared/js-runtime-command {:target :node-script
                                        :js-runtime js-runtime}))
        ...
        node-proc
        (-> (ProcessBuilder.
              (into-array
                (into [runtime-command] node-args)))
            ...)]
```

6. **Step 6: Update `:node-test` autorun to use the same selector**

Update `/Users/tmk/dev/my/shadow-cljs/src/main/shadow/build/targets/node_test.clj`:

```clojure
(defn autorun-test [{::build/keys [config] :as state}]
  (util/with-logged-time
    [state {:type ::autorun}]
    (let [script-args
          [(shared/js-runtime-command config) (:output-to config)]
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
  '[shadow.cljs.devtools.server.repl-impl :as repl-impl]
  :reload)
(println
  (::node-test/exit-code
    (node-test/autorun-test
      {:shadow.build/config
       {:output-to "target/bun-runtime/needs-bun.js"
        :js-runtime :bun}})))
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

Append to `/Users/tmk/dev/my/shadow-cljs/src/repl/shadow/cljs/bun_runtime_test.clj`:

```clojure
(ns shadow.cljs.bun-runtime-test
  (:require
    ...
    [shadow.cljs.devtools.api :as api]))

(deftest test-watched-node-build-can-start-managed-bun-runtime
  (if-not (bun-available?)
    (testing "Bun not installed"
      (is true))
    (do
      (api/watch
        {:build-id :bun-watch-test
         :target :node-test
         :ns-regexp "test.(.+)-test$"
         :ui-driven true
         :output-to "target/bun-watch-test/script.js"
         :js-runtime :bun}
        {:autobuild false
         :sync true})
      (try
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
          (let [module-format
                (get-in (:build-state worker-state) [:build-options :module-format] :commonjs)]
            (js-runtime/bootstrap-file cache-root (:build-id build-config) module-format))
          module-format
          (get-in (:build-state worker-state) [:build-options :module-format] :commonjs)
          bootstrap-source
          (js-runtime/bootstrap-source
            {:output-to (:output-to build-config)
             :module-format module-format})
          _ (spit bootstrap-file bootstrap-source)
          process
          (-> (ProcessBuilder.
                (into-array
                  [(shared/js-runtime-command build-config)
                   (.getAbsolutePath bootstrap-file)]))
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
      (>!! reply-to :started))
    next-state))
```

Also update worker shutdown cleanup in the existing `:do-shutdown` function to call `stop-managed-runtime`.

7. **Step 7: Add the public API that waits for runtime connection**

Update `/Users/tmk/dev/my/shadow-cljs/src/main/shadow/cljs/devtools/api.clj`:

```clojure
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

Then update `api/repl` to call `ensure-runtime` before entering CLJS mode when the target build has explicit `:js-runtime`.

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

4. **Step 4: Run the end-to-end Bun-backed REPL verification**

In one terminal, keep `clojure -M:dev:start` running. Then:

```bash
cd /Users/tmk/dev/my/shadow-cljs
clj-nrepl-eval -p PORT <<'EOF'
(require '[shadow.cljs.devtools.api :as shadow] :reload)
(shadow/watch
  {:build-id :bun-e2e
   :target :node-test
   :ns-regexp "test.(.+)-test$"
   :ui-driven true
   :output-to "target/bun-e2e/script.js"
   :js-runtime :bun}
  {:autobuild false
   :sync true})
EOF
```

Then switch the nREPL session into CLJS mode through the watched build:

```bash
cd /Users/tmk/dev/my/shadow-cljs
clj-nrepl-eval -p PORT <<'EOF'
(do
  (shadow.cljs.devtools.api/repl :bun-e2e)
  (println js/process.versions.bun)
  (println (exists? js/Bun)))
EOF
```

Expected:
- first command prints a Bun version string
- second command prints `true`

5. **Step 5: Return to CLJ mode and stop the worker**

```bash
cd /Users/tmk/dev/my/shadow-cljs
clj-nrepl-eval -p PORT <<'EOF'
(shadow.cljs.devtools.api/stop-worker :bun-e2e)
:done
EOF
```

Expected: `:done`

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
