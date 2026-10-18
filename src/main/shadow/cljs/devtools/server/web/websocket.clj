(ns shadow.cljs.devtools.server.web.websocket
  "A core.async WebSocketHandler for shadow-http whose write loop survives the
  peer going away mid-send.

  Intentionally duplicates shadow.http.server/CoreAsyncWebSocketHandler,
  which lives in the shadow-http jar: its write loop sends with no guard, so
  a message in flight when the peer dies throws on the closing connection,
  uncaught on its virtual thread. The managed runtime makes that routine,
  since the worker kills its own JS process while messages to it may still
  be queued. Only the write loop differs; keep the rest in step with
  upstream."
  (:require
    [clojure.core.async :as async]
    [shadow.http.server :as http-server])
  (:import
    [shadow.http.server WebSocketConnection WebSocketHandler]))

(def ^:private close-wait-ms
  "How long a failed send waits for the connection's close to be announced.
  shadow-http's reader closes the compressor, then calls onClose, then
  closes the socket, all within microseconds of the peer going away."
  1000)

(defn write-loop
  "Sends each message taken from WS-OUT on CONNECTION until WS-OUT closes, a
  message is neither a string nor bytes, or a send fails. A send that fails
  because the peer went away is expected: shadow-http has not announced the
  close yet when it fails, so the failure waits for CLOSED, delivered by
  onClose, to tell that apart from a fault on a live connection, which is
  rethrown."
  [^WebSocketConnection connection ws-out closed]
  (loop []
    (when-some [msg (async/<!! ws-out)]
      (when (try
              (cond
                (string? msg)
                (do (.sendText connection ^String msg) true)

                (bytes? msg)
                (do (.sendBinary connection ^bytes msg) true)

                :else
                false)
              (catch Exception e
                (when (= ::open (deref closed close-wait-ms ::open))
                  (throw e))
                false))
        (recur)))))

(deftype GuardedWebSocketHandler [^WebSocketConnection context closed ws-in ws-out]
  WebSocketHandler
  (start [_ new-context]
    (http-server/vthread
      (write-loop new-context ws-out closed))
    (GuardedWebSocketHandler. new-context closed ws-in ws-out))

  (onText [_ text]
    (async/>!! ws-in text))

  (onBinary [_ bin]
    (async/>!! ws-in bin))

  (onPing [_ payload]
    (.sendPong context payload))

  (onClose [_ _status _reason]
    (deliver closed true)
    (async/close! ws-in)
    (async/close! ws-out)))

(defn ws-upgrade
  "shadow.http.server/ws-upgrade with the guarded write loop. WS-LOOP is
  accepted for parity and ignored, as upstream's handler ignores it."
  ([ws-in ws-out]
   (ws-upgrade ws-in ws-out nil))
  ([ws-in ws-out _ws-loop]
   {::http-server/handler (GuardedWebSocketHandler. nil (promise) ws-in ws-out)}))
