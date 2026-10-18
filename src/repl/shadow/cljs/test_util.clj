(ns shadow.cljs.test-util
  (:require [clojure.pprint :as pp :refer (pprint)]
            [clojure.java.shell :refer [sh]]))

(defn wide-pprint [x]
  (binding [clojure.pprint/*print-right-margin* 120]
    (pprint x)))

(defn program-available?
  "True when PROGRAM is on the JVM's PATH and runs with --version.

  Absence is asked of the shell's command -v, whose exit status says it as
  data. Telling a missing executable apart from the IOException's message
  was rejected: the JDK words errno 2 differently by version and launch
  mechanism (\"error=2\", \"Exec failed, error: 2\"), and a mismatch turned
  a missing bun into an error. Any exec failure, of sh or of PROGRAM (an
  exec refused by a launch restriction), still throws, failing the run
  instead of letting a test pass vacuously."
  [program]
  (and (zero? (:exit (sh "sh" "-c" "command -v \"$1\"" "sh" program)))
       (zero? (:exit (sh program "--version")))))

(def ^:private bun-probe
  (delay (program-available? "bun")))

(defn bun-available?
  "True when bun runs from the JVM's PATH; the Bun-backed tests pass
  vacuously without it. Probed once per JVM."
  []
  @bun-probe)
