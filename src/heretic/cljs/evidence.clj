(ns heretic.cljs.evidence
  "Deterministic EDN evidence for one experimental mutation run.

   Objects: a source tree (commit and tree SHAs plus per-file digests),
   candidate-v2 discoveries, and observations (baseline or one mutation).
   The morphism assemble sorts those inputs and computes counters, runtime
   provenance, and eligibility. It does not run tests and it does not admit
   a build to production.

   Law. :production-admitted? is false for every record.
   Law. :qualification-eligible? is true only when :heretic.cljs/eligibility-v1
   holds, and false otherwise. The nbb worker never sets an observation's own
   flag, so a record built from worker results fails the rule."
  (:require [clojure.string :as str]))

(def eligibility-rule :heretic.cljs/eligibility-v1)

(defn- canonical-pr [x]
  (binding [*print-length* nil
            *print-level* nil
            *print-readably* true
            *print-dup* false
            *print-meta* false
            *print-namespace-maps* false]
    (pr-str x)))

(defn- ordered [x]
  (cond
    (map? x) (into (sorted-map) (map (fn [[k v]] [k (ordered v)]) x))
    (vector? x) (mapv ordered x)
    :else x))

(defn- hex? [s n]
  (and (string? s) (re-matches (re-pattern (str "[0-9a-f]{" n "}")) s)))

(defn- counter? [observation]
  (let [{:keys [tests assertions failures errors completed? status]} observation]
    (and (every? #(and (integer? %) (<= 0 %)) [tests assertions failures errors])
         (boolean? completed?)
         (case status
           :survived (and completed? (pos? tests) (pos? assertions)
                          (zero? failures) (zero? errors))
           :assertion-killed (and completed? (pos? tests) (pos? assertions)
                                 (pos? failures) (zero? errors)
                                 (<= failures assertions))
           false))))

(defn- runtime-observed? [runtime]
  (and (map? runtime)
       (= :observed (:availability runtime))
       (= :worker-reported (:provenance runtime))
       (string? (:node-version runtime))
       (string? (:nbb-version runtime))))

(defn- failures [input]
  (let [files (vec (:files input))
        candidates (vec (:candidates input))
        observations (vec (:observations input))
        baselines (filter #(= :baseline (:role %)) observations)
        mutations (filter #(= :mutation (:role %)) observations)
        fingerprints (map :fingerprint candidates)
        reasons (cond-> []
                  (not (and (map? input) (vector? (:files input))
                            (vector? (:candidates input))
                            (vector? (:observations input))))
                  (conj :malformed)
                  (not (hex? (:commit-sha input) 40)) (conj :commit-sha)
                  (not (hex? (:tree-sha input) 40)) (conj :tree-sha)
                  (or (empty? files)
                      (some #(not= :ok (:status %)) files)
                      (some #(not (hex? (:source-sha256 %) 64)) files))
                  (conj :sources)
                  (or (empty? fingerprints)
                      (not= (count fingerprints) (count (set fingerprints))))
                  (conj :candidates)
                  (not= 1 (count baselines)) (conj :baseline)
                  (some #(not (and (= :survived (:status %)) (counter? %))) baselines)
                  (conj :baseline-outcome)
                  (empty? mutations) (conj :mutations)
                  (some #(not (and (#{:survived :assertion-killed} (:status %))
                                   (counter? %)
                                   (contains? (set fingerprints) (:fingerprint %))))
                        mutations)
                  (conj :mutation-outcome)
                  (some #(not (runtime-observed? (:runtime %))) observations)
                  (conj :runtime)
                  (or (empty? observations)
                      (some #(not (true? (:qualification-eligible? %))) observations))
                  (conj :worker-ineligible))]
    (vec (sort (distinct reasons)))))

(defn- by-status [observations]
  (reduce (fn [counts observation]
            (update counts (:status observation) (fnil inc 0)))
          (sorted-map)
          observations))

(defn assemble
  "Return one evidence record. The same inputs always return the same record."
  [input]
  (let [files (->> (:files input)
                   (map #(select-keys % [:path :source-sha256 :status]))
                   (sort-by :path)
                   vec)
        candidates (->> (:candidates input)
                        (sort-by (fn [candidate]
                                   [(:path candidate)
                                    (:fingerprint candidate)
                                    (or (:offset candidate) -1)
                                    (:operator candidate)]))
                        vec)
        observations (->> (:observations input)
                          (sort-by (fn [observation]
                                     [(:role observation)
                                      (or (:path observation) "")
                                      (or (:fingerprint observation) "")
                                      (str (:namespace observation))]))
                          vec)
        failed (failures input)
        runtimes (->> observations
                      (map :runtime)
                      (filter runtime-observed?)
                      (map #(select-keys % [:node-version :nbb-version :provenance :availability]))
                      distinct
                      (sort-by (juxt :node-version :nbb-version))
                      vec)]
    (ordered
     {:schema :heretic.cljs/evidence-v1
      :qualification-eligible? (empty? failed)
      :production-admitted? false
      :eligibility {:rule eligibility-rule
                    :met? (empty? failed)
                    :failed failed}
      :source {:commit-sha (:commit-sha input)
               :tree-sha (:tree-sha input)
               :files files}
      :candidates candidates
      :observations observations
      :runtime {:provenance :worker-reported :observations runtimes}
      :counters {:files (count files)
                 :parse-errors (count (filter #(not= :ok (:status %)) files))
                 :candidates (count candidates)
                 :observations (count observations)
                 :by-status (by-status observations)}})))

(defn emit [input]
  (canonical-pr (assemble input)))
