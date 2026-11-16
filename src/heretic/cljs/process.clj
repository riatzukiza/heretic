(ns heretic.cljs.process
  (:require [heretic.cljs.outcome :as outcome]
            [heretic.cljs.protocol :as protocol]
            [heretic.extern.process :as extern]))

(defn run-worker!
  "Run a fresh worker with a hard deadline and validate its terminal record."
  [{:keys [command directory timeout-ms request log-dir]}]
  (if-not (and (outcome/valid-request? request)
               (vector? command) (seq command) (every? string? command)
               (integer? timeout-ms) (pos? timeout-ms) (<= timeout-ms Long/MAX_VALUE)
               (string? log-dir) (not-empty log-dir)
               (or (nil? directory) (string? directory)))
    {:execution {:spawned? false :command command :directory directory :containment :unknown
                 :runtime (protocol/runtime-observation nil)}
     :result (protocol/invalid request "Invalid parent options or request")}
    (try
      (let [paths (extern/prepare! log-dir request)
            actual-command (conj command (:request-path paths))
            base (merge paths {:command actual-command :directory directory :containment :unknown})]
        (try
          (let [execution (merge base (extern/execute! actual-command directory timeout-ms paths))
                stdout (:stdout execution)
                stderr (:stderr execution)
                readable (if (or (= extern/output-overflow stdout)
                                 (= extern/output-overflow stderr))
                           extern/output-overflow
                           stdout)
                execution (assoc execution :runtime (protocol/runtime-observation readable))]
            {:execution execution
             :result (protocol/validate-output request readable
                                               (:exit-code execution) (:timed-out? execution))})
          (catch Exception e
            {:execution (let [execution (merge base {:spawned? false} (:execution (ex-data e))
                                               {:message (.getMessage e)})]
                          (assoc execution :runtime (protocol/runtime-observation (:stdout execution))))
             :result (protocol/invalid request "Unable to execute worker")})))
      (catch Exception e
        {:execution {:spawned? false :command command :directory directory
                     :containment :unknown :message (.getMessage e)
                     :runtime (protocol/runtime-observation nil)}
         :result (protocol/invalid request "Unable to prepare worker")}))))
