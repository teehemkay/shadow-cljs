(ns shadow.cljs.test-util-test
  (:require
    [clojure.test :refer [deftest is]]
    [shadow.cljs.test-util :as tu :refer [program-available?]]))

(deftest a-missing-program-is-unavailable
  ;; the bun-backed tests rely on this to pass vacuously where bun is absent
  (is (false? (program-available? "shadow-cljs-test-util-no-such-program"))))

(deftest an-installed-program-is-available
  (is (true? (program-available? "bash"))))

(deftest a-program-that-is-present-but-fails-throws
  ;; `false` is on PATH and exits 1 whatever its arguments, as a present
  ;; program whose exec is refused does; that must fail the run rather
  ;; than read as absence
  (is (thrown? clojure.lang.ExceptionInfo (program-available? "false"))))

(deftest a-probe-that-throws-probes-again
  ;; bun-available? lives as long as the JVM, often a long-lived nREPL, so
  ;; one refused exec must fail only the run that hit it
  (let [calls (atom 0)]
    (with-redefs [tu/program-available?
                  (fn [_]
                    (if (= 1 (swap! calls inc))
                      (throw (ex-info "exec refused" {}))
                      true))]
      (let [probe (#'tu/probe "bun")]
        (is (thrown? clojure.lang.ExceptionInfo (probe)))
        (is (true? (probe)))
        (is (true? (probe)))
        (is (= 2 @calls) "an answer, once had, is kept")))))
