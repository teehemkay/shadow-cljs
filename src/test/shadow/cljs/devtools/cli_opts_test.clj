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
