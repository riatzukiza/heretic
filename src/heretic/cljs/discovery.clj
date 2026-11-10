(ns heretic.cljs.discovery
  "Pure, source-preserving CLJS mutation discovery for the experimental backend.

   Selected reader dispatch forms are supported. Candidate
   identities bind the path, complete source, position, operator and edit."
  (:require [clojure.string :as str]
            [edamame.core :as edamame]
            [heretic.coord-mapper :as coord]
            [heretic.operators :as ops]
            [heretic.parser :as parser]
            [rewrite-clj.zip :as z])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(defn- sha256 [s]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes ^String s StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and 0xff %)) digest))))

(defn- reject-dispatch!
  "Inspect dispatch syntax outside strings, comments and character literals.
   Discard, sets, anonymous functions and regexes are handled by rewrite-clj."
  [source]
  (let [n (count source)]
    (loop [i 0 state :code escaped? false]
      (when (< i n)
        (let [c (.charAt ^String source i)
              next-char (when (< (inc i) n) (.charAt ^String source (inc i)))]
          (case state
            :comment (recur (inc i) (if (#{\newline \return} c) :code :comment) false)
            :string (cond escaped? (recur (inc i) :string false)
                          (= c \\) (recur (inc i) :string true)
                          (= c \") (recur (inc i) :code false)
                          :else (recur (inc i) :string false))
            :code (cond
                    (= c \;) (recur (inc i) :comment false)
                    (= c \") (recur (inc i) :string false)
                    ;; The first character of a character literal is always consumed.
                    (= c \\) (recur (min n (+ i 2)) :code false)
                    (= c \#) (cond
                                (= next-char \_) (recur (+ i 2) :code false)
                                (#{\{ \( \"} next-char) (recur (inc i) :code false)
                                :else (throw (ex-info "Unsupported reader dispatch" {:offset i})))
                    :else (recur (inc i) :code false))))))))

(defn- children [loc]
  (when-let [child (z/down loc)]
    (take-while some? (iterate z/right child))))

(defn- validate-regex [pattern]
  ;; Restrict advanced Java constructs; nbb supports an initial (?i) flag.
  ;; Each compiled pattern is a distinct object, as with the nbb reader.
  (let [body (if (str/starts-with? pattern "(?i)") (subs pattern 4) pattern)
        stripped (str/replace body #"\\[dDsSwW./\\-]" "x")]
    (when (or (not (re-matches #"[A-Za-z0-9_ .:%^$*+?(){}\[\]|,\-]*" stripped))
              (re-find #"\(\?(?!:)" stripped)
              (re-find #"[+*?][+*?]" stripped)
              (str/includes? stripped "[:"))
    (throw (ex-info "Unsupported regex syntax" {:pattern pattern})))
    (re-pattern pattern)))

(defn- head-symbol [loc]
  (when (= :list (z/tag loc))
    (some-> loc z/down z/sexpr)))

(defn- core-head [head context]
  (when (symbol? head)
    (if-let [prefix (namespace head)]
      (let [target (or (get context (symbol prefix)) (symbol prefix))]
        (when (#{'cljs.core 'clojure.core} target) (symbol (name head))))
      head)))

(defn- contextual-forms [source]
  (let [raw-forms (vec (parser/top-level-forms (z/of-string source {:track-position? true})))
        root (z/of-string* source {:track-position? true})
        children (when-let [first-child (z/down* root)]
                   (take-while some? (iterate z/right* first-child)))
        contexts-by-position
        (loop [remaining children context {} result {}]
          (if-let [form (first remaining)]
            (if (z/whitespace-or-comment? form)
              (recur (next remaining) context result)
              ;; Validate discarded forms too, but only a returned live ns
              ;; declaration may change the context of following forms.
              (let [parsed (edamame/parse-string-all
                            (z/string form)
                            {:all true :read-eval false
                             :auto-resolve context :regex validate-regex})
                    value (first parsed)
                    context (if (and (seq? value)
                                     (= 'ns (core-head (first value) context)))
                              (let [info (edamame/parse-ns-form value)]
                                (assoc (:aliases info) :current (:current info)))
                              context)]
                (recur (next remaining) context (assoc result (z/position form) context))))
            result))
        ;; The zipper's public option is fixed per parse, so retain one full
        ;; zipper per distinct namespace context, preserving original positions.
        forms-for (memoize (fn [context]
                             (vec (parser/top-level-forms
                                   (z/of-string source
                                                {:track-position? true
                                                 :auto-resolve (fn [alias]
                                                                 (or (get context alias)
                                                                     (throw (ex-info "Unresolved namespace alias" {:alias alias}))))})))))]
    (mapv (fn [i form]
            (let [context (or (get contexts-by-position (z/position form))
                              (throw (ex-info "Missing top-level reader context"
                                              {:position (z/position form)})))]
              {:form (nth (forms-for context) i) :context context}))
          (range) raw-forms)))

(defn- string-node? [loc]
  (and loc (= :token (z/tag loc))
       (string? (z/sexpr loc))))

(defn- body-children [loc head]
  (let [cs (vec (children loc))]
    (cond
      (#{'defn 'defn- 'defmacro} head)
      (let [body (drop 2 cs)
            body (if (string-node? (first body)) (rest body) body)
            body (if (= :map (some-> body first z/tag)) (rest body) body)
            body (vec body)]
        ;; Multi-arity defn permits a trailing attribute map.
        (if (and (= :list (some-> body first z/tag))
                 (= :map (some-> body last z/tag)))
          (pop body) body))

      (= 'def head)
      (let [body (drop 2 cs)]
        (if (and (next body) (string-node? (first body))) (rest body) body))

      :else cs)))

(defn- walk-executable [loc context]
  (lazy-seq
   (when loc
     (let [tag (z/tag loc) head (core-head (head-symbol loc) context)]
       (cond
         (#{:quote :syntax-quote :uneval :discard} tag) nil
         (#{'ns 'comment 'quote} head) nil
         ;; The last child is the annotated form; preceding children are metadata.
         (#{:meta :meta*} tag) (walk-executable (last (children loc)) context)
         :else (cons loc (mapcat #(walk-executable % context) (body-children loc head))))))))

(defn- offset-at [source line column]
  (loop [i 0 l 1]
    (cond
      (= l line) (+ i (dec column))
      (>= i (count source)) (throw (ex-info "Invalid source position" {:line line :column column}))
      (= \return (.charAt ^String source i))
      (recur (if (and (< (inc i) (count source))
                      (= \newline (.charAt ^String source (inc i))))
               (+ i 2) (inc i))
             (inc l))
      (= \newline (.charAt ^String source i)) (recur (inc i) (inc l))
      :else (recur (inc i) l))))

(defn- fingerprint [candidate]
  (sha256 (pr-str [:heretic.cljs/candidate-v2
                   (:path candidate) (:source-sha256 candidate)
                   (:operator candidate) (:original candidate) (:replacement candidate)
                   (:line candidate) (:column candidate) (:form-id candidate)
                   (:coord candidate) (:form-index candidate) (:offset candidate)])))

(def ^:private expression-operators
  #{:replace-comparison-false :replace-comparison-true :replace-and-false :replace-or-true})

(defn- target-loc [loc op]
  (if (expression-operators (:id op))
    (let [parent (z/up loc)]
      (when (and parent (= :list (z/tag parent)) (nil? (z/left loc))) parent))
    loc))

(defn- candidate [path source digest form-index form loc op]
  (let [[line column] (z/position loc)
        original (z/string loc)
        offset (offset-at source line column)
        replacement (if (string? (:replacement op))
                      (pr-str (:replacement op))
                      (ops/apply-operator op loc))
        result {:path path :source-sha256 digest :operator (:id op)
                :original original :replacement replacement
                :line line :column column :offset offset :form-index form-index
                :form-id (hash (z/string form))
                :coord (coord/stringify-coord (coord/zloc->coord loc))}]
    (when-not (= original (subs source offset (+ offset (count original))))
      (throw (ex-info "Source position does not identify the parsed token" {:line line :column column})))
    (assoc result :fingerprint (fingerprint result))))

(defn discover-source
  "Discover candidates for a normalized relative path and source string.
   Unsupported readers and parse errors are returned as :parse-error."
  [{:keys [path source operators]}]
  (try
    (when-not (and (string? path) (not (str/blank? path))
                   (not (str/starts-with? path "/"))
                   (not (str/includes? path "\\"))
                   (not-any? #{"." ".." ""} (str/split path #"/" -1))
                   (string? source))
      (throw (ex-info "Expected normalized relative path and source string" {})))
    (when (and (some? operators)
               (or (not (vector? operators))
                   (some #(not (contains? ops/operators-by-id %)) operators)))
      (throw (ex-info "Unknown or invalid operator selection" {:operators operators})))
    (reject-dispatch! source)
    (let [forms (contextual-forms source)
          selected (if (nil? operators) ops/all-operators
                       (mapv ops/operators-by-id (distinct operators)))
          digest (sha256 source)
          sites (vec (for [[form-index {:keys [form context]}] (map-indexed vector forms)
                           loc (walk-executable form context)
                           :when (= :token (z/tag loc))
                           op selected
                           :when ((:matcher op) loc)
                           :let [target (target-loc loc op)]
                           :when target]
                       (candidate path source digest form-index form target op)))]
      {:status :ok :source-sha256 digest :candidates sites})
    (catch Exception e
      {:status :parse-error :path path :message (or (.getMessage e) (str (class e))) :candidates []})))

(defn resolve-candidate
  "Resolve a full candidate to its exact zipper location, or reject its identity.
   The legacy upstream form-id/coord pair alone is not occurrence-aware."
  [{:keys [source candidate]}]
  (let [{:keys [path operator offset form-index]} candidate
        result (discover-source {:path path :source source :operators [operator]})]
    (when-not (and (= :ok (:status result))
                   (some #(= candidate %) (:candidates result)))
      (throw (ex-info "Stale or invalid candidate" {:path path :operator operator})))
    (let [{:keys [form context]} (nth (contextual-forms source) form-index nil)
          matches (filter (fn [loc]
                            (when-let [[line column] (z/position loc)]
                              (and (#{:token :list} (z/tag loc))
                                   (= (:original candidate) (z/string loc))
                                   (= offset (offset-at source line column)))))
                          (walk-executable form context))]
      (when-not (= 1 (count matches))
        (throw (ex-info "Candidate does not resolve to one source location" {:path path :offset offset})))
      (first matches))))

(defn apply-candidate
  "Apply exactly one previously discovered candidate, rejecting stale or altered identity."
  [{:keys [source candidate] :as request}]
  (let [{:keys [offset original replacement]} candidate]
    (resolve-candidate request)
    (str (subs source 0 offset) replacement
         (subs source (+ offset (count original))))))
