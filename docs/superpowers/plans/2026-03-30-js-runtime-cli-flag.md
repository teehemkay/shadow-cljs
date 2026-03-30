# `--js-runtime` CLI Flag Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a global `--js-runtime RUNTIME` CLI flag that lets users select an alternative JS runtime (e.g. Bun) for node-family builds without editing `shadow-cljs.edn`.

**Architecture:** The flag is parsed as a keyword in the global CLI spec, lifted into the `:config-merge` vector in `cli_actual.clj:main` so it flows through the existing `build/configure` deep-merge path, and validated/warned in `build.clj` and `shared.clj`. The `node-repl` path already destructures `:js-runtime` from opts, so it works without changes.

**Tech Stack:** Clojure, clojure.spec, clojure.tools.cli

**Spec:** `docs/superpowers/specs/2026-03-30-js-runtime-cli-flag-design.md`

---

## File Map

- **Modify:** `src/main/shadow/cljs/devtools/cli_opts.cljc` — add `--js-runtime` to `cli-spec`
- **Modify:** `src/main/shadow/cljs/devtools/cli_actual.clj` — lift `:js-runtime` into `:config-merge`
- **Modify:** `src/main/shadow/build/config.clj` — add `:js-runtime` spec + optional key to `::build`
- **Modify:** `src/main/shadow/build.clj` — call warning helper during configure
- **Modify:** `src/main/shadow/build/targets/shared.clj` — add warning helper; throw on unrecognized runtime values
- **Modify:** `doc/js-runtime.md` — add CLI usage section, fix "Valid values" wording
- **Modify:** `doc/js-runtime-architecture.md` — update spec location description
- **Test:** `src/test/shadow/cljs/devtools/cli_opts_test.clj` (new) — CLI parsing tests
- **Test:** `src/test/shadow/build/targets/shared_test.clj` (new) — runtime helper tests
- **Test:** `src/test/shadow/build/js_runtime_warning_test.clj` (new) — warning behavior tests

---

### Task 1: CLI Option Parsing — `--js-runtime` flag

**Files:**
- Create: `src/test/shadow/cljs/devtools/cli_opts_test.clj`
- Modify: `src/main/shadow/cljs/devtools/cli_opts.cljc:57-84`

- [ ] **Step 1: Write the failing test**

Create `src/test/shadow/cljs/devtools/cli_opts_test.clj`:

```clojure
(ns shadow.cljs.devtools.cli-opts-test
  (:require
    [clojure.test :refer (deftest is)]
    [shadow.cljs.devtools.cli-opts :as opts]))

(deftest parse-js-runtime-flag
  (let [{:keys [options errors]}
        (opts/parse ["node-repl" "--js-runtime" "bun"])]
    (is (nil? errors))
    (is (= :bun (:js-runtime options))))

  (let [{:keys [options errors]}
        (opts/parse ["watch" "app" "--js-runtime" "node"])]
    (is (nil? errors))
    (is (= :node (:js-runtime options))))

  (let [{:keys [options errors]}
        (opts/parse ["node-repl"])]
    (is (nil? errors))
    (is (nil? (:js-runtime options)))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `lein test :only shadow.cljs.devtools.cli-opts-test/parse-js-runtime-flag`
Expected: FAIL — `:js-runtime` is not a recognized option, so `errors` will be non-nil or the key will be missing.

- [ ] **Step 3: Add `--js-runtime` to `cli-spec`**

In `src/main/shadow/cljs/devtools/cli_opts.cljc`, add the following line to the `cli-spec` vector, after the `--via` line (line 83) and before the `--help` line (line 84):

```clojure
   [nil "--js-runtime RUNTIME" "use alternative JS runtime (e.g. bun) for node-family builds"
    :parse-fn keyword]
```

- [ ] **Step 4: Run test to verify it passes**

Run: `lein test :only shadow.cljs.devtools.cli-opts-test/parse-js-runtime-flag`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/test/shadow/cljs/devtools/cli_opts_test.clj src/main/shadow/cljs/devtools/cli_opts.cljc
git commit -m "feat: add --js-runtime flag to CLI option spec"
```

---

### Task 2: Lift `:js-runtime` into `:config-merge` in `cli_actual.clj`

**Files:**
- Modify: `src/test/shadow/cljs/devtools/cli_opts_test.clj`
- Modify: `src/main/shadow/cljs/devtools/cli_actual.clj:132-134`

- [ ] **Step 1: Write the failing test**

Add to `src/test/shadow/cljs/devtools/cli_opts_test.clj`:

```clojure
(deftest js-runtime-lifted-into-config-merge
  (let [{:keys [options]}
        (opts/parse ["watch" "app" "--js-runtime" "bun"])]
    (is (= :bun (:js-runtime options)))
    (let [lifted (opts/lift-js-runtime options)]
      ;; :js-runtime is copied into :config-merge for build/configure
      (is (= [{:js-runtime :bun}] (:config-merge lifted)))
      ;; :js-runtime is also kept as a top-level key for node-repl
      (is (= :bun (:js-runtime lifted)))))

  ;; when no --js-runtime, config-merge is untouched
  (let [{:keys [options]}
        (opts/parse ["watch" "app"])]
    (let [lifted (opts/lift-js-runtime options)]
      (is (nil? (:config-merge lifted))))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `lein test :only shadow.cljs.devtools.cli-opts-test/js-runtime-lifted-into-config-merge`
Expected: FAIL — `opts/lift-js-runtime` does not exist yet.

- [ ] **Step 3: Implement `lift-js-runtime` in `cli_opts.cljc`**

Add to `src/main/shadow/cljs/devtools/cli_opts.cljc`, after the `conj-vec` function (after line 55):

```clojure
(defn lift-js-runtime
  "When :js-runtime is present in options, copies it into :config-merge
   so it flows through build/configure's deep-merge path. The key is kept
   in options as well because node-repl reads it directly from opts."
  [options]
  (if-let [rt (:js-runtime options)]
    (update options :config-merge conj-vec {:js-runtime rt})
    options))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `lein test :only shadow.cljs.devtools.cli-opts-test/js-runtime-lifted-into-config-merge`
Expected: PASS

- [ ] **Step 5: Add test for config-merge accumulation**

Add to the test file to verify `--js-runtime` accumulates with existing `--config-merge`:

```clojure
(deftest js-runtime-accumulates-with-config-merge
  (let [{:keys [options]}
        (opts/parse ["watch" "app"
                     "--config-merge" "{:devtools {:preloads [foo]}}"
                     "--js-runtime" "bun"])]
    (let [lifted (opts/lift-js-runtime options)]
      (is (= [{:devtools {:preloads ['foo]}} {:js-runtime :bun}]
             (:config-merge lifted))))))
```

- [ ] **Step 6: Run test to verify it passes**

Run: `lein test :only shadow.cljs.devtools.cli-opts-test/js-runtime-accumulates-with-config-merge`
Expected: PASS (config-merge is a vector; `conj-vec` appends)

- [ ] **Step 7: Wire `lift-js-runtime` into `cli_actual.clj:main`**

In `src/main/shadow/cljs/devtools/cli_actual.clj`, modify the `main` function. Change lines 132-134 from:

```clojure
(defn main [& args]
  (let [{:keys [action builds options summary errors] :as opts}
        (opts/parse args)
```

to:

```clojure
(defn main [& args]
  (let [{:keys [action builds summary errors] :as opts}
        (opts/parse args)

        options
        (opts/lift-js-runtime (:options opts))

        opts
        (assoc opts :options options)
```

- [ ] **Step 8: Add end-to-end parse+lift test**

Add to `src/test/shadow/cljs/devtools/cli_opts_test.clj` a test that exercises
the full pipeline as `main` would see it — parse then lift — verifying the
downstream `options` map has `:js-runtime` in `:config-merge` and not as a
top-level key:

```clojure
(deftest parse-then-lift-end-to-end
  (testing "build action: :js-runtime lands in :config-merge"
    (let [{:keys [options] :as parsed} (opts/parse ["watch" "app" "--js-runtime" "bun"])
          lifted (opts/lift-js-runtime options)]
      (is (= [{:js-runtime :bun}] (:config-merge lifted)))
      ;; verify the rewritten opts map matches what cli_actual.clj:main produces
      (let [opts (assoc parsed :options lifted)]
        (is (= [{:js-runtime :bun}] (get-in opts [:options :config-merge]))))))

  (testing "node-repl: :js-runtime available both in opts and config-merge"
    ;; node-repl* reads :js-runtime directly from opts to build process argv,
    ;; AND it flows through config-merge via build/configure. Both must work.
    (let [{:keys [options]} (opts/parse ["node-repl" "--js-runtime" "bun"])
          lifted (opts/lift-js-runtime options)]
      (is (= :bun (:js-runtime lifted)))
      (is (= [{:js-runtime :bun}] (:config-merge lifted))))))
```

- [ ] **Step 9: Run tests to verify they pass**

Run: `lein test :only shadow.cljs.devtools.cli-opts-test`
Expected: PASS

- [ ] **Step 10: Commit**

```bash
git add src/test/shadow/cljs/devtools/cli_opts_test.clj \
        src/main/shadow/cljs/devtools/cli_opts.cljc \
        src/main/shadow/cljs/devtools/cli_actual.clj
git commit -m "feat: lift --js-runtime into :config-merge for build actions"
```

---

### Task 3: Add `:js-runtime` to base build spec

**Files:**
- Modify: `src/main/shadow/build/config.clj:24-30`

- [ ] **Step 1: Run existing tests to establish baseline**

Run: `lein test`
Expected: All existing tests pass.

- [ ] **Step 2: Add `:js-runtime` spec and optional key**

In `src/main/shadow/build/config.clj`, add a spec for `:js-runtime` after the `::target` spec (after line 8):

```clojure
(s/def ::js-runtime keyword?)
```

Then modify the `::build` spec (lines 24-30) to include it as optional:

```clojure
(s/def ::build
  (s/keys
    :req-un
    [::build-id
     ::target]
    :opt-un
    [::build-hooks
     ::js-runtime]))
```

- [ ] **Step 3: Run tests to verify nothing broke**

Run: `lein test`
Expected: All existing tests still pass.

- [ ] **Step 4: Commit**

```bash
git add src/main/shadow/build/config.clj
git commit -m "feat: add :js-runtime as optional key in base build spec"
```

---

### Task 4: Warn on non-node-family builds in `build/configure`

**Files:**
- Create: `src/test/shadow/build/js_runtime_warning_test.clj`
- Modify: `src/main/shadow/build.clj:3-24` (require) and `src/main/shadow/build.clj:354-365`

The warning point is in `build/configure`, in an imperative block before the
build-state threading form. This means `util/warn` (which threads through build
state) is not usable here. The codebase convention for user-facing messages in
this context is `println` with a `"shadow-cljs - "` prefix, so we use that.

- [ ] **Step 1: Write the failing test**

Create `src/test/shadow/build/js_runtime_warning_test.clj`:

```clojure
(ns shadow.build.js-runtime-warning-test
  (:require
    [clojure.test :refer (deftest is testing)]
    [shadow.build.targets.shared :as shared]))

(deftest node-family-target-test
  (testing "node-family targets"
    (is (true? (shared/node-family-target? {:target :node-script})))
    (is (true? (shared/node-family-target? {:target :node-test}))))

  (testing "non-node-family targets"
    (is (false? (shared/node-family-target? {:target :browser})))
    (is (false? (shared/node-family-target? {:target :esm})))
    (is (false? (shared/node-family-target? {:target :react-native})))))

(deftest js-runtime-warning-printed-for-non-node-targets
  (testing "warning is printed for browser target with :js-runtime"
    (let [output (with-out-str
                   (shared/warn-if-js-runtime-ignored
                     {:target :browser :build-id :app :js-runtime :bun}))]
      (is (.contains output "js-runtime"))
      (is (.contains output "ignored"))))

  (testing "no warning for node-script target with :js-runtime"
    (let [output (with-out-str
                   (shared/warn-if-js-runtime-ignored
                     {:target :node-script :build-id :app :js-runtime :bun}))]
      (is (= "" output))))

  (testing "no warning when :js-runtime is absent"
    (let [output (with-out-str
                   (shared/warn-if-js-runtime-ignored
                     {:target :browser :build-id :app}))]
      (is (= "" output)))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `lein test :only shadow.build.js-runtime-warning-test`
Expected: FAIL — `shared/warn-if-js-runtime-ignored` does not exist yet.

- [ ] **Step 3: Implement `warn-if-js-runtime-ignored` in `shared.clj`**

In `src/main/shadow/build/targets/shared.clj`, add after the `managed-runtime?` function (after line 310):

```clojure
(defn warn-if-js-runtime-ignored
  "Prints a warning when :js-runtime is set on a non-node-family build config."
  [{:keys [build-id target js-runtime] :as build-config}]
  (when (and (contains? build-config :js-runtime)
             (not (node-family-target? build-config)))
    (println
      (format "shadow-cljs - warning: :js-runtime %s ignored for build %s, target %s is not a node-family target"
        (name js-runtime) (name build-id) (name target)))))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `lein test :only shadow.build.js-runtime-warning-test`
Expected: PASS

- [ ] **Step 5: Wire `warn-if-js-runtime-ignored` into `build/configure`**

In `src/main/shadow/build.clj`, add to the `:require` block (around line 3):

```clojure
[shadow.build.targets.shared :as shared]
```

Then add after line 359 (after the spec validation block, before the `:source-paths` check):

```clojure
     (shared/warn-if-js-runtime-ignored config)
```

- [ ] **Step 6: Run full test suite**

Run: `lein test`
Expected: All tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/test/shadow/build/js_runtime_warning_test.clj \
        src/main/shadow/build/targets/shared.clj \
        src/main/shadow/build.clj
git commit -m "feat: warn when :js-runtime is set on non-node-family builds"
```

---

### Task 5: Throw on unrecognized runtime values in `shared.clj`

**Files:**
- Create: `src/test/shadow/build/targets/shared_test.clj`
- Modify: `src/main/shadow/build/targets/shared.clj:292-306`

- [ ] **Step 1: Write the failing tests**

Create `src/test/shadow/build/targets/shared_test.clj`:

```clojure
(ns shadow.build.targets.shared-test
  (:require
    [clojure.test :refer (deftest is testing)]
    [shadow.build.targets.shared :as shared]))

(deftest js-runtime-command-test
  (testing "known runtimes"
    (is (= "bun" (shared/js-runtime-command {:js-runtime :bun})))
    (is (= "node" (shared/js-runtime-command {:js-runtime :node})))
    (is (= "node" (shared/js-runtime-command {}))))

  (testing "unknown runtime throws"
    (is (thrown-with-msg? Exception #"unknown"
          (shared/js-runtime-command {:js-runtime :deno})))))

(deftest js-runtime-stdin-argv-test
  (testing "known runtimes"
    (is (= ["bun" "run" "-"] (shared/js-runtime-stdin-argv {:js-runtime :bun})))
    (is (= ["node"] (shared/js-runtime-stdin-argv {:js-runtime :node})))
    (is (= ["node"] (shared/js-runtime-stdin-argv {}))))

  (testing "unknown runtime throws"
    (is (thrown-with-msg? Exception #"unknown"
          (shared/js-runtime-stdin-argv {:js-runtime :deno})))))

(deftest js-runtime-file-argv-test
  (testing "known runtimes"
    (is (= ["bun" "run" "out/test.js"]
           (shared/js-runtime-file-argv {:js-runtime :bun :output-to "out/test.js"})))
    (is (= ["node" "out/test.js"]
           (shared/js-runtime-file-argv {:js-runtime :node :output-to "out/test.js"})))
    (is (= ["node" "out/test.js"]
           (shared/js-runtime-file-argv {:output-to "out/test.js"}))))

  (testing "unknown runtime throws"
    (is (thrown-with-msg? Exception #"unknown"
          (shared/js-runtime-file-argv {:js-runtime :deno :output-to "out/test.js"})))))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `lein test :only shadow.build.targets.shared-test`
Expected: the "unknown runtime throws" assertions FAIL — current code silently falls back to Node.

- [ ] **Step 3: Change `case` defaults to throw**

In `src/main/shadow/build/targets/shared.clj`, modify the three helper functions (lines 292-306).

Change `js-runtime-command` from:

```clojure
(defn js-runtime-command [build-config]
  (case (js-runtime build-config)
    :bun "bun"
    "node"))
```

to:

```clojure
(defn js-runtime-command [build-config]
  (case (js-runtime build-config)
    :node "node"
    :bun "bun"
    (throw (ex-info (str "unknown :js-runtime " (pr-str (js-runtime build-config)))
             {:js-runtime (js-runtime build-config)}))))
```

Change `js-runtime-stdin-argv` from:

```clojure
(defn js-runtime-stdin-argv [build-config]
  (case (js-runtime build-config)
    :bun ["bun" "run" "-"]
    ["node"]))
```

to:

```clojure
(defn js-runtime-stdin-argv [build-config]
  (case (js-runtime build-config)
    :node ["node"]
    :bun ["bun" "run" "-"]
    (throw (ex-info (str "unknown :js-runtime " (pr-str (js-runtime build-config)))
             {:js-runtime (js-runtime build-config)}))))
```

Change `js-runtime-file-argv` from:

```clojure
(defn js-runtime-file-argv [{:keys [output-to] :as build-config}]
  (let [output-path (.getPath (io/file output-to))]
    (case (js-runtime build-config)
      :bun ["bun" "run" output-path]
      ["node" output-path])))
```

to:

```clojure
(defn js-runtime-file-argv [{:keys [output-to] :as build-config}]
  (let [output-path (.getPath (io/file output-to))]
    (case (js-runtime build-config)
      :node ["node" output-path]
      :bun ["bun" "run" output-path]
      (throw (ex-info (str "unknown :js-runtime " (pr-str (js-runtime build-config)))
               {:js-runtime (js-runtime build-config)})))))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `lein test :only shadow.build.targets.shared-test`
Expected: PASS

- [ ] **Step 5: Run full test suite**

Run: `lein test`
Expected: All tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/test/shadow/build/targets/shared_test.clj \
        src/main/shadow/build/targets/shared.clj
git commit -m "feat: throw on unrecognized :js-runtime values instead of silent fallback"
```

---

### Task 6: Update documentation

**Files:**
- Modify: `doc/js-runtime.md`

- [ ] **Step 1: Add CLI usage section to the docs**

In `doc/js-runtime.md`, add a new section after the "Standalone node-repl" section (after line 63). Insert:

```markdown
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
```

- [ ] **Step 2: Update the "Valid values" section**

In `doc/js-runtime.md`, replace the existing "Valid values" section (lines 69-71):

```markdown
## Valid values

`:js-runtime` accepts `:node` or `:bun`. Any other value will fail spec validation.
```

with:

```markdown
## Valid values

`:js-runtime` accepts `:node` or `:bun`. Any other value will produce an error
at build time or when starting a REPL.
```

- [ ] **Step 3: Update architecture doc**

In `doc/js-runtime-architecture.md`, replace lines 41-42:

```markdown
The spec `::js-runtime` is defined as `#{:node :bun}` in the same namespace
and included in both the `:node-script` and `:node-test` target specs.
```

with:

```markdown
The spec `::js-runtime` is defined as `keyword?` in `shadow.build.config` and
included as an optional key in the base `::build` spec (accepted by all
targets). It is also included in the `:node-script` and `:node-test` target
specs. On non-node-family targets, it is accepted but ignored with a warning.
```

- [ ] **Step 4: Commit**

```bash
git add doc/js-runtime.md doc/js-runtime-architecture.md
git commit -m "docs: add CLI usage for --js-runtime flag, update architecture doc"
```

---

### Task 7: Final verification

- [ ] **Step 1: Run full test suite**

Run: `lein test`
Expected: All tests pass, no regressions.

- [ ] **Step 2: Verify CLI help output includes the new flag**

Run: `lein run -m shadow.cljs.devtools.cli-actual -- --help`
Expected: `--js-runtime RUNTIME` appears in the options list with the description text.

- [ ] **Step 3: Commit any remaining changes**

If any fixups were needed, commit them.
