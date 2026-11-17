(ns heretic.cljs.runner
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [edamame.core :as edamame]
            [cljs.test :as test]
            [heretic.cljs.nbb-prelude :as prelude]
            [heretic.cljs.outcome :as outcome]
            [heretic.extern.nbb :as nbb]))

(defonce completion-handler (atom nil))

(defn- install-completion-report!
  "Own :end-run-tests. A requested namespace can defmethod the same event
   while it loads; reinstalling immediately before the requested run keeps
   that method from dropping the worker's completion callback."
  []
  (defmethod test/report [::test/default :end-run-tests] [event]
    (when-let [f @completion-handler] (f event))))

(install-completion-report!)

(defn run! [request]
  (if-not (outcome/valid-request? request)
    (nbb/emit (outcome/result request {:kind :invalid :message "Invalid request"}))
    (let [finished? (atom false)
          completions (atom [])
          timer (atom nil)
          reports (atom [])
          finish! (fn [event]
                    (when (compare-and-set! finished? false true)
                      (when @timer (nbb/cancel @timer))
                      (reset! completion-handler nil)
                      ;; Let asynchronous library logs settle so the result
                      ;; marker stays the last line the parent reads.
                      (nbb/later (fn []
                                   (nbb/emit (assoc (outcome/result request event)
                                                    :namespace-reports @reports)))
                                 40)))
          namespaces (:test-namespaces request)
          phase (atom :loading)]
      (nbb/on-async-error #(finish! {:kind (if (= @phase :loading) :load-error :test-error)
                                     :message (or (.-message %) (str %))}))
      (reset! completion-handler
              (fn [event]
                ;; Top-level run-tests during require is not the requested run.
                (when (= :testing @phase)
                  (swap! completions conj event)
                  ;; Defer until the next turn to observe duplicate synchronous completions.
                  (nbb/later #(finish! {:kind :completed :counters (first @completions)
                                        :completion-count (count @completions)}) 0))))
      (reset! timer (nbb/later #(finish! {:kind :timeout}) (:timeout-ms request)))
      (-> (prelude/install!)
          (.then (fn []
                   (reduce (fn [p ns-sym]
                    (.then p (fn [_]
                               (-> (nbb/load-code (str "(require '" (pr-str [ns-sym]) ")"))
                                   (.then (fn []
                                            (swap! reports conj {:namespace ns-sym :loaded? true})
                                            nil))
                                   (.catch (fn [err]
                                             (swap! reports conj {:namespace ns-sym
                                                                  :loaded? false
                                                                  :message (or (.-message err) (str err))})
                                             nil))))))
                  (js/Promise.resolve nil)
                  namespaces)))
          (.then (fn []
                   (when-not @finished?
                     (let [loaded (mapv :namespace (filter :loaded? @reports))]
                       (if (empty? loaded)
                         (finish! {:kind :load-error
                                   :message (or (:message (first @reports))
                                                "Unable to load namespaces")})
                         (do (install-completion-report!)
                             (reset! phase :testing)
                             (-> (nbb/load-code (pr-str (list* 'cljs.test/run-tests
                                                               (map #(list 'quote %) loaded))))
                                 (.catch #(finish! {:kind :test-error :message (.-message %)})))))))))
          (.catch #(finish! {:kind :load-error :message (.-message %)}))))))

(defn- read-request [text]
  (let [input (str/triml text)
        [_ source] (edamame/parse-next+string (edamame/source-reader input) {})]
    (when-not (and (seq source) (str/starts-with? input source)
                   (str/blank? (subs input (count source))))
      (throw (js/Error. "Expected exactly one EDN request")))
    (reader/read-string source)))

(defn main! [args]
  (nbb/emit-runtime)
  (try
    (if (= 1 (count args))
      (run! (read-request (nbb/read-file (first args))))
      (nbb/emit (outcome/result nil {:kind :invalid :message "Expected exactly one request path"})))
    (catch :default e
      (nbb/emit (outcome/result nil {:kind :invalid :message (.-message e)})))))
