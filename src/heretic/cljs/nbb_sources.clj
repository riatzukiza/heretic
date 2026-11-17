(ns heretic.cljs.nbb-sources
  "Rewrite shadow-cljs string libspecs into the form nbb loads.
   `:default` and `:refer` on a JavaScript module are shadow syntax.
   nbb imports those bindings as `package$export`."
  (:require [clojure.string :as str]
            [rewrite-clj.zip :as z]))

(defn- libspec-form [loc]
  (try (z/sexpr loc)
       (catch Exception _ nil)))

(defn- shadow-libspec [form]
  (when (and (vector? form) (string? (first form)))
    (let [opts (apply hash-map (rest form))]
      (when (or (:as opts) (:default opts) (:refer opts))
        opts))))

(defn- emit-libspecs [pkg opts]
  (let [alias (:as opts)
        default (:default opts)
        refers (or (:refer opts) [])
        ;; nbb's :as binding is the module namespace. Node builtins and
        ;; ESM packages expose properties there. CJS packages such as pg
        ;; expose only `default`, which is the object shadow's :as binds.
        module (if (str/starts-with? pkg "node:") pkg (str pkg "$default"))]
    (str/join " "
              (cond-> []
                default (conj (str "[\"" pkg "$default\" :as " default "]"))
                alias (conj (str "[\"" module "\" :as " alias "]"))
                true (into (map (fn [sym]
                                  (str "[\"" pkg "$" sym "\" :as " sym "]"))
                                refers))))))

(defn- require-vectors [ns-loc]
  (when-let [require-list (->> (z/down ns-loc)
                               (iterate z/right)
                               (take-while some?)
                               (filter #(and (= :list (z/tag %))
                                             (= :require (some-> % z/down z/sexpr))))
                               first)]
    (->> (z/down require-list)
         (iterate z/right)
         (take-while some?)
         (filter #(= :vector (z/tag %))))))

(defn- contains-await? [form]
  (letfn [(walk [x]
            (cond
              (= x 'await) true
              (coll? x) (boolean (some walk x))
              :else false))]
    (walk form)))

(defn- body-awaits?
  "True when this form uses await outside a nested fn or defn.
   SCI rewrites await only in the function being marked, and marking an
   outer defn makes it return a promise instead of the function it builds."
  [form]
  (letfn [(walk [x]
            (cond
              (= x 'await) true
              (and (seq? x) (#{'fn 'fn* 'defn 'defn-} (first x))) false
              (coll? x) (boolean (some walk x))
              :else false))]
    (walk form)))

(defn- mark-defn [text]
  (cond
    (re-find #"\(defn-\s+\^:async\b" text) text
    (re-find #"\(defn\s+\^:async\b" text) text
    (str/starts-with? text "(defn- ") (str "(defn- ^:async " (subs text 7))
    (str/starts-with? text "(defn ") (str "(defn ^:async " (subs text 6))
    :else text))

(defn- wrap-deftest [loc]
  (let [name (-> loc z/down z/right z/string)
        head (-> loc z/down z/string)
        body (->> (-> loc z/down z/right z/right)
                  (iterate z/right)
                  (take-while some?)
                  (map z/string)
                  (str/join "\n"))]
    (str "(" head " " name
         " (cljs.test/async done#"
         " (-> (^:async (fn []\n" body "\n))"
         " (.catch (fn [err#] (cljs.test/is false (str err#))))"
         " (.finally done#))))")))

(defn- rewrite-with-redefs [loc]
  (let [bindings (-> loc z/down z/right)
        pairs (->> (z/down bindings)
                   (iterate z/right)
                   (take-while some?)
                   (partition 2))
        binding-src (str/join " " (map (fn [[var-loc expr-loc]]
                                          (str "(var " (z/string var-loc) ") " (z/string expr-loc)))
                                        pairs))
        body (->> (z/right bindings)
                  (iterate z/right)
                  (take-while some?)
                  (map z/string)
                  (str/join "\n"))]
    (str "(with-redefs-fn (hash-map " binding-src ") ^:async (fn []\n" body "))")))

(defn- replace-with-redefs [source]
  (let [root (z/of-string* source)
        forms (when-let [first-form (z/down* root)]
                (take-while some? (iterate z/right* first-form)))
        locs (filter (fn [loc]
                       (and (= :list (z/tag loc))
                            (= 'with-redefs (some-> loc z/down z/sexpr))
                            (body-awaits? (libspec-form loc))))
                     (mapcat (fn [form]
                               (tree-seq (fn [loc]
                                           (or (= :list (z/tag loc))
                                               (= :vector (z/tag loc))
                                               (= :map (z/tag loc))))
                                         (fn [loc]
                                           (when-let [down (z/down loc)]
                                             (take-while some? (iterate z/right down))))
                                         form))
                             (or forms [])))]
    (if-let [loc (first (sort-by (comp count z/string) locs))]
      (str/replace source (z/string loc) (rewrite-with-redefs loc))
      source)))

(defn- mark-async [source]
  (let [root (z/of-string* source)
        forms (when-let [first-form (z/down* root)]
                (take-while some? (iterate z/right* first-form)))]
    (reduce (fn [text loc]
              (let [form (libspec-form loc)
                    head (when (seq? form) (first form))
                    original (z/string loc)]
                (cond
                  (and (#{'deftest 'deftest-} head)
                       (contains-await? form)
                       (not (str/includes? original "cljs.test/async done#")))
                  (str/replace text original (wrap-deftest loc))
                  (and (#{'defn 'defn-} head) (body-awaits? form))
                  (str/replace text original (mark-defn original))
                  :else text)))
            source
            (or forms []))))

(defn rewrite-source
  "Return source with shadow JavaScript libspecs translated for nbb, and
   with await bodies marked async so SCI rewrites them."
  [source]
  (let [ns-loc (z/of-string source)
        rewritten (if-not (and ns-loc
                               (= :list (z/tag ns-loc))
                               (= 'ns (some-> ns-loc z/down z/sexpr)))
                    source
                    (reduce (fn [text loc]
                              (if-let [opts (shadow-libspec (libspec-form loc))]
                                (str/replace text
                                             (z/string loc)
                                             (emit-libspecs (first (libspec-form loc)) opts))
                                text))
                            source
                            (require-vectors ns-loc)))
        redefs (loop [text rewritten
                      n 0]
                 (if (> n 200)
                   text
                   (let [next-text (replace-with-redefs text)]
                     (if (= next-text text)
                       text
                       (recur next-text (inc n))))))
        async-marked (mark-async redefs)]
    (str/replace async-marked
                 ":requestLock with-local-oauth-lock"
                 ":requestLock (heretic.cljs.nbb-prelude/as-js with-local-oauth-lock)")))
