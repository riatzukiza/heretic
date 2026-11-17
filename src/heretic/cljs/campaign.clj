(ns heretic.cljs.campaign
  "Run a bounded Axxium mutation sample under nbb and emit evidence.

   A baseline observation keeps the unmutated counters, including failures
   that already exist in Axxium. A mutant survives when its counters match
   that baseline. It is killed when the counters differ without a load,
   timeout, or test error. Nothing in this namespace sets
   :qualification-eligible? or :production-admitted? to true."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [heretic.cljs.compatibility :as compatibility]
            [heretic.cljs.discovery :as discovery]
            [heretic.cljs.evidence :as evidence]
            [heretic.cljs.nbb-runtime :as runtime]
            [heretic.cljs.nbb-sources :as sources]
            [heretic.cljs.process :as process]))

(defn verdict
  "Compare one worker observation to the unmutated baseline."
  [baseline observation]
  (cond
    (= :timeout (:status observation)) :timeout
    (#{:load-error :test-error :invalid-test-output} (:status observation)) :error
    (nil? baseline) :error
    (and (= (:tests observation) (:tests baseline))
         (= (:assertions observation) (:assertions baseline))
         (= (:failures observation) (:failures baseline))
         (= (:errors observation) (:errors baseline)))
    :survived
    :else :killed))

(defn- test-namespaces [source-root]
  (->> (file-seq (io/file source-root))
       (keep (fn [file]
               (when (and (.isFile file) (str/ends-with? (.getName file) "_test.cljs"))
                 (let [text (slurp file)]
                   (when-let [name (second (re-find #"\(ns\s+([^\s()]+)" text))]
                     (symbol name))))))
       sort
       vec))

(defn- run-namespace! [runtime ns-sym overlay log-dir timeout-ms]
  (let [request {:schema :heretic.cljs/request-v1
                 :test-namespaces [ns-sym]
                 :timeout-ms timeout-ms
                 :expected-tests nil}
        response (process/run-worker!
                  {:command (runtime/command runtime overlay)
                   :directory (:directory runtime)
                   :timeout-ms (+ timeout-ms 15000)
                   :log-dir log-dir
                   :env (runtime/env runtime)
                   :request request})
        result (:result response)
        ;; A namespace that aborts before cljs.test records an error still
        ;; differs from a clean run. Count that abort as one error.
        errors (cond-> (or (:errors result) 0)
                 (and (= :test-error (:status result))
                      (zero? (or (:errors result) 0)))
                 inc)]
    {:namespace ns-sym
     :status (:status result)
     :tests (or (:tests result) 0)
     :assertions (or (:assertions result) 0)
     :failures (or (:failures result) 0)
     :errors errors
     :message (or (:message result)
                  (:message (first (:namespace-reports result))))
     :runtime (get-in response [:execution :runtime])}))

(defn- run-suite!
  "Run each namespace in its own worker. One aborted namespace must not
   discard the counters of the others. The totals are what a mutant is
   compared to."
  [runtime namespaces overlay log-dir timeout-ms]
  (let [results (mapv #(run-namespace! runtime % overlay log-dir timeout-ms)
                      namespaces)
        totals (reduce (fn [acc result]
                         (-> acc
                             (update :tests + (:tests result))
                             (update :assertions + (:assertions result))
                             (update :failures + (:failures result))
                             (update :errors + (:errors result))))
                       {:tests 0 :assertions 0 :failures 0 :errors 0}
                       results)
        status (cond
                 (some #(= :timeout (:status %)) results) :timeout
                 (every? #(#{:load-error :invalid-test-output} (:status %)) results) :load-error
                 (or (pos? (:failures totals)) (pos? (:errors totals))) :assertion-killed
                 :else :survived)]
    (assoc totals
           :status status
           :completed? (not (#{:timeout :load-error} status))
           :namespace-reports (mapv #(select-keys % [:namespace :status :tests :assertions
                                                     :failures :errors :message])
                                    results)
           :runtime (:runtime (first (filter :runtime results))))))

(defn- candidates [source-root limit]
  (let [root (io/file source-root)]
    (->> (file-seq root)
         (filter #(and (.isFile %)
                       (re-find #"\.(?:cljs|cljc)$" (.getName %))
                       (not (str/includes? (.getName %) "_test."))
                       (not (str/includes? (.getName %) "_fixture."))))
         (sort-by #(.getPath %))
         (mapcat (fn [file]
                   (let [rel (str (.relativize (.toPath root) (.toPath file)))
                         result (discovery/discover-source
                                 {:path rel :source (slurp file)
                                  :operators [:swap-plus-minus :swap-true-false
                                              :replace-and-false :replace-or-true
                                              :replace-comparison-false]})]
                     (when (= :ok (:status result))
                       (:candidates result)))))
         (take limit)
         vec)))

(defn- overlay! [runtime source-root candidate]
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "heretic-mutant" (make-array java.nio.file.attribute.FileAttribute 0)))
        original (slurp (io/file source-root (:path candidate)))
        mutated (discovery/apply-candidate {:source original :candidate candidate})
        out (io/file dir (:path candidate))]
    (io/make-parents out)
    (spit out (sources/rewrite-source mutated))
    dir))

(defn- observation [role verdict-name result candidate]
  (merge (select-keys result [:status :tests :assertions :failures :errors :completed?
                              :namespace-reports :message])
         {:role role
          :verdict verdict-name
          :qualification-eligible? false
          :runtime (or (:runtime result)
                       {:availability :unavailable :provenance :worker-reported :reason :missing})}
         (when candidate
           {:path (:path candidate)
            :fingerprint (:fingerprint candidate)
            :operator (:operator candidate)})))

(defn run-sample!
  "Prepare nbb, run the unmutated suite, then a bounded candidate batch.
   Returns the evidence record and a small summary. Eligibility stays false."
  [{:keys [root limit timeout-ms log-dir] :or {limit 40 timeout-ms 120000}}]
  (let [source-root (io/file root "src/cljs")
        prepared (runtime/prepare! root)
        logs (or log-dir (.getAbsolutePath
                          (.toFile (java.nio.file.Files/createTempDirectory
                                    "heretic-campaign" (make-array java.nio.file.attribute.FileAttribute 0)))))
        namespaces (test-namespaces source-root)
        baseline-result (run-suite! prepared namespaces nil logs timeout-ms)
        baseline-verdict (if (#{:load-error :test-error :timeout :invalid-test-output}
                              (:status baseline-result))
                           :error
                           :baseline)
        chosen (candidates source-root limit)
        mutant-results
        (mapv (fn [candidate]
                (let [dir (overlay! prepared source-root candidate)
                      result (run-suite! prepared namespaces dir logs timeout-ms)]
                  {:candidate candidate
                   :result result
                   :verdict (verdict baseline-result result)}))
              chosen)
        files (->> chosen
                   (map :path)
                   distinct
                   (mapv (fn [path]
                           (let [text (slurp (io/file source-root path))
                                 discovered (discovery/discover-source {:path path :source text})]
                             {:path path
                              :source-sha256 (:source-sha256 discovered)
                              :status (:status discovered)}))))
        identity (compatibility/git-identity root)
        record (evidence/assemble
                {:commit-sha (:commit-sha identity)
                 :tree-sha (:tree-sha identity)
                 :files files
                 :candidates chosen
                 :observations (into [(observation :baseline baseline-verdict baseline-result nil)]
                                     (map (fn [{:keys [candidate result verdict]}]
                                            (observation :mutation verdict result candidate))
                                          mutant-results))})
        summary {:baseline (select-keys baseline-result
                                        [:status :tests :assertions :failures :errors])
                 :namespace-reports (:namespace-reports baseline-result)
                 :verdicts (frequencies (map :verdict mutant-results))
                 :candidates (count chosen)
                 :qualification-eligible? (:qualification-eligible? record)
                 :production-admitted? (:production-admitted? record)}]
    {:record record :summary summary}))

(defn -main [root & _]
  (let [{:keys [summary]} (run-sample! {:root root :limit 40 :timeout-ms 180000})]
    (binding [*out* *err*]
      (prn summary))
    (shutdown-agents)
    (System/exit 0)))
