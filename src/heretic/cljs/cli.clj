(ns heretic.cljs.cli
  "Small EDN CLI for the exploratory CLJS backend."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [heretic.cljs.compatibility :as compatibility]
            [heretic.cljs.discovery :as discovery]
            [heretic.cljs.evidence :as evidence]
            [heretic.cljs.process :as process]))

(defn- read-one [path]
  (with-open [input (java.io.PushbackReader. (io/reader path))]
    (let [eof (Object.)
          value (edn/read {:eof eof} input)]
      (when (or (identical? eof value)
                (not (identical? eof (edn/read {:eof eof} input))))
        (throw (ex-info "Expected exactly one EDN form" {:path path})))
      value)))

(defn -main [& args]
  (try
    (let [[operation path & extra] args]
      (when (or (nil? path) (seq extra))
        (throw (ex-info "Usage: discover RELATIVE-SOURCE | run WORKER-OPTIONS.edn | compat ROOT | evidence INPUT.edn" {})))
      (if (= operation "evidence")
        (println (evidence/emit (read-one path)))
        (let [result (case operation
                       "discover" (discovery/discover-source {:path path :source (slurp path)})
                       "run" (process/run-worker! (read-one path))
                       "compat" (compatibility/scan path)
                       (throw (ex-info "Unknown operation" {:operation operation})))]
          (prn result)
          (when (or (= :parse-error (:status result))
                    (contains? #{:load-error :test-error :timeout :invalid-test-output}
                               (get-in result [:result :status])))
            (System/exit 1)))))
    (shutdown-agents)
    (catch Exception e
      (binding [*out* *err*]
        (prn {:status :cli-error :message (.getMessage e) :data (ex-data e)}))
      (shutdown-agents)
      (System/exit 2))))
