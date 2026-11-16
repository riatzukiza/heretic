(ns heretic.cljs.outcome)

(defn valid-request? [request]
  (let [{:keys [schema test-namespaces timeout-ms expected-tests]} request]
    (and (map? request) (= :heretic.cljs/request-v1 schema)
         (vector? test-namespaces) (seq test-namespaces)
         (every? #(and (symbol? %) (nil? (namespace %))
                       (re-matches #"[A-Za-z_][A-Za-z0-9_.-]*" (name %))) test-namespaces)
         (= (count test-namespaces) (count (distinct test-namespaces)))
         (integer? timeout-ms) (pos? timeout-ms) (<= timeout-ms 2147483647)
         (or (nil? expected-tests) (and (integer? expected-tests) (pos? expected-tests))))))

(defn result [request event]
  (let [{:keys [kind counters completion-count message]} event
        {:keys [test pass fail error]} counters
        valid-counters? (and (map? counters)
                             (every? #(and (integer? %) (<= 0 %)) [test pass fail error]))
        tests (if valid-counters? test 0)
        assertions (if valid-counters? (+ pass fail error) 0)
        completed? (= kind :completed)
        status (cond
                 (= kind :load-error) :load-error
                 (= kind :timeout) :timeout
                 (= kind :test-error) :test-error
                 (not= kind :completed) :invalid-test-output
                 (not valid-counters?) :invalid-test-output
                 (not= 1 completion-count) :invalid-test-output
                 (pos? error) :test-error
                 (or (zero? tests) (zero? assertions)
                     (and (:expected-tests request) (not= tests (:expected-tests request)))) :invalid-test-output
                 (pos? fail) :assertion-killed
                 :else :survived)]
    (cond-> {:schema :heretic.cljs/result-v1 :backend :nbb :status status
             :completed? completed? :tests tests :assertions assertions
             :failures (if valid-counters? fail 0) :errors (if valid-counters? error 0)
             :test-namespaces (if (vector? (:test-namespaces request)) (:test-namespaces request) [])
             :qualification-eligible? false}
      message (assoc :message (str message)))))
