(ns shadow.cljs.devtools.server.util-test
  (:require
    [clojure.core.async :as async]
    [clojure.string :as str]
    [clojure.test :refer (deftest is)]
    [shadow.build.log :as build-log]
    [shadow.cljs.devtools.server.util :as util]))

(defn- poll
  "Calls F every 10ms until it returns truthy or TIMEOUT-MS pass."
  [timeout-ms f]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (or (f)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 10)
            (recur))))))

(deftest the-watch-printer-keeps-messages-that-arrive-while-it-prints
  ;; Another thread printing holds the stdout lock, so the printer can take
  ;; one message and then wait. Messages arriving meanwhile must queue, not
  ;; replace each other: one of them may be a build failure.
  (let [err (java.io.StringWriter.)
        out (binding [*err* err] (util/stdout-dump false))]
    (try
      (locking build-log/stdout-lock
        (doseq [build-id [:a :b :c]]
          (async/>!! out {:type :build-configure :build-id build-id})))
      (poll 5000 #(str/includes? (str err) "[:c]"))
      (is (= ["[:a] Configuring build."
              "[:b] Configuring build."
              "[:c] Configuring build."]
             (str/split-lines (str err))))
      (finally
        (async/close! out)))))

(deftest messages-the-printer-ignores-take-no-slots
  ;; a cold compile offers thousands of :build-log events, which a printer
  ;; that is not verbose throws away; queued, they would push out a build
  ;; failure waiting ahead of them
  (let [err (java.io.StringWriter.)
        out (binding [*err* err] (util/stdout-dump false))]
    (try
      (locking build-log/stdout-lock
        (async/>!! out {:type :build-configure :build-id :a})
        (async/>!! out {:type :build-configure :build-id :b})
        (dotimes [_ 2000]
          (async/>!! out {:type :build-log :event {:type :compile-cljs}}))
        (async/>!! out {:type :build-configure :build-id :c}))
      (poll 5000 #(str/includes? (str err) "[:c]"))
      (is (= ["[:a] Configuring build."
              "[:b] Configuring build."
              "[:c] Configuring build."]
             (str/split-lines (str err))))
      (finally
        (async/close! out)))))

(deftest a-printer-whose-output-disconnected-leaves-the-worker
  ;; the worker's output mult drops a tap whose channel is closed; a printer
  ;; that only stopped reading would keep a full buffer of build results
  ;; for as long as the worker runs
  (let [disconnected (proxy [java.io.Writer] []
                       (write
                         ([_] (throw (java.net.SocketException. "Broken pipe")))
                         ([_ _ _] (throw (java.net.SocketException. "Broken pipe"))))
                       (flush [])
                       (close []))
        out (binding [*err* disconnected] (util/stdout-dump false))]
    (async/>!! out {:type :build-configure :build-id :a})
    (is (poll 5000 #(false? (async/>!! out {:type :build-configure :build-id :b})))
        "the printer closes its channel")))
