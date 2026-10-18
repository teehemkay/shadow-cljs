(ns shadow.cljs.managed-runtime-busy-probe
  "The :main of a managed-runtime-test build whose process stays alive but
  cannot connect: loading it blocks the event loop for 20 s, so the REPL
  client's websocket never opens in that time.")

(let [end (+ (js/Date.now) 20000)]
  (while (< (js/Date.now) end)))

(defn main [& _])
