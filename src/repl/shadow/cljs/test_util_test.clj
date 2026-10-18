(ns shadow.cljs.test-util-test
  (:require
    [clojure.test :refer [deftest is]]
    [shadow.cljs.test-util :refer [program-available?]]))

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
