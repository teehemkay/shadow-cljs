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
