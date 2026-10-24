(ns shadow.cljs.managed-runtime-test
  "A watched build with :js-runtime whose output carries the REPL client
  gets its JS process from the worker: started after a successful compile
  when none is alive, and stopped when the build is no longer managed.
  Evals never start it; they wait briefly for a new one to connect and
  otherwise say why there is none. Editors are driven as a real nREPL
  client against shadow's own nREPL server, the way CIDER's nrepl-select
  connects.

  Needs a running shadow-cljs server (its nREPL port is read from
  .shadow-cljs/nrepl.port) and bun on PATH; without bun it passes vacuously."
  (:require
    [clojure.core.async :as async]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [nrepl.core :as nrepl]
    [shadow.cljs.devtools.api :as api]
    [shadow.cljs.devtools.server.js-runtime :as js-runtime]
    [shadow.cljs.devtools.server.repl-impl :as repl-impl]
    [shadow.cljs.devtools.server.runtime :as runtime]
    [shadow.cljs.devtools.server.util :as util]
    [shadow.cljs.devtools.server.worker :as worker]
    [shadow.cljs.devtools.server.worker.impl :as impl]
    [shadow.cljs.test-util :refer [bun-available?]]
    [shadow.jvm-log :as log])
  (:import
    [java.util.logging Filter Level LogRecord]))

(defn- shadow-nrepl-port []
  (parse-long (str/trim (slurp ".shadow-cljs/nrepl.port"))))

(defn- script-config
  "A :node-script build whose output carries the REPL client and stays
  alive; MAIN defaults to a main that prints one line."
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

(defn- poll
  "The first truthy value of F within TIMEOUT-MS, or nil."
  [timeout-ms f]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (or (f)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 50)
            (recur))))))

(defn- await-runtime
  "The first REPL runtime connected to BUILD-ID within TIMEOUT-MS that is not
  EXCEPT, or nil."
  [build-id timeout-ms & {:keys [except]}]
  (poll timeout-ms
    #(->> (api/repl-runtimes build-id) (map :client-id) (remove #{except}) first)))

(defn- await-no-runtime
  "Waits until the worker has dropped BUILD-ID's runtimes: a process that
  exits disconnects through a separate relay notice."
  [build-id]
  (poll 10000 #(empty? (api/repl-runtimes build-id))))

(defn- worker-state [build-id]
  (-> (api/get-worker build-id) :state-ref deref))

(defn- managed-state [build-id]
  (:managed-runtime (worker-state build-id)))

(defn- log-file [build-id]
  (js-runtime/log-file (get-in (runtime/get-instance!) [:config :cache-root]) build-id))

(defn- log-text [build-id]
  (let [f (log-file build-id)]
    (if (.exists f) (slurp f) "")))

(defn- occurrences [s sub]
  (count (re-seq (re-pattern (java.util.regex.Pattern/quote sub)) s)))

(defn- send-config!
  "Hand the worker a changed build config, as its config watch does."
  [build-id config]
  (async/>!! (-> (worker-state build-id) :channels :config-watch) {:config config}))

(defn- collect-expected-failures
  "A :log-chan for api/watch that keeps the build-failure messages whose
  :report satisfies EXPECTED? in the atom FAILURES, so a test can check an
  expected failure report rather than dump it. Every other message, an
  unexpected failure included, is printed with api/watch's own printer.

  Duplicates util/stdout-dump's drain loop on purpose: stdout-dump has no
  way to intercept a report, and under a long enough stall drops its
  oldest messages, while a test must intercept its expected report and
  can afford to lose none. The fixed buffer here keeps every message
  without making the worker, whose output mult waits on every tap, wait on
  the printing. Unlike stdout-dump, it keeps draining after the printer
  reports a closed output stream: the test's report may still be coming."
  [expected? failures]
  (let [ch (async/chan 1024)]
    (async/go-loop []
      (when-some [x (async/<! ch)]
        (if (and (= :build-failure (:type x)) (expected? (:report x)))
          (swap! failures conj x)
          (util/print-worker-out x false))
        (recur)))
    ch))

(defn- capturing-launch-failures
  "Calls F with an atom of the managed-runtime-launch-failed log records for
  BUILD-ID, kept out of the log while F runs so a test can check an expected
  warning rather than dump it; every other record is logged as usual. A
  filter on shadow's logger, because a handler added beside the console one
  sees the records but cannot keep them from it."
  [build-id f]
  (let [records (atom [])
        message (log/log-msg ::impl/managed-runtime-launch-failed {:build-id build-id})
        ^Filter previous (.getFilter log/logger)]
    (.setFilter log/logger
      (reify Filter
        (isLoggable [_ record]
          (if (= message (.getMessage record))
            (do (swap! records conj record) false)
            (or (nil? previous) (.isLoggable previous record))))))
    (try
      (f records)
      (finally
        (.setFilter log/logger previous)))))

(defn- when-bun
  "Calls F, or passes vacuously when bun is not installed."
  [f]
  (if-not (bun-available?)
    (testing "Bun not installed"
      (is true))
    (f)))

(defn- bootstrap-path [build-id]
  (js-runtime/bootstrap-file (get-in (runtime/get-instance!) [:config :cache-root]) build-id))

(defn- block-launch!
  "Makes the next launch for BUILD-ID fail for real: a directory where its
  bootstrap file is written."
  [build-id]
  (let [path (bootstrap-path build-id)]
    ;; an earlier run's bootstrap file would make .mkdirs a silent no-op
    (io/delete-file path true)
    (assert (.mkdirs path) (str "could not create " path))))

(defn- unblock-launch! [build-id]
  (io/delete-file (bootstrap-path build-id) true))

(defn- with-watched-build
  "Watch CONFIG, compiled once, call F with it, and stop the worker, which
  also stops its JS process. CLI-OPTS go to api/watch. Without bun, F does
  not run and the test passes vacuously."
  ([config f] (with-watched-build config {} f))
  ([{:keys [build-id] :as config} cli-opts f]
   (when-bun
     (fn []
       (try
         ;; a worker left by an aborted run would ignore CLI-OPTS; failing
         ;; here still stops it, so the next run starts clean
         (assert (= :watching (api/watch config (merge {:autobuild false :sync true} cli-opts)))
                 (str build-id " was already being watched"))
         (f config)
         (finally
           (api/stop-worker build-id)))))))

(def ^:private no-runtime-message
  "No available JS runtime.\nSee https://shadow-cljs.github.io/docs/UsersGuide.html#repl-troubleshooting")

(def ^:private bun-check "(println (some? js/process.versions.bun))")

(deftest the-runtime-starts-when-the-build-completes
  (with-watched-build (script-config :managed-probe-starts)
    (fn [{:keys [build-id]}]
      (is (some? (await-runtime build-id 10000))
          "connected without any eval asking for it"))))

(deftest an-editor-session-evaluates-in-the-managed-runtime
  (with-watched-build (script-config :managed-probe-editor)
    (fn [{:keys [build-id]}]
      (is (= {:out "true\n"} (eval-after-select build-id bun-check))))))

(deftest cljs-eval-evaluates-in-the-managed-runtime
  ;; api/cljs-eval drives the same REPL loop without nREPL or stdin.
  (with-watched-build (script-config :managed-probe-cljs-eval)
    (fn [{:keys [build-id]}]
      (is (= ["true"]
             (:results (api/cljs-eval build-id "(some? js/process.versions.bun)" {})))))))

(deftest the-console-repl-evaluates-in-the-managed-runtime
  ;; api/repl outside nREPL takes over stdin (repl-impl/stdin-takeover!), the
  ;; path of `shadow-cljs cljs-repl`; it returns at the end of input. Run
  ;; from shadow's own nREPL, api/*nrepl-init* is bound and api/repl would
  ;; turn the running session into a CLJS REPL instead, so it is unbound.
  (with-watched-build (script-config :managed-probe-console)
    (fn [{:keys [build-id]}]
      ;; the form's output, then its result
      (is (= "true\nnil\n"
             (with-out-str
               (binding [api/*nrepl-init* nil
                         *in* (clojure.lang.LineNumberingPushbackReader.
                                (java.io.StringReader. (str bun-check "\n")))]
                 (api/repl build-id {:prompt false}))))))))

(deftest watching-a-running-build-by-its-config-says-so
  ;; with-watched-build relies on this to catch a worker left by an
  ;; aborted run
  (with-watched-build (script-config :managed-probe-rewatch)
    (fn [config]
      (is (= :already-watching (api/watch config {:autobuild false}))))))

(deftest a-js-runtime-given-on-the-command-line-is-managed
  ;; --js-runtime reaches the build through :config-merge, so the raw build
  ;; config never carries it.
  (with-watched-build (dissoc (script-config :managed-probe-cli) :js-runtime)
    {:config-merge [{:js-runtime :bun}]}
    (fn [{:keys [build-id]}]
      (is (= {:out "true\n"} (eval-after-select build-id bun-check))))))

(deftest an-eval-during-the-first-compile-waits-for-the-runtime
  ;; A tool may select the build and evaluate as soon as the watch's nREPL
  ;; port is written, while the first compile is still running. Every other
  ;; test here watches with :sync true, which hides this. Deleting the
  ;; build's cache keeps the compile slow enough to wait through. The wait
  ;; is called directly with a long window, because a cold compile on a
  ;; loaded machine can outlast the 5 s an eval gets, which the fake-worker
  ;; test below covers.
  (let [build-id :managed-probe-first-compile]
    (doseq [f (reverse (file-seq (io/file (get-in (runtime/get-instance!) [:config :cache-root])
                                          "builds" (name build-id))))]
      (io/delete-file f true))
    (with-watched-build (script-config build-id) {:sync false}
      (fn [_]
        (is (nil? (:last-build-provides (worker-state build-id)))
            "the wait below starts before the first compile ends")
        (is (nil? (worker/await-managed-runtime (api/get-worker build-id) 60000)))
        (is (seq (api/repl-runtimes build-id))
            "it returned only once the process had connected")
        (is (= {:out "true\n"} (eval-after-select build-id bun-check)))))))

(defn- fake-worker
  "A worker whose state is STATE, for await-managed-runtime, which reads
  only the state and never the worker's loop."
  [state]
  {::impl/proc true
   :build-id :managed-probe-fake
   :state-ref (volatile! (merge {:build-config (script-config :managed-probe-fake)} state))})

(deftest a-first-compile-that-outlasts-the-wait-is-named
  (let [[message ms] (timed #(worker/await-managed-runtime (fake-worker {}) 300))]
    (is (= "build :managed-probe-fake has not finished a successful compile yet\n" message))
    (is (<= 300 ms 2000) "it waited out the window, and no longer")))

(deftest a-failed-first-compile-is-not-waited-for
  ;; The compile's own failure report says why; the REPL's generic message
  ;; follows it.
  (let [[message ms] (timed #(worker/await-managed-runtime (fake-worker {:failure-data nil}) 5000))]
    (is (nil? message))
    (is (< ms 1000))))

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
          (is (nil? (managed-state build-id)) "no JS process was started"))))))

(deftest a-runtime-that-exits-before-connecting-is-reported-at-once
  (with-watched-build (script-config :managed-probe-exits
                        :main 'shadow.cljs.managed-runtime-exit-probe/main)
    (fn [{:keys [build-id]}]
      (dotimes [i 2]
        (testing (str "eval " (inc i))
          (let [[{:keys [out err]} ms] (timed #(eval-after-select build-id "(println :unreachable)"))]
            (is (nil? out))
            (is (str/includes? (str err) "bun runtime for :managed-probe-exits exited with code 3"))
            (is (= 1 (occurrences (str err) "managed-runtime-exit-probe exits while loading"))
                "the process's own output names the cause, from this run only")
            (is (str/ends-with? (str err) "\n"))
            (is (< ms (if (zero? i) 7000 2000))
                "a process already known to have exited is reported without a wait")))))))

(defn- level-and-cause
  "[level exception-class] of each log record, so a mismatch shows what was
  logged."
  [records]
  (mapv (fn [^LogRecord record] [(.getLevel record) (class (.getThrown record))]) records))

(def ^:private blocked-launch-logged [[Level/WARNING java.io.FileNotFoundException]])

(deftest a-runtime-that-cannot-be-launched-is-reported
  (block-launch! :managed-probe-no-launch)
  (try
    (capturing-launch-failures :managed-probe-no-launch
      (fn [records]
        (with-watched-build (script-config :managed-probe-no-launch)
          (fn [{:keys [build-id]}]
            (let [[{:keys [out err]} ms] (timed #(eval-after-select build-id "(println :unreachable)"))]
              (is (nil? out))
              (is (str/includes? (str err) "bun runtime for :managed-probe-no-launch could not be launched: java.io.FileNotFoundException"))
              (is (< ms 2000) "a failed launch never blocks the eval"))
            (is (= blocked-launch-logged (level-and-cause @records))
                "the failed launch is logged once, as a warning")))))
    (finally
      (unblock-launch! :managed-probe-no-launch))))

(deftest a-failed-launch-is-retried-by-the-next-build
  ;; The failed launch leaves no process; neither the next compile nor
  ;; stopping the worker may trip over that.
  (block-launch! :managed-probe-relaunch)
  (try
    (capturing-launch-failures :managed-probe-relaunch
      (fn [records]
        (with-watched-build (script-config :managed-probe-relaunch)
          (fn [{:keys [build-id]}]
            (let [{:keys [err]} (eval-after-select build-id "(println :unreachable)")]
              (is (str/includes? (str err) "could not be launched")))
            (unblock-launch! build-id)
            (api/watch-compile! build-id)
            (is (not (contains? (worker-state build-id) :failure-data))
                "the next compile is not reported as failed")
            (is (some? (await-runtime build-id 10000)) "and it starts the runtime")
            (is (= blocked-launch-logged (level-and-cause @records))
                "only the blocked launch is logged as failed")))))
    (finally
      (unblock-launch! :managed-probe-relaunch))))

(deftest node-repl-gets-no-second-process
  ;; node-repl pipes in its own process; a managed one beside it would take
  ;; the REPL and its output. Options as the CLI's --js-runtime builds them.
  (when-bun
    #(let [build-id :managed-probe-node-repl
          worker (repl-impl/node-repl* (runtime/get-instance!)
                   {:build-id build-id :js-runtime :bun :config-merge [{:js-runtime :bun}]})]
      (try
        ;; node-repl* has compiled by the time it returns
        (is (some? (-> worker :state-ref deref :last-build-provides)))
        (is (false? (impl/managed-build? (-> worker :state-ref deref))))
        (is (nil? (managed-state build-id)) "no managed process")
        (is (some? (await-runtime build-id 10000)) "its own process connects")
        (is (= ["true"] (:results (api/cljs-eval build-id "(some? js/process.versions.bun)" {})))
            "and evaluates under bun")
        (finally
          (api/stop-worker build-id))))))

(deftest a-process-that-never-connects-costs-one-wait
  ;; The wait is anchored to the process's start, so a region of forms
  ;; against a process that cannot connect does not pay it once per form.
  (with-watched-build (script-config :managed-probe-busy
                        :main 'shadow.cljs.managed-runtime-busy-probe/main)
    (fn [{:keys [build-id]}]
      (let [[{first-err :err} first-ms] (timed #(eval-after-select build-id "(println :unreachable)"))
            [{second-err :err} second-ms] (timed #(eval-after-select build-id "(println :unreachable)"))]
        (is (str/includes? (str first-err) "bun runtime for :managed-probe-busy is running but not connected"))
        (is (str/includes? (str second-err) "is running but not connected"))
        (is (< first-ms 7000))
        (is (< second-ms 2000) "the second eval does not wait again")))))

(deftest an-exited-process-is-started-again-by-the-next-compile
  (with-watched-build (script-config :managed-probe-again)
    (fn [{:keys [build-id]}]
      (let [first-id (await-runtime build-id 10000)
            runs-before (occurrences (log-text build-id) "managed-runtime-probe started")]
        (is (some? first-id))
        (api/cljs-eval build-id "(js/setTimeout #(js/process.exit 0) 100)" {})
        (is (poll 10000 #(not (impl/managed-runtime-running? (worker-state build-id)))))
        (is (await-no-runtime build-id))
        (let [{:keys [err]} (eval-after-select build-id "(println :unreachable)")]
          (is (str/includes? (str err) "exited with code 0") "nothing restarts it by itself"))
        (api/watch-compile! build-id)
        (is (some? (await-runtime build-id 10000 :except first-id)))
        (is (= {:out "true\n"} (eval-after-select build-id bun-check)))
        (is (= (inc runs-before) (occurrences (log-text build-id) "managed-runtime-probe started"))
            "the new run is appended; the earlier run's output is still there")))))

(deftest a-build-that-stops-being-managed-loses-its-process
  (with-watched-build (script-config :managed-probe-unmanaged)
    {:autobuild true}
    (fn [{:keys [build-id] :as config}]
      (is (some? (await-runtime build-id 10000)))
      (let [old (:process (managed-state build-id))]
        (send-config! build-id (dissoc config :js-runtime))
        (is (poll 15000 #(nil? (managed-state build-id))) "its entry is gone")
        (is (poll 5000 #(not (.isAlive ^Process old))) "its process was stopped")
        (is (await-no-runtime build-id))
        (is (= {:err no-runtime-message} (eval-after-select build-id "(println :unreachable)")))))))

(deftest without-autobuild-a-config-change-keeps-the-process
  ;; Without autobuild a config edit is only recorded and the next manual
  ;; compile still builds from the old config, so the process keeps serving.
  (with-watched-build (script-config :managed-probe-config-kept)
    (fn [{:keys [build-id] :as config}]
      (is (some? (await-runtime build-id 10000)))
      (let [old (:process (managed-state build-id))]
        (send-config! build-id (assoc config :output-to "target/managed-probe-config-kept/moved.js"))
        (api/watch-compile! build-id)
        (is (identical? old (:process (managed-state build-id))))
        (is (= {:out "true\n"} (eval-after-select build-id bun-check)))))))

(deftest a-failed-compile-leaves-the-process-alone
  ;; build-failure stores (ex-data e), nil for a plain exception; the
  ;; failure still has to count as one.
  (let [failures (atom [])]
    (with-watched-build (script-config :managed-probe-failed-compile)
      {:autobuild true
       :log-chan (collect-expected-failures #(str/includes? % "not-a-dir") failures)}
      (fn [{:keys [build-id] :as config}]
        (is (some? (await-runtime build-id 10000)))
        (let [old (:process (managed-state build-id))]
          (io/make-parents "target/managed-probe-blocked/not-a-dir")
          (spit "target/managed-probe-blocked/not-a-dir" "")
          (send-config! build-id (assoc config :output-to "target/managed-probe-blocked/not-a-dir/script.js"))
          (is (poll 15000 #(contains? (worker-state build-id) :failure-data))
              "the autobuild compile failed")
          (is (poll 5000 #(seq @failures)) "the failure report names the blocked output")
          (is (identical? old (:process (managed-state build-id))))
          (is (.isAlive ^Process old)))))))

(defn- worker-in-relay?
  "True while BUILD-ID's worker is connected to shadow's relay."
  [build-id]
  (->> (-> (runtime/get-instance!) :relay :state-ref deref :clients vals)
       (some #(= build-id (get-in % [:client-info :shadow.cljs/worker-for])))
       (boolean)))

(deftest the-worker-leaves-the-relay-before-its-runtime-stops
  ;; The relay tells a build's runtimes when their worker leaves, offering
  ;; the notice to each runtime's websocket. A runtime stopped first may
  ;; have that websocket closed, and the refused offer logs
  ;; client-not-keeping-up. Checked where the order is decided: the real
  ;; stop-managed-runtime, wrapped to note whether the worker is still in
  ;; the relay when its shutdown calls it. Watching for the departure from
  ;; a relay client instead races that client's go block against the
  ;; process's exit.
  (with-watched-build (script-config :managed-probe-worker-stop)
    (fn [{:keys [build-id]}]
      (is (some? (await-runtime build-id 10000)))
      (let [stop-managed-runtime impl/stop-managed-runtime
            worker-present (promise)]
        ;; worker/stop waits for the worker's thread, so the shutdown runs
        ;; inside the redefinition
        (with-redefs [impl/stop-managed-runtime
                      (fn [state]
                        (deliver worker-present (worker-in-relay? build-id))
                        (stop-managed-runtime state))]
          (api/stop-worker build-id))
        (is (false? (deref worker-present 0 ::not-stopped)))))))

(deftest the-log-is-rotated-past-its-limit
  (let [build-id :managed-probe-rotate
        log (log-file build-id)
        set-aside (io/file (str (.getPath log) ".1"))]
    (io/delete-file set-aside true)
    (io/make-parents log)
    (spit log (apply str (repeat (* 1100 1024) "x")))
    (with-watched-build (script-config build-id)
      (fn [_]
        (is (some? (await-runtime build-id 10000)))
        (is (.exists set-aside) "the full log was set aside")
        (is (str/starts-with? (slurp log) "--- bun runtime started ---"))))))
