(ns shadow.cljs.managed-runtime-probe
  "The :main of managed-runtime-test's :node-script builds. It only says it
  started, so a test can count runs in the runtime's log; the managed
  runtime's bootstrap keeps the process alive.")

(defn main [& _]
  (js/console.log "managed-runtime-probe started"))
