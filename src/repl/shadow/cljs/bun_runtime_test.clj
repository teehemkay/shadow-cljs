(ns shadow.cljs.bun-runtime-test
  (:require
    [clojure.java.io :as io]
    [clojure.java.shell :refer [sh]]
    [clojure.test :refer [deftest is testing]]
    [shadow.build.log :as build-log]
    [shadow.build.targets.node-test :as node-test]))

(defn bun-available? []
  (try
    (zero? (:exit (sh "bun" "--version")))
    (catch Exception _ false)))

(def ^:private noop-logger
  (reify build-log/BuildLog (log* [_ _ _])))

(defn- make-autorun-state
  "Builds a minimal state map that satisfies autorun-test's preconditions.
   autorun-test uses util/with-logged-time which requires a valid build state
   (build-state? checks for :shadow.build.data/build-state true and :logger)."
  [config]
  {:shadow.build.data/build-state true
   :logger noop-logger
   :shadow.build/config config})

(deftest test-node-test-autorun-respects-bun
  (if-not (bun-available?)
    (testing "Bun not installed"
      (is true))
    (let [script (io/file "target" "bun-runtime" "needs-bun.js")]
      (io/make-parents script)
      (spit script "process.exit(process.versions.bun ? 0 : 17);\n")
      (is (= 0
            (::node-test/exit-code
              (node-test/autorun-test
                (make-autorun-state
                  {:output-to (.getPath script)
                   :js-runtime :bun}))))))))
