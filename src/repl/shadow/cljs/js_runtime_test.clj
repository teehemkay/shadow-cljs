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
