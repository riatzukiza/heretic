(ns heretic.cljs.runner
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [edamame.core :as edamame]
            [cljs.test :as test]
            [heretic.cljs.outcome :as outcome]
            [heretic.extern.nbb :as nbb]))

(defonce completion-handler (atom nil))
(defmethod test/report [::test/default :end-run-tests] [event]
  (when-let [f @completion-handler] (f event)))

(defn run! [request]
  (if-not (outcome/valid-request? request)
    (nbb/emit (outcome/result request {:kind :invalid :message "Invalid request"}))
    (let [finished? (atom false)
          completions (atom [])
          timer (atom nil)
          finish! (fn [event]
                    (when (compare-and-set! finished? false true)
                      (when @timer (nbb/cancel @timer))
                      (reset! completion-handler nil)
                      (nbb/emit (outcome/result request event))))
          namespaces (:test-namespaces request)
          phase (atom :loading)]
      (nbb/on-async-error #(finish! {:kind (if (= @phase :loading) :load-error :test-error)
                                     :message (or (.-message %) (str %))}))
      (reset! completion-handler
              (fn [event]
                (swap! completions conj event)
                ;; Defer until the next turn to observe duplicate synchronous completions.
                (nbb/later #(finish! {:kind :completed :counters (first @completions)
                                      :completion-count (count @completions)}) 0)))
      (reset! timer (nbb/later #(finish! {:kind :timeout}) (:timeout-ms request)))
      (-> (reduce (fn [p ns-sym]
                    (.then p #(nbb/load-code (str "(require '" (pr-str [ns-sym]) ")"))))
                  (js/Promise.resolve nil) namespaces)
          (.then (fn []
                   (when-not @finished?
                     (reset! phase :testing)
                     (-> (nbb/load-code (pr-str (list* 'cljs.test/run-tests
                                                        (map #(list 'quote %) namespaces))))
                         (.catch #(finish! {:kind :test-error :message (.-message %)}))))))
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
