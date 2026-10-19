(ns shadow.cljs.devtools.server.web.websocket
  "A core.async WebSocketHandler for shadow-http whose write loop survives the
  peer going away mid-send.

  Intentionally duplicates shadow.http.server/CoreAsyncWebSocketHandler,
  which lives in the shadow-http jar: its write loop sends with no guard, so
  a message in flight when the peer dies throws on the closing connection,
  uncaught on its virtual thread. The managed runtime makes that routine,
  since the worker kills its own JS process while messages to it may still
  be queued. It also closes the websocket whenever the write loop ends,
  where upstream leaves a client the relay dropped connected but deaf.
  Only the write loop and its ending differ; keep the rest in step with
  upstream."
  (:require
    [clojure.core.async :as async]
    [shadow.http.server :as http-server]
    [shadow.jvm-log :as log])
  (:import
    [shadow.http.server WebSocketConnection WebSocketHandler]))

(def ^:private close-wait-ms
  "How long a failed send waits for the connection's close to be announced.
  shadow-http's reader closes the compressor, then calls onClose, then
  closes the socket, all within microseconds of the peer going away."
  1000)

(defn write-loop
  "Sends each message taken from WS-OUT on CONNECTION, and returns how it
  ended:

  - ::ws-out-closed when WS-OUT closes.
  - ::peer-gone when a send fails because the peer went away. shadow-http
    has not announced the close yet when such a send fails, so the failure
    waits for CLOSED, delivered by onClose.
  - {::fault ex} when a send fails on a connection that stays open, or a
    message is neither a string nor bytes.

  A value rather than a throw, so end-connection! decides what each ending
  means in one place."
  [^WebSocketConnection connection ws-out closed]
  (loop []
    (if-some [msg (async/<!! ws-out)]
      (let [ending
            (try
              (cond
                (string? msg)
                (do (.sendText connection ^String msg) nil)

                (bytes? msg)
                (do (.sendBinary connection ^bytes msg) nil)

                :else
                {::fault (ex-info "websocket message is neither text nor bytes"
                           {:message-class (class msg)})})
              (catch Exception e
                (if (= ::open (deref closed close-wait-ms ::open))
                  {::fault e}
                  ::peer-gone)))]
        (if (some? ending)
          ending
          (recur)))
      ::ws-out-closed)))

(defn- send-close
  "Sends a close frame with STATUS. A connection whose writes are failing
  usually fails this too, and the close is only a courtesy then, so a
  failure is dropped."
  [^WebSocketConnection connection status]
  (try
    (.sendClose connection status)
    (catch Exception _)))

(defn end-connection!
  "Ends CONNECTION after its write loop returned ENDING; every way the loop
  ends closes the websocket, so no client is left connected but deaf.

  - A peer that already left, announced by CLOSED, needs nothing.
  - WS-OUT closed by its owner, the relay dropping the client or an
    upgrade that only had something to say, gets a normal close.
  - A fault is logged; closing WS-IN tells the relay the client is gone,
    and the peer gets an internal-error close."
  [connection ws-in closed ending]
  (cond
    (or (realized? closed) (= ::peer-gone ending))
    nil

    (= ::ws-out-closed ending)
    (send-close connection 1000)

    :else
    (do (log/warn-ex (::fault ending) ::send-failed)
        (async/close! ws-in)
        (send-close connection 1011))))

(deftype GuardedWebSocketHandler [^WebSocketConnection context closed ws-in ws-out]
  WebSocketHandler
  (start [_ new-context]
    (http-server/vthread
      (end-connection! new-context ws-in closed (write-loop new-context ws-out closed)))
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
