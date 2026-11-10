(ns heretic.cljs.discovery-test
  (:require [clojure.test :refer [deftest is testing]]
            [heretic.cljs.discovery :as discovery]
            [heretic.operators :as ops]
            [rewrite-clj.zip :as z]))

(defn discover [source]
  (discovery/discover-source {:path "src/example.cljs" :source source
                              :operators [:swap-plus-minus :swap-true-false]}))

(deftest deterministic-and-exact-edit
  (let [source "; α\r\n(defn f [x] (+ x 1)) ; trailing\r\n"
        a (discover source) b (discover source) candidate (first (:candidates a))]
    (is (= :ok (:status a)))
    (is (= (:candidates a) (:candidates b)))
    (is (= 64 (count (:fingerprint candidate))))
    (is (= "+" (:original candidate)))
    (is (= "-" (:replacement candidate)))
    (is (= "; α\r\n(defn f [x] (- x 1)) ; trailing\r\n"
           (discovery/apply-candidate {:source source :candidate candidate})))
    (is (thrown? Exception (discovery/apply-candidate {:source (str source " ") :candidate candidate})))
    (is (thrown? Exception (discovery/apply-candidate {:source source :candidate (assoc candidate :replacement "evil")})))))

(deftest exclusions
  (let [source "(ns demo (:require [foo :as f]))\n(defn f \"true (+ 1 2)\" ^{:tag true} [x] ; (+ 1 2)\n  #_(+ 1 2) (comment (+ 1 2)) '(+ 1 2) (quote (+ 1 2)) (+ x 1))"
        result (discover source)]
    (is (= :ok (:status result)))
    (is (= ["+"] (mapv :original (:candidates result))))))

(deftest invalid-and-unsupported
  (is (= :parse-error (:status (discover "(defn broken [x] (+ x 1)"))))
  (doseq [source ["#?(:cljs (+ 1 2) :clj (+ 2 3))" "#foo/bar (+ 1 2)" "#= (+ 1 2)"]]
    (let [result (discover source)]
      (is (not= :ok (:status result)) source)
      (is (empty? (:candidates result)) source))))

(deftest metadata-target-and-string-replacement
  (let [source "^{:tag true} (defn f [] (+ 1 nil))"
        result (discovery/discover-source {:path "x.cljs" :source source
                                          :operators [:swap-plus-minus :swap-true-false :replace-nil-empty-str]})
        sites (:candidates result)]
    (is (= :ok (:status result)))
    (is (= ["+" "nil"] (mapv :original sites)))
    (is (= "\"\"" (:replacement (second sites))))
    (is (= "^{:tag true} (defn f [] (+ 1 \"\"))"
           (discovery/apply-candidate {:source source :candidate (second sites)})))))

(deftest common-reader-forms-and-exact-unicode
  (let [source "; 😀 before position\r\n(def a #{(+ 1 2) true})\r\n(def b #(+ % 1))\r\n(def r #\"a+true\")"
        result (discover source)
        candidates (:candidates result)]
    (is (= :ok (:status result)))
    (is (= ["+" "true" "+"] (mapv :original candidates)))
    (is (= "; 😀 before position\r\n(def a #{(- 1 2) true})\r\n(def b #(+ % 1))\r\n(def r #\"a+true\")"
           (discovery/apply-candidate {:source source :candidate (first candidates)})))))

(deftest empty-and-duplicate-forms
  (doseq [source ["" "; just a comment\n"]]
    (is (= [] (:candidates (discover source))))
    (is (= :ok (:status (discover source)))))
  (let [result (discover "(+ 1 2)\n(+ 1 2)")
        sites (:candidates result)]
    (is (= 2 (count sites)))
    (is (= 2 (count (set (map :fingerprint sites)))))
    (is (= sites (:candidates (discover "(+ 1 2)\n(+ 1 2)"))))))

;; Upstream has no string-input mutation operator, so exercise that boundary
;; using a synthetic operator with the same registry/matcher interface.
(deftest documentation-and-attribute-exclusions
  (let [string-op {:id :test-string :replacement "changed"
                   :matcher #(and (= :token (z/tag %)) (string? (z/sexpr %)))}
        registry (assoc ops/operators-by-id :test-string string-op)
        source "(def x \"init\")\n(def y \"def doc\" \"value\")\n(defn f \"doc\" {:deprecated true} [x] (str \"body\" true))"]
    (with-redefs [ops/operators-by-id registry]
      (let [result (discovery/discover-source {:path "x.cljs" :source source
                                               :operators [:test-string :swap-true-false]})
            sites (:candidates result)]
        (is (= :ok (:status result)))
        (is (= ["\"init\"" "\"value\"" "\"body\"" "true"] (mapv :original sites)))
        (is (= "\"changed\"" (:replacement (first sites))))))))

(deftest non-bmp-on-candidate-line
  (let [source "(str \"😀\" (+ 1 2))"
        result (discover source)
        candidate (first (:candidates result))]
    (is (= :ok (:status result)))
    (is (= "(str \"😀\" (- 1 2))"
           (discovery/apply-candidate {:source source :candidate candidate})))))

(deftest trailing-multi-arity-attributes
  (let [result (discover "(defn f ([x] (+ x 1)) {:deprecated true})")]
    (is (= :ok (:status result)))
    (is (= ["+"] (mapv :original (:candidates result))))))

(deftest malformed-map-arity-is-a-parse-error
  (doseq [source ["{:a true :b}" "(def x {:a {:b}})" "{:a #_x}"
                  "#_{:a} {:ok true}" "{:a 1 #_:discard :b}"]]
    (let [result (discover source)]
      (is (= :parse-error (:status result)) source)
      (is (empty? (:candidates result)) source)))
  (doseq [source ["{}" "{:a #_x true}" "{:a 1 #_:discard :b 2}" "#_{} {:a 1}"]]
    (is (= :ok (:status (discover source))) source)))

(deftest identical-forms-use-occurrence-aware-locator
  (let [source "(+ 1 2)\n(+ 1 2)"
        [first-candidate second-candidate] (:candidates (discover source))
        resolved (discovery/resolve-candidate {:source source :candidate second-candidate})]
    (is (= [0 1] (mapv :form-index [first-candidate second-candidate])))
    (is (= (:form-id first-candidate) (:form-id second-candidate)))
    (is (= (:coord first-candidate) (:coord second-candidate)))
    (is (= [2 2] (z/position resolved)))
    (is (= "(+ 1 2)\n(- 1 2)" (discovery/apply-candidate {:source source :candidate second-candidate})))
    (is (thrown? Exception (discovery/resolve-candidate {:source source :candidate (assoc second-candidate :form-index 0)})))))

(deftest reader-rejects-duplicate-literals
  (doseq [source ["{:a 1 :a 2}" "#{true true}" "(def a {:x #{1 1}})"
                  "#_{:a 1 :a 2} (+ 1 2)" "{:x #_ignored 1 :x 2}"
                  "(ns example.current) {::key 1 :example.current/key 2}"
                  "(ns example.core (:require [example.other :as other])) {::other/key 1 :example.other/key 2}"]]
    (let [result (discover source)]
      (is (= :parse-error (:status result)) source)
      (is (empty? (:candidates result)) source)))
  (doseq [source ["{:a 1 :b 2}" "#{true false}" "(ns example.core (:require [example.other :as other])) {::other/key 1 :example.core/key 2}"
                  "#_{} {:a 1}" "(def f #(+ % 1))" "(def r #\"a+\")"]]
    (is (= :ok (:status (discover source))) source)))

(deftest discovery-does-not-create-namespaces
  (let [before (set (map ns-name (all-ns)))]
    (discover "(ns never.created.by.heretic (:require [other.example :as x])) {::x/key 1}")
    (is (= before (set (map ns-name (all-ns)))))))

(deftest constant-expression-operators-replace-whole-calls
  (doseq [[operator source original replacement expected]
          [[:replace-comparison-false "; c\n(< 1 2)\n" "(< 1 2)" "false" "; c\nfalse\n"]
           [:replace-comparison-true "(< 1 2)" "(< 1 2)" "true" "true"]
           [:replace-and-false "(and true false)" "(and true false)" "false" "false"]
           [:replace-or-true "(or true false)" "(or true false)" "true" "true"]]]
    (let [result (discovery/discover-source {:path "x.cljs" :source source :operators [operator]})
          site (first (:candidates result))]
      (is (= :ok (:status result)))
      (is (= 1 (count (:candidates result))))
      (is (= [original replacement] [(:original site) (:replacement site)]))
      (is (= original (z/string (discovery/resolve-candidate {:source source :candidate site}))))
      (is (= expected (discovery/apply-candidate {:source source :candidate site})))))
  (let [source "(list < and or)\n(< 1 (< 2 3))\n(< 1 2)\n(< 1 2)"
        result (discovery/discover-source {:path "x.cljs" :source source
                                          :operators [:replace-comparison-false :replace-and-false :replace-or-true]})
        sites (:candidates result)]
    (is (= 4 (count sites)))
    (is (= ["(< 1 (< 2 3))" "(< 2 3)" "(< 1 2)" "(< 1 2)"] (mapv :original sites)))
    (is (= "(list < and or)\n(< 1 (< 2 3))\n(< 1 2)\nfalse"
           (discovery/apply-candidate {:source source :candidate (last sites)})))))

(deftest regex-and-anonymous-function-reader-boundary
  ;; Pinned nbb rejects identical anonymous-function reader forms in a set,
  ;; while separately compiled regex literals are distinct values.
  (is (= :parse-error (:status (discover "#{#(+ % 1) #(+ % 1)}"))))
  (is (= :ok (:status (discover "#{#\"a\" #\"a\"}"))))
  (is (= :ok (:status (discover "(ns a) #\"(?i)^[a-z0-9]{3}\\\\d+$\""))))
  (is (= :parse-error (:status (discover "#\"(?!a)b\""))))
  (is (= :parse-error (:status (discover "#\"a++\""))))
  (is (= :parse-error (:status (discover "::missing/key")))))

(deftest resolved-keyword-operators-use-form-namespace
  (let [source "(ns sample (:require [clojure.string :as s]))\n(def k ::s/item)\n(def current ::item)\n(def m {::s/user-id 1 ::userName 2})"
        result (discovery/discover-source {:path "x.cljs" :source source
                  :operators [:mutate-ns-typo :mutate-qualified-to-unqualified
                              :mutate-kebab-to-camel :mutate-camel-to-kebab]})
        sites (:candidates result)
        find-site (fn [original operator] (first (filter #(and (= original (:original %)) (= operator (:operator %))) sites)))]
    (is (= :ok (:status result)))
    (doseq [[original operator replacement]
            [["::s/item" :mutate-ns-typo ":clojure.strings/item"]
             ["::s/item" :mutate-qualified-to-unqualified ":item"]
             ["::item" :mutate-ns-typo ":samples/item"]
             ["::s/user-id" :mutate-kebab-to-camel ":clojure.string/userId"]
             ["::userName" :mutate-camel-to-kebab ":sample/user-name"]]]
      (let [site (find-site original operator)]
        (is (= replacement (:replacement site)) [original operator])
        (when site
          (is (not (clojure.string/includes?
                    (str (z/sexpr (discovery/resolve-candidate {:source source :candidate site}))) "?")))
          (is (= (str (subs source 0 (:offset site)) replacement (subs source (+ (:offset site) (count original))))
                 (discovery/apply-candidate {:source source :candidate site}))))))
    (is (not-any? #(clojure.string/includes? (:replacement %) "??") sites))
    (is (not-any? #(clojure.string/includes? (:replacement %) "??")
                  (:candidates (discovery/discover-source {:path "x.cljs" :source source}))))))

(deftest multiple-namespaces-do-not-leak-context
  (let [source "(ns first.core (:require [first.lib :as x])) (def a ::x/key) (ns second.core (:require [second.lib :as x])) (def b ::x/key)"
        sites (:candidates (discovery/discover-source {:path "x.cljs" :source source :operators [:mutate-ns-typo]}))]
    (is (= [":first.libs/key" ":second.libs/key"] (mapv :replacement sites)))
    (is (= ["::x/key" "::x/key"] (mapv :original sites)))))

(deftest source-offsets-honor-all-line-endings
  (doseq [separator ["\n" "\r\n" "\r"]]
    (let [source (str "(ns a)" separator "(+ 1 2)")
          candidate (first (:candidates (discover source)))
          prefix (str "(ns a)" separator "(")]
      (is (some? candidate) (pr-str separator))
      (when candidate
        (is (= (count prefix) (:offset candidate)) (pr-str separator))
        (is (= [2 2] [(:line candidate) (:column candidate)]))
        (is (= "+" (z/string (discovery/resolve-candidate {:source source :candidate candidate}))))
        (is (= (str "(ns a)" separator "(- 1 2)")
               (discovery/apply-candidate {:source source :candidate candidate})))
        (is (= candidate (first (:candidates (discover source))))))))
  (let [source "; 😀\r(ns a)\r\n(def x 1)\n(+ 1 2)\r"
        result (discover source)
        candidate (first (:candidates result))]
    (is (= :ok (:status result)))
    (is (some? candidate))
    (when candidate
      (is (= (.indexOf source "+") (:offset candidate)))
      (is (= [4 2] [(:line candidate) (:column candidate)]))
      (is (= "; 😀\r(ns a)\r\n(def x 1)\n(- 1 2)\r"
             (discovery/apply-candidate {:source source :candidate candidate}))))))

(deftest core-qualified-and-aliased-forms
  (let [source "(ns demo (:require [cljs.core :as c] [clojure.core :as j]))\n(cljs.core/comment (+ 1 2))\n(clojure.core/comment (+ 1 2))\n(c/comment (+ 1 2))\n(j/comment (+ 1 2))\n(foo/comment (+ 1 2))\n(cljs.core/defn f \"doc\" {:flag true} [] (+ 1 2))\n(j/defmacro g \"doc\" {:flag true} [] (+ 1 2))\n(cljs.core/ns another (:require [foo.bar :as foo]))"
        result (discover source)]
    (is (= :ok (:status result)))
    (is (= ["+" "+" "+"] (mapv :original (:candidates result))))
    (is (= [6 7 8] (mapv :line (:candidates result))))
    (is (= "+" (z/string (discovery/resolve-candidate {:source source :candidate (first (:candidates result))}))))
    (is (clojure.string/includes?
         (discovery/apply-candidate {:source source :candidate (first (:candidates result))})
         "(foo/comment (- 1 2))"))))

(deftest core-qualified-declaration-docstrings-are-excluded
  (let [string-op {:id :test-string :replacement "changed"
                   :matcher #(and (= :token (z/tag %)) (string? (z/sexpr %)))}
        source "(ns demo (:require [cljs.core :as c])) (cljs.core/defn f \"doc\" {:private true} [] \"body\") (c/def g \"doc2\" \"init\")"]
    (with-redefs [ops/operators-by-id (assoc ops/operators-by-id :test-string string-op)]
      (let [result (discovery/discover-source {:path "x.cljs" :source source :operators [:test-string :swap-true-false]})]
        (is (= :ok (:status result)))
        (is (= ["\"body\"" "\"init\""] (mapv :original (:candidates result))))))))

(deftest discarded-namespaces-do-not-change-reader-context
  (let [start "(ns review.repro) #_(ns broken) "]
    (is (= :parse-error (:status (discover (str start "{::key 1 :review.repro/key 2}")))))
    (is (= :ok (:status (discover (str start "{::key 1 :broken/key 2}"))))))
  (let [source "(ns review.repro (:require [cljs.core :as b])) #_(ns broken) (def x ::b/key)"
        result (discovery/discover-source {:path "x.cljs" :source source :operators [:mutate-ns-typo]})
        candidate (first (:candidates result))]
    (is (= :ok (:status result)))
    (is (= "::b/key" (:original candidate)))
    (is (= ":cljs.cores/key" (:replacement candidate)))
    (when candidate
      (is (= ":cljs.core/key" (str (z/sexpr (discovery/resolve-candidate {:source source :candidate candidate})))))
      (is (= "(ns review.repro (:require [cljs.core :as b])) #_(ns broken) (def x :cljs.cores/key)"
             (discovery/apply-candidate {:source source :candidate candidate})))))
  (is (= :parse-error (:status (discover "(ns a) #_{:a 1 :a 2} (+ 1 2)")))))

(deftest metadata-wrapped-real-namespace-carries-context
  (let [source "^{:x true} (ns review.repro (:require [cljs.core :as b])) (def x ::b/key)"
        result (discovery/discover-source {:path "x.cljs" :source source :operators [:mutate-ns-typo]})
        candidate (first (:candidates result))]
    (is (= :ok (:status result)))
    (is (= "::b/key" (:original candidate)))
    (is (= ":cljs.cores/key" (:replacement candidate)))
    (when candidate
      (is (= ":cljs.core/key" (str (z/sexpr (discovery/resolve-candidate {:source source :candidate candidate})))))
      (is (= "^{:x true} (ns review.repro (:require [cljs.core :as b])) (def x :cljs.cores/key)"
             (discovery/apply-candidate {:source source :candidate candidate}))))))

(deftest matcher-failures-are-explicit
  (let [op {:id :test-throws :matcher (fn [_] (throw (ex-info "resolution failed" {}))) :replacement false}]
    (with-redefs [ops/operators-by-id (assoc ops/operators-by-id :test-throws op)]
      (let [result (discovery/discover-source {:path "x.cljs" :source "(def x 1)" :operators [:test-throws]})]
        (is (= :parse-error (:status result)))
        (is (= [] (:candidates result)))
        (is (= "resolution failed" (:message result)))))))

(deftest nested-discard-consumes-following-reader-form
  (let [source "(ns live) #_ #_(ns ignored1) (ns ignored2) (def k ::key)"
        result (discovery/discover-source {:path "x.cljs" :source source :operators [:mutate-ns-typo]})
        candidate (first (:candidates result))]
    (is (= :ok (:status result)))
    (is (= "::key" (:original candidate)))
    (is (= ":lives/key" (:replacement candidate)))
    (is (= "(ns live) #_ #_(ns ignored1) (ns ignored2) (def k :lives/key)"
           (discovery/apply-candidate {:source source :candidate candidate})))))
