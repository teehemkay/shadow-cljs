(ns shadow.cljs.server-test-runner
  "Runs src/repl test namespaces inside a running shadow-cljs server, the
  way CI runs the fork's own suites. lein test cannot: its :test-paths are
  src/test, and shadow.cljs.managed-runtime-test needs a server and its
  nREPL port.

    lein javac
    clojure -M -m shadow.cljs.server-test-runner <ns> ...

  Exits non-zero when a test fails or errors, or when no test ran, so a
  mistyped namespace cannot pass empty."
  (:require
    [clojure.test :as t]
    [shadow.cljs.devtools.config :as config]
    [shadow.cljs.devtools.server :as server]))

(defn -main [& ns-names]
  (let [nss (map symbol ns-names)]
    (when (empty? nss)
      (println "usage: clojure -M -m shadow.cljs.server-test-runner <ns> ...")
      (System/exit 2))
    (apply require nss)
    ;; the repo's shadow-cljs.edn logs at :debug for working on shadow-cljs
    ;; itself; a test run wants warnings, which the tests capture where they
    ;; expect them, without the debug traces of a server shutting down
    (server/start! (assoc (config/load-cljs-edn) :log {:level :info}))
    (let [{:keys [test fail error]}
          (try
            (apply t/run-tests nss)
            (finally
              (server/stop!)))]
      (shutdown-agents)
      (System/exit (if (and (pos? test) (zero? (+ fail error))) 0 1)))))
