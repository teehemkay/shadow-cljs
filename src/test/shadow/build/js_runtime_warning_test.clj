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

(deftest shadow-build-loads-with-shared-require
  ;; This catches a broken require in shadow.build after adding shared.
  ;; A full build/configure integration test would require bootstrapping
  ;; the entire build system, which is disproportionate for one call site.
  (require 'shadow.build)
  (is (some? (find-ns 'shadow.build))))
