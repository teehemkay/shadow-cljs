(ns shadow.cljs.test-util
  (:require [clojure.pprint :as pp :refer (pprint)]
            [clojure.java.shell :refer [sh]]))

(defn wide-pprint [x]
  (binding [clojure.pprint/*print-right-margin* 120]
    (pprint x)))

(defn program-available?
  "True when PROGRAM is on the JVM's PATH and runs with --version, false
  when it is not on PATH.

  One shell asks command -v, whose exit status says absence as data (127),
  then execs PROGRAM; every spawn on a Mac under a launch restriction is a
  chance for the exec to be refused, so the probe spends one. Telling a
  missing executable apart from the IOException's message was rejected:
  the JDK words errno 2 differently by version and launch mechanism
  (\"error=2\", \"Exec failed, error: 2\"), and a mismatch turned a missing
  bun into an error. Any other failure, PROGRAM's exec refused or its
  --version failing, throws, failing the run instead of letting a test
  pass vacuously."
  [program]
  (let [{:keys [exit err]}
        (sh "sh" "-c" "command -v \"$1\" >/dev/null || exit 127; exec \"$1\" --version" "sh" program)]
    (case exit
      0 true
      127 false
      (throw (ex-info (str program " is on PATH but does not run")
               {:program program :exit exit :err err})))))

(defn- probe
  "A function answering (program-available? PROGRAM), asked once and kept.
  A throw is not kept, so the next call asks again: a delay was rejected
  because it caches its body's exception, and one refused exec would then
  fail every later run in a long-lived JVM."
  [program]
  (memoize #(program-available? program)))

(def ^:private bun-probe
  (probe "bun"))

(defn bun-available?
  "True when bun runs from the JVM's PATH; the Bun-backed tests pass
  vacuously without it. Probed once per JVM."
  []
  (bun-probe))
