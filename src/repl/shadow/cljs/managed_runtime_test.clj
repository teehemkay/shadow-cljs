(ns shadow.cljs.managed-runtime-test
  "A watched build with :js-runtime whose output carries the REPL client
  gets its JS process from the worker: started when a compile succeeds,
  restarted once if it exits after connecting. Evals never start it; they
  wait briefly for one that is starting and otherwise say why there is none.
  Editors are driven as a real nREPL client against shadow's own nREPL
  server, the way CIDER's nrepl-select connects.

  Needs a running shadow-cljs server (its nREPL port is read from
  .shadow-cljs/nrepl.port) and bun on PATH; without bun it passes vacuously."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [nrepl.core :as nrepl]
    [shadow.build.targets.shared :as shared]
    [shadow.cljs.devtools.api :as api]
    [shadow.cljs.test-util :refer [bun-available?]]))

(defn- shadow-nrepl-port []
  (parse-long (str/trim (slurp ".shadow-cljs/nrepl.port"))))

(defn- script-config
  "A :node-script build whose output carries the REPL client and stays
  alive; MAIN defaults to a main that does nothing."
  [build-id & {:keys [main] :or {main 'shadow.cljs.managed-runtime-probe/main}}]
  {:build-id build-id
   :target :node-script
   :main main
   :output-to (str "target/" (name build-id) "/script.js")
   :js-runtime :bun})

(defn- eval-after-select
  "Clone a session on shadow's nREPL, select BUILD-ID with nrepl-select, then
  evaluate CODE as ClojureScript. Returns CODE's :out and :err."
  [build-id code]
  (with-open [conn (nrepl/connect :port (shadow-nrepl-port))]
    (let [session (nrepl/client-session (nrepl/client conn 60000))
          run #(nrepl/combine-responses (nrepl/message session {:op "eval" :code %}))]
      (run (format "(shadow.cljs.devtools.api/nrepl-select %s)" build-id))
      (select-keys (run code) [:out :err]))))

(defn- timed
  "[result elapsed-ms] of calling F."
  [f]
  (let [start (System/currentTimeMillis)
        result (f)]
    [result (- (System/currentTimeMillis) start)]))

(defn- await-runtime
  "The first REPL runtime connected to BUILD-ID within TIMEOUT-MS that is not
  EXCEPT, or nil."
  [build-id timeout-ms & {:keys [except]}]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [ids (->> (api/repl-runtimes build-id) (map :client-id) (remove #{except}))]
        (cond
          (seq ids) (first ids)
          (> (System/currentTimeMillis) deadline) nil
          :else (do (Thread/sleep 50) (recur)))))))

(defn- with-watched-build
  "Watch CONFIG, compiled once, call F with it, and stop the worker, which
  also stops its JS process. CLI-OPTS go to api/watch. Without bun, F does
  not run and the test passes vacuously."
  ([config f] (with-watched-build config {} f))
  ([{:keys [build-id] :as config} cli-opts f]
   (if-not (bun-available?)
     (testing "Bun not installed"
       (is true))
     (do (api/watch config (merge {:autobuild false :sync true} cli-opts))
         (try
           (f config)
           (finally
             (api/stop-worker build-id)))))))

(def ^:private no-runtime-message
  "No available JS runtime.\nSee https://shadow-cljs.github.io/docs/UsersGuide.html#repl-troubleshooting")

(deftest the-runtime-starts-when-the-build-completes
  (with-watched-build (script-config :managed-probe-starts)
    (fn [{:keys [build-id]}]
      (is (some? (await-runtime build-id 10000))
          "connected without any eval asking for it"))))

(deftest an-editor-session-evaluates-in-the-managed-runtime
  (with-watched-build (script-config :managed-probe-editor)
    (fn [{:keys [build-id]}]
      (is (= {:out "true\n"}
             (eval-after-select build-id "(println (some? js/process.versions.bun))"))))))

(deftest cljs-eval-evaluates-in-the-managed-runtime
  ;; api/cljs-eval drives the same REPL loop without nREPL or stdin.
  (with-watched-build (script-config :managed-probe-cljs-eval)
    (fn [{:keys [build-id]}]
      (is (= ["true"]
             (:results (api/cljs-eval build-id "(some? js/process.versions.bun)" {})))))))

(deftest a-js-runtime-given-on-the-command-line-is-managed
  ;; --js-runtime reaches the build through :config-merge, so the raw build
  ;; config never carries it.
  (with-watched-build (dissoc (script-config :managed-probe-cli) :js-runtime)
    {:config-merge [{:js-runtime :bun}]}
    (fn [{:keys [build-id]}]
      (is (= {:out "true\n"}
             (eval-after-select build-id "(println (some? js/process.versions.bun))"))))))

(deftest a-build-without-the-repl-client-starts-no-runtime
  ;; A :node-test build that is not :ui-driven runs its whole suite and
  ;; exits; a build with devtools disabled has no client to connect.
  (doseq [config [{:build-id :managed-probe-plain-node-test
                   :target :node-test
                   :ns-regexp "^managed-runtime-test-matches-nothing$"
                   :autorun false
                   :output-to "target/managed-probe-plain-node-test/script.js"
                   :js-runtime :bun}
                  (assoc (script-config :managed-probe-devtools-off)
                    :devtools {:enabled false})]]
    (testing (name (:build-id config))
      (with-watched-build config
        (fn [{:keys [build-id]}]
          (let [[result ms] (timed #(eval-after-select build-id "(println :unreachable)"))]
            (is (= {:err no-runtime-message} result))
            (is (< ms 2000) "answered without waiting for a runtime"))
          (is (nil? (-> (api/get-worker build-id) :state-ref deref :managed-runtime))
              "no JS process was started"))))))

(deftest a-runtime-that-exits-before-connecting-is-reported-at-once
  (with-watched-build (script-config :managed-probe-exits
                        :main 'shadow.cljs.managed-runtime-exit-probe/main)
    (fn [{:keys [build-id]}]
      (dotimes [i 2]
        (testing (str "eval " (inc i))
          (let [[{:keys [out err]} ms] (timed #(eval-after-select build-id "(println :unreachable)"))]
            (is (nil? out))
            (is (str/includes? (str err) "bun runtime for :managed-probe-exits exited with code 3 before connecting"))
            (is (str/includes? (str err) "managed-runtime-exit-probe exits while loading")
                "the process's own output names the cause")
            (is (str/ends-with? (str err) "\n"))
            (is (< ms 7000) "bounded by one connect wait, not one per form")))))))

(deftest a-runtime-that-cannot-be-launched-is-reported
  (with-redefs [shared/js-runtime-file-argv (fn [_] ["/nonexistent/managed-probe-js-runtime"])]
    (with-watched-build (script-config :managed-probe-no-launch)
      (fn [{:keys [build-id]}]
        (let [[{:keys [out err]} ms] (timed #(eval-after-select build-id "(println :unreachable)"))]
          (is (nil? out))
          (is (str/includes? (str err) "bun runtime for :managed-probe-no-launch could not be launched"))
          (is (< ms 2000) "a failed launch never blocks the eval"))))))

(deftest a-runtime-that-exits-after-connecting-is-restarted
  (with-watched-build (script-config :managed-probe-restart)
    (fn [{:keys [build-id]}]
      (let [first-id (await-runtime build-id 10000)]
        (is (some? first-id))
        (api/cljs-eval build-id "(js/setTimeout #(js/process.exit 0) 100)" {})
        (is (some? (await-runtime build-id 10000 :except first-id))
            "a fresh process connected after the first exited")
        (is (= {:out "true\n"}
               (eval-after-select build-id "(println (some? js/process.versions.bun))")))))))
