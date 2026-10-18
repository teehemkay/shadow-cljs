(ns shadow.cljs.managed-runtime-test
  "A watched build with :js-runtime gets its JS process from the worker,
  and every eval has to reach that process however the session selected
  the build. Editors select with nrepl-select (CIDER's shadow init form
  does), people with shadow/repl, so both are driven here as a real nREPL
  client against shadow's own nREPL server.

  Needs a running shadow-cljs server (its nREPL port is read from
  .shadow-cljs/nrepl.port) and bun on PATH; without bun it passes vacuously,
  as bun-runtime-test does."
  (:require
    [clojure.java.io :as io]
    [clojure.java.shell :refer [sh]]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [nrepl.core :as nrepl]
    [shadow.cljs.devtools.api :as api]))

(defn- bun-available? []
  (try
    (zero? (:exit (sh "bun" "--version")))
    (catch Exception _ false)))

(defn- shadow-nrepl-port []
  (parse-long (str/trim (slurp ".shadow-cljs/nrepl.port"))))

(defn- probe-config [build-id]
  {:build-id build-id
   ;; :node-script, not :node-test: a :node-test build runs its tests on
   ;; load and exits, so its managed runtime never connects.
   :target :node-script
   :main 'shadow.cljs.managed-runtime-probe/main
   :output-to (str "target/" (name build-id) "/script.js")
   :js-runtime :bun})

(defn- eval-after-select
  "Clone a session on shadow's nREPL, evaluate SELECT-FORM in it, then CODE
  as ClojureScript. Returns CODE's :out and :err."
  [select-form code]
  (with-open [conn (nrepl/connect :port (shadow-nrepl-port))]
    (let [session (nrepl/client-session (nrepl/client conn 60000))
          run #(nrepl/combine-responses (nrepl/message session {:op "eval" :code %}))]
      (run select-form)
      (select-keys (run code) [:out :err]))))

(defn- with-watched-build
  "Watch a fresh managed build named BUILD-ID, compiled once, call F with
  its config, and stop the worker, which also stops its JS process."
  [build-id f]
  (let [config (probe-config build-id)]
    (api/watch config {:autobuild false :sync true})
    (try
      (f config)
      (finally
        (api/stop-worker build-id)))))

(deftest every-selection-path-reaches-the-managed-runtime
  (if-not (bun-available?)
    (testing "Bun not installed"
      (is true))
    (doseq [[select build-id] [["nrepl-select" :managed-probe-nrepl-select]
                               ["repl" :managed-probe-repl]]]
      (testing select
        (with-watched-build build-id
          (fn [_]
            (is (= {:out "true\n"}
                   (eval-after-select
                    (format "(shadow.cljs.devtools.api/%s %s)" select build-id)
                    "(println (some? js/process.versions.bun))")))))))))

(deftest a-runtime-that-never-connects-is-reported
  (if-not (bun-available?)
    (testing "Bun not installed"
      (is true))
    (with-watched-build :managed-probe-no-output
      (fn [{:keys [output-to]}]
        ;; Without the compiled output the bootstrap's require fails and the
        ;; process exits before it can connect.
        (io/delete-file output-to)
        (let [{:keys [out err]} (eval-after-select
                                 "(shadow.cljs.devtools.api/nrepl-select :managed-probe-no-output)"
                                 "(println :unreachable)")]
          (is (nil? out))
          (is (some? err))
          (is (str/includes? (str err) "bun runtime for :managed-probe-no-output did not connect within 5000 ms")))))))

(deftest a-build-without-a-repl-client-starts-no-runtime
  ;; A :node-test build that is not :ui-driven has no REPL client: started,
  ;; it would run its whole suite on every eval and exit. The eval reports
  ;; the generic message and nothing is spawned.
  (if-not (bun-available?)
    (testing "Bun not installed"
      (is true))
    (let [build-id :managed-probe-plain-node-test]
      (api/watch {:build-id build-id
                  :target :node-test
                  :ns-regexp "^managed-runtime-test-matches-nothing$"
                  :autorun false
                  :output-to (str "target/" (name build-id) "/script.js")
                  :js-runtime :bun}
        {:autobuild false :sync true})
      (try
        (is (= {:err "No available JS runtime.\nSee https://shadow-cljs.github.io/docs/UsersGuide.html#repl-troubleshooting"}
               (eval-after-select
                 (format "(shadow.cljs.devtools.api/nrepl-select %s)" build-id)
                 "(println :unreachable)")))
        (is (nil? (-> (api/get-worker build-id) :state-ref deref :managed-runtime))
            "no JS process was started")
        (finally
          (api/stop-worker build-id))))))

(deftest a-ui-driven-node-test-build-reaches-its-runtime
  ;; :ui-driven is what gives a :node-test build a REPL client; its main
  ;; then waits for the UI instead of running the suite and exiting.
  (if-not (bun-available?)
    (testing "Bun not installed"
      (is true))
    (let [build-id :managed-probe-ui-driven]
      (api/watch {:build-id build-id
                  :target :node-test
                  :ns-regexp "^managed-runtime-test-matches-nothing$"
                  :ui-driven true
                  :autorun false
                  :output-to (str "target/" (name build-id) "/script.js")
                  :js-runtime :bun}
        {:autobuild false :sync true})
      (try
        (is (= {:out "true\n"}
               (eval-after-select
                 (format "(shadow.cljs.devtools.api/nrepl-select %s)" build-id)
                 "(println (some? js/process.versions.bun))")))
        (finally
          (api/stop-worker build-id))))))
