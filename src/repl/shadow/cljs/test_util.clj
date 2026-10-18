(ns shadow.cljs.test-util
  (:require [clojure.pprint :as pp :refer (pprint)]
            [clojure.java.shell :refer [sh]]))

(defn wide-pprint [x]
  (binding [clojure.pprint/*print-right-margin* 120]
    (pprint x)))

(def ^:private bun-probe
  (delay
    (try
      (zero? (:exit (sh "bun" "--version")))
      (catch java.io.IOException e
        ;; only a missing executable means bun is absent; any other failure
        ;; (an exec refused by a launch restriction) fails the run instead of
        ;; letting a test pass vacuously
        (if (re-find #"error=2\b" (str (.getMessage e)))
          false
          (throw e))))))

(defn bun-available?
  "True when bun runs from the JVM's PATH; the Bun-backed tests pass
  vacuously without it. Probed once per JVM."
  []
  @bun-probe)
