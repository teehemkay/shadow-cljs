(ns shadow.cljs.managed-runtime-probe
  "The :main of managed-runtime-test's :node-script builds. It does nothing:
  the managed runtime's bootstrap keeps the process alive, and the test
  only needs a build that loads and connects.")

(defn main [& _])
