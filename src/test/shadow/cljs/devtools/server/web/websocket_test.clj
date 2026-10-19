(ns shadow.cljs.devtools.server.web.websocket-test
  "The write loop and the connection's end are driven directly, against
  connections that record what is sent and fail the way shadow-http's do
  when the peer goes away mid-send."
  (:require
    [clojure.core.async :as async]
    [clojure.test :refer (deftest is)]
    [shadow.cljs.devtools.server.web.api :as web-api]
    [shadow.cljs.devtools.server.web.websocket :as ws]
    [shadow.http.server :as http-server]
    [shadow.jvm-log :as log])
  (:import
    [java.util.logging Filter Level LogRecord]
    [shadow.cljs.devtools.server.web.websocket GuardedWebSocketHandler]
    [shadow.http.server WebSocketConnection WebSocketHandler]))

(defn- connection
  "A WebSocketConnection that records each text send and close in the atom
  EVENTS. SEND-TEXT and SEND-CLOSE, when given, run after the record and
  may throw; ON-CLOSE is called with the close status."
  [events & {:keys [send-text send-close on-close]}]
  (reify WebSocketConnection
    (isOpen [_] true)
    (sendText [_ text]
      (swap! events conj [:text text])
      (when send-text (send-text text)))
    (sendClose [_ status]
      (swap! events conj [:close status])
      (when on-close (on-close status))
      (when send-close (send-close status)))))

(defn- capturing-send-failures
  "Calls F, keeping the send-failed warnings logged meanwhile out of the
  console, and returns [F's result, the records]. A copy of the
  managed-runtime tests' launch-failure capture rather than a shared one:
  those live in src/repl, which lein test does not load."
  [f]
  (let [records (atom [])
        message (log/log-msg ::ws/send-failed nil)
        ^Filter previous (.getFilter log/logger)]
    (.setFilter log/logger
      (reify Filter
        (isLoggable [_ record]
          (if (= message (.getMessage ^LogRecord record))
            (do (swap! records conj record) false)
            (or (nil? previous) (.isLoggable previous record))))))
    (try
      [(f) @records]
      (finally
        (.setFilter log/logger previous)))))

(defn- closed?
  "True when CH is closed and drained, false when a take would block."
  [ch]
  (async/alt!!
    ch ([v] (nil? v))
    (async/timeout 100) false))

(deftest messages-are-sent-until-ws-out-closes
  (let [events (atom [])
        ws-out (async/chan 10)]
    (async/>!! ws-out "a")
    (async/>!! ws-out "b")
    (async/close! ws-out)
    (is (= ::ws/ws-out-closed (ws/write-loop (connection events) ws-out (promise))))
    (is (= [[:text "a"] [:text "b"]] @events))))

(deftest a-send-failing-as-the-peer-leaves-ends-the-loop
  (let [events (atom [])
        ws-out (async/chan 10)
        closed (promise)]
    (async/>!! ws-out "a")
    (async/>!! ws-out "b")
    ;; the reader closes the compressor before onClose, so a send can fail
    ;; with this before the close is announced
    (let [result (future
                   (ws/write-loop
                     (connection events :send-text (fn [_] (throw (NullPointerException. "Deflater has been closed"))))
                     ws-out
                     closed))]
      (deliver closed true)
      (is (= ::ws/peer-gone (deref result 5000 ::still-running)))
      (is (= [[:text "a"]] @events) "nothing is sent after the failure"))))

(deftest a-send-failing-on-a-live-connection-is-a-fault
  (let [ws-out (async/chan 10)
        ex (java.io.IOException. "write failed")]
    (async/>!! ws-out "a")
    (is (identical? ex
          (::ws/fault
            (ws/write-loop (connection (atom []) :send-text (fn [_] (throw ex))) ws-out (promise)))))))

(deftest a-message-that-is-neither-text-nor-bytes-is-a-fault
  (let [events (atom [])
        ws-out (async/chan 10)]
    (async/>!! ws-out :not-a-frame)
    (is (instance? clojure.lang.ExceptionInfo
          (::ws/fault (ws/write-loop (connection events) ws-out (promise)))))
    (is (= [] @events))))

(deftest a-closed-ws-out-closes-the-websocket
  (let [events (atom [])]
    (ws/end-connection! (connection events) (async/chan) (promise) ::ws/ws-out-closed)
    (is (= [[:close 1000]] @events))))

(deftest a-departed-peer-is-sent-no-close
  ;; onClose closes ws-out too, so a loop ending that way meets a realized
  ;; CLOSED and has no one left to tell
  (doseq [outcome [::ws/ws-out-closed ::ws/peer-gone]]
    (let [events (atom [])]
      (ws/end-connection! (connection events) (async/chan) (doto (promise) (deliver true)) outcome)
      (is (= [] @events) (str outcome)))))

(deftest a-send-fault-is-logged-and-drops-the-client
  (let [events (atom [])
        ws-in (async/chan)
        ex (java.io.IOException. "write failed")
        [_ records] (capturing-send-failures
                      #(ws/end-connection! (connection events) ws-in (promise) {::ws/fault ex}))]
    (is (= [[Level/WARNING ex]] (map (juxt #(.getLevel ^LogRecord %) #(.getThrown ^LogRecord %)) records)))
    (is (closed? ws-in) "the relay sees the client leave")
    (is (= [[:close 1011]] @events))))

(deftest a-close-that-cannot-be-sent-is-dropped
  ;; a connection whose writes fail usually fails the close frame as well
  (let [events (atom [])]
    (ws/end-connection!
      (connection events :send-close (fn [_] (throw (java.net.SocketException. "Socket closed"))))
      (async/chan)
      (promise)
      ::ws/ws-out-closed)
    (is (= [[:close 1000]] @events))))

(deftest the-started-handler-announces-its-close-to-the-write-loop
  ;; start runs the write loop on the handler's own CLOSED and returns the
  ;; handler shadow-http calls onClose on; that onClose must deliver the
  ;; same promise, or every send failing as the peer leaves waits out the
  ;; timeout and is taken for a fault
  (let [handler (::http-server/handler (ws/ws-upgrade (async/chan) (async/chan 10)))
        started (.start ^WebSocketHandler handler (connection (atom [])))]
    (.onClose ^WebSocketHandler started 1000 "")
    (is (realized? (.-closed ^GuardedWebSocketHandler handler)))))

(deftest a-started-handler-closes-the-websocket-when-ws-out-closes
  (let [ws-out (async/chan 10)
        close-status (promise)
        handler (::http-server/handler (ws/ws-upgrade (async/chan) ws-out))]
    (.start ^WebSocketHandler handler (connection (atom []) :on-close #(deliver close-status %)))
    (async/close! ws-out)
    (is (= 1000 (deref close-status 5000 ::not-closed)))))

(deftest an-untrusted-client-is-told-and-disconnected
  (let [events (atom [])
        close-status (promise)
        handler (::http-server/handler
                  (web-api/api-remote-relay
                    {:transit-str pr-str
                     :http {:server-token "the-token"}
                     :ring-request {:query-params {"server-token" "wrong"}}}))
        started (.start ^WebSocketHandler handler
                  (connection events :on-close #(deliver close-status %)))]
    (is (= 1000 (deref close-status 5000 ::not-closed)))
    (is (= [[:text (pr-str {:op :access-denied})] [:close 1000]] @events))
    ;; nothing reads its input, so a frame it sends must not block the reader
    (is (not= ::blocked (deref (future (.onText ^WebSocketHandler started "hello")) 1000 ::blocked)))))
