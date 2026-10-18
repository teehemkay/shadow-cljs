(ns shadow.cljs.managed-runtime-sigterm-probe
  "The :main of a managed-runtime-test build whose process ignores SIGTERM,
  so only a forced kill replaces it.")

(defn main [& _]
  (js/process.on "SIGTERM" (fn [] nil))
  (js/console.log "managed-runtime-sigterm-probe started"))
