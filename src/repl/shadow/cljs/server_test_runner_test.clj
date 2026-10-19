(ns shadow.cljs.server-test-runner-test
  (:require
    [clojure.test :refer [deftest is]]
    [shadow.cljs.server-test-runner :as runner]))

(deftest a-named-namespace-without-tests-is-reported
  ;; shadow.cljs.test-util is real and loads, but holds no tests; named in
  ;; place of shadow.cljs.test-util-test it would otherwise pass unnoticed
  ;; beside the suites that do run
  (require 'shadow.cljs.test-util 'shadow.cljs.test-util-test)
  (is (= '[shadow.cljs.test-util]
         (runner/namespaces-without-tests '[shadow.cljs.test-util shadow.cljs.test-util-test]))))
