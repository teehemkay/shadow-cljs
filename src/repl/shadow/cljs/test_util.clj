(ns shadow.cljs.test-util
  (:require [clojure.pprint :as pp :refer (pprint)]
            [clojure.java.shell :refer [sh]]))

(defn wide-pprint [x]
  (binding [clojure.pprint/*print-right-margin* 120]
    (pprint x)))

(defn bun-available?
  "True when bun runs from the JVM's PATH; the Bun-backed tests pass
  vacuously without it."
  []
  (try
    (zero? (:exit (sh "bun" "--version")))
    (catch Exception _ false)))
