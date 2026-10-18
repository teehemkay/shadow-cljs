(ns shadow.cljs.devtools.server.web.websocket-test
  "The write loop is driven directly, against a connection whose sends fail
  the way shadow-http's do when the peer goes away mid-send."
  (:require
    [clojure.core.async :as async]
    [clojure.test :refer (deftest is)]
    [shadow.cljs.devtools.server.web.websocket :as ws])
  (:import
    [shadow.http.server WebSocketConnection]))

(defn- failing-connection
  "A WebSocketConnection whose text sends throw EX, recording each attempt in
  the atom SENT."
  [sent ex]
  (reify WebSocketConnection
    (isOpen [_] true)
    (sendText [_ text]
      (swap! sent conj text)
      (throw ex))))

(deftest a-send-failing-as-the-connection-closes-ends-the-loop
  (let [sent (atom [])
        ws-out (async/chan 10)
        closed (promise)]
    (async/>!! ws-out "a")
    (async/>!! ws-out "b")
    ;; the reader closes the compressor before onClose, so a send can fail
    ;; with this before the close is announced
    (let [result (future
                   (ws/write-loop
                     (failing-connection sent (NullPointerException. "Deflater has been closed"))
                     ws-out
                     closed))]
      (deliver closed true)
      (is (nil? (deref result 5000 ::still-running)))
      (is (= ["a"] @sent) "nothing is sent after the failure"))))

(deftest a-send-failing-on-a-connection-that-stays-open-is-rethrown
  (let [ws-out (async/chan 10)
        ex (java.io.IOException. "write failed")]
    (async/>!! ws-out "a")
    (is (identical? ex
          (try
            (ws/write-loop (failing-connection (atom []) ex) ws-out (promise))
            nil
            (catch Exception e e))))))

(deftest messages-are-sent-until-ws-out-closes
  (let [sent (atom [])
        ws-out (async/chan 10)
        connection (reify WebSocketConnection
                     (isOpen [_] true)
                     (sendText [_ text] (swap! sent conj text)))]
    (async/>!! ws-out "a")
    (async/>!! ws-out "b")
    (async/close! ws-out)
    (ws/write-loop connection ws-out (promise))
    (is (= ["a" "b"] @sent))))
