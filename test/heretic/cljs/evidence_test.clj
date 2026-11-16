(ns heretic.cljs.evidence-test
  (:require [clojure.test :refer [deftest is]]
            [heretic.cljs.evidence :as evidence]))

(def commit "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
(def tree "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
(def file-sha "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
(def runtime {:availability :observed :provenance :worker-reported
              :node-version "v22.23.3" :nbb-version "1.4.207"})

(defn observation [role eligible?]
  {:role role
   :path "src/example.cljs"
   :fingerprint (if (= role :baseline) nil "abc")
   :namespace 'example.test
   :status (if (= role :baseline) :survived :assertion-killed)
   :tests 2 :assertions 3
   :failures (if (= role :baseline) 0 1)
   :errors 0
   :completed? true
   :qualification-eligible? eligible?
   :runtime runtime})

(defn input [eligible?]
  {:commit-sha commit
   :tree-sha tree
   :files [{:path "src/example.cljs" :source-sha256 file-sha :status :ok}]
   :candidates [{:path "src/z.cljs" :fingerprint "zzz" :offset 4 :operator :swap-plus-minus
                 :original "+" :replacement "-"}
                {:path "src/example.cljs" :fingerprint "abc" :offset 1 :operator :swap-plus-minus
                 :original "+" :replacement "-"}]
   :observations [(observation :mutation eligible?)
                  (observation :baseline eligible?)]})

(deftest eligibility-v1-is-explicit
  (let [eligible (evidence/assemble (input true))
        worker (evidence/assemble (input false))]
    (is (true? (:qualification-eligible? eligible)))
    (is (true? (get-in eligible [:eligibility :met?])))
    (is (= [] (get-in eligible [:eligibility :failed])))
    (is (false? (:production-admitted? eligible)))
    (is (false? (:qualification-eligible? worker)))
    (is (= [:worker-ineligible] (get-in worker [:eligibility :failed])))
    (is (false? (:production-admitted? worker)))))

(deftest evidence-record-is-deterministic
  (let [left (input true)
        right (-> left
                  (update :candidates shuffle)
                  (update :observations shuffle)
                  (update :files #(vec (reverse %))))
        plain (evidence/emit left)
        rebound (binding [*print-length* 1
                          *print-level* 1
                          *print-readably* false
                          *print-dup* true]
                  (evidence/emit right))]
    (is (= plain rebound))
    (is (= ["src/example.cljs" "src/z.cljs"]
           (mapv :path (:candidates (evidence/assemble left)))))
    (is (= [:baseline :mutation]
           (mapv :role (:observations (evidence/assemble left)))))))

(deftest malformed-input-stays-ineligible
  (let [record (evidence/assemble {:commit-sha "nope"})]
    (is (false? (:qualification-eligible? record)))
    (is (false? (:production-admitted? record)))
    (is (some #{:malformed :commit-sha :worker-ineligible} (get-in record [:eligibility :failed])))))
