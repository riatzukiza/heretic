(ns heretic.cljs.test-runner
  (:require [clojure.test :as test]
            [heretic.cljs.discovery-test]
            [heretic.cljs.process-test]))

(defn -main [& _]
  (let [result (test/run-tests 'heretic.cljs.discovery-test 'heretic.cljs.process-test)]
    (shutdown-agents)
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
