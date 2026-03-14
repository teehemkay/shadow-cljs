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

(defn bootstrap-source
  [{:keys [output-to]}]
  (let [abs-output (.getAbsolutePath (io/file output-to))]
    (str "require(" (pr-str abs-output) ");\n"
         "setInterval(function () {}, 2147483647);\n")))
