(ns shadow.cljs.bun-runtime-test
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [shadow.cljs.test-util :refer [bun-available?]]
    [shadow.build.log :as build-log]
    [shadow.build.targets.node-test :as node-test]))


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
      ;; autorun-test frames the run's own output with two banners; the
      ;; script prints nothing, so the banners are all there is
      (let [result (atom nil)
            out (with-out-str
                  (reset! result
                    (node-test/autorun-test
                      (make-autorun-state
                        {:output-to (.getPath script)
                         :js-runtime :bun}))))]
        (is (= 0 (::node-test/exit-code @result)))
        (is (= ["========= Running Tests ======================="
                "==============================================="]
               (str/split-lines out)))))))
