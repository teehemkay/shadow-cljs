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
