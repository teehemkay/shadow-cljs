(ns shadow.cljs.devtools.cli-opts-test
  (:require
    [clojure.test :refer (deftest is testing)]
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

(deftest js-runtime-accumulates-with-config-merge
  (let [{:keys [options]}
        (opts/parse ["watch" "app"
                     "--config-merge" "{:devtools {:preloads [foo]}}"
                     "--js-runtime" "bun"])]
    (let [lifted (opts/lift-js-runtime options)]
      (is (= [{:devtools {:preloads ['foo]}} {:js-runtime :bun}]
             (:config-merge lifted))))))

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
