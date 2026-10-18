(ns shadow.cljs.managed-runtime-exit-probe
  "The :main of a managed-runtime-test build whose process exits while
  loading, before its REPL client can connect.")

(js/console.error "managed-runtime-exit-probe exits while loading")
(js/process.exit 3)

(defn main [& _])
