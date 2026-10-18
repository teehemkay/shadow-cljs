(ns shadow.cljs.devtools.server.js-runtime
  (:require
    [clojure.java.io :as io]))

(defn bootstrap-file
  [cache-root build-id]
  (io/file
    cache-root
    (str "shadow-managed-runtime-"
         (name build-id)
         ".cjs")))

(defn log-file
  "Where a managed runtime's stdout and stderr go. Written to a file rather
  than a pipe the worker would have to drain, so a chatty runtime never
  blocks, and the REPL quotes its end when the process fails."
  [cache-root build-id]
  (io/file
    cache-root
    (str "shadow-managed-runtime-"
         (name build-id)
         ".log")))

(defn bootstrap-source
  [{:keys [output-to]}]
  (let [abs-output (.getAbsolutePath (io/file output-to))]
    (str "require(" (pr-str abs-output) ");\n"
         "setInterval(function () {}, 2147483647);\n")))
