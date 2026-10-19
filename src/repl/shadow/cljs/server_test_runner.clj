(ns shadow.cljs.server-test-runner
  "Runs src/repl test namespaces inside a running shadow-cljs server, the
  way CI runs the fork's own suites. lein test cannot: its :test-paths are
  src/test, and shadow.cljs.managed-runtime-test needs a server and its
  nREPL port. The :repl-tests profile puts src/repl on the classpath, so
  the suites run on the release's own dependencies:

    lein with-profile +repl-tests run -m shadow.cljs.server-test-runner <ns> ...

  Exits non-zero when a test fails or errors, and before starting the
  server when a named namespace holds no tests: a mistyped name already
  fails to load, but a real namespace named in place of its test
  namespace would otherwise pass unnoticed beside the suites that run."
  (:require
    [clojure.test :as t]
    [shadow.cljs.devtools.config :as config]
    [shadow.cljs.devtools.server :as server]))

(defn namespaces-without-tests
  "The loaded namespaces among NSS that define no test."
  [nss]
  (vec (remove (fn [ns-sym] (some (comp :test meta) (vals (ns-interns ns-sym)))) nss)))

(defn -main [& ns-names]
  (let [nss (map symbol ns-names)]
    (when (empty? nss)
      (println "usage: lein with-profile +repl-tests run -m shadow.cljs.server-test-runner <ns> ...")
      (System/exit 2))
    (apply require nss)
    (when-some [empty-nss (seq (namespaces-without-tests nss))]
      (println "no tests in:" (pr-str empty-nss))
      (System/exit 2))
    ;; the repo's shadow-cljs.edn logs at :debug for working on shadow-cljs
    ;; itself; a test run wants warnings, which the tests capture where they
    ;; expect them, without the debug traces of a server shutting down
    (server/start! (assoc (config/load-cljs-edn!) :log {:level :info}))
    (let [{:keys [fail error]}
          (try
            (apply t/run-tests nss)
            (finally
              (server/stop!)))]
      (shutdown-agents)
      (System/exit (if (zero? (+ fail error)) 0 1)))))
