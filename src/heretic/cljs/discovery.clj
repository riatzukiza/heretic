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

(defn- token-boundary?
  "A reader dispatch begins only where a new token can begin."
  [c]
  (or (Character/isWhitespace ^char c)
      (contains? #{\, \( \) \[ \] \{ \} \' \` \~ \"} c)))

(def ^:private symbolic-literals
  "ClojureScript symbolic numbers. The following character must end the token."
  ["##-Inf" "##Inf" "##NaN"])

(defn- symbolic-literal-end [source i]
  (let [n (count source)]
    (some (fn [text]
            (let [end (+ i (count text))]
              (when (and (<= end n)
                         (= text (subs source i end))
                         (or (= end n) (token-boundary? (.charAt ^String source end))))
                end)))
          symbolic-literals)))

(def ^:private supported-dispatch-tags
  "Tags whose following form is ordinary source. Reader conditionals are parsed
   and then left unmutated; #js values are walked as executable expressions."
  #{"js" "inst" "uuid" "?" "?@"})

(defn- dispatch-tag
  "Return {:tag name :end index} for a hash at i, or nil when it is not a named tag."
  [source i]
  (let [n (count source)
        start (inc i)]
    (when (< start n)
      (let [c (.charAt ^String source start)]
        (cond
          (= c \?)
          (if (and (< (inc start) n) (= \@ (.charAt ^String source (inc start))))
            {:tag "?@" :end (+ start 2)}
            {:tag "?" :end (inc start)})
          (Character/isLetter c)
          (loop [k (inc start)]
            (if (and (< k n)
                     (let [ch (.charAt ^String source k)]
                       (or (Character/isLetterOrDigit ch) (contains? #{\_ \-} ch))))
              (recur (inc k))
              {:tag (subs source start k) :end k}))
          :else nil)))))

(defn- reject-dispatch!
  "Inspect dispatch syntax outside strings, comments and character literals.
   A hash is reader dispatch only at a token start: the beginning of the text,
   after whitespace, a comma, a delimiter, a quote, or a closing string, and
   immediately after a consumed discard. A hash inside a symbol, including a
   syntax-quoted auto-gensym, is ordinary text. Discard, sets, anonymous
   functions and regexes are handled by rewrite-clj."
  [source]
  (let [n (count source)]
    (loop [i 0 state :code escaped? false token-start? true]
      (when (< i n)
        (let [c (.charAt ^String source i)
              next-char (when (< (inc i) n) (.charAt ^String source (inc i)))]
          (case state
            :comment (let [newline? (#{\newline \return} c)]
                       (recur (inc i) (if newline? :code :comment) false newline?))
            :string (cond escaped? (recur (inc i) :string false false)
                          (= c \\) (recur (inc i) :string true false)
                          (= c \") (recur (inc i) :code false true)
                          :else (recur (inc i) :string false false))
            :code (cond
                    (= c \;) (recur (inc i) :comment false false)
                    (= c \") (recur (inc i) :string false false)
                    ;; The first character of a character literal is always consumed.
                    (= c \\) (recur (min n (+ i 2)) :code false true)
                    (and (= c \#) token-start?)
                    (let [literal-end (symbolic-literal-end source i)
                          tag (when-not literal-end (dispatch-tag source i))]
                      (cond
                        (= next-char \_) (recur (+ i 2) :code false true)
                        (#{\{ \( \"} next-char) (recur (inc i) :code false true)
                        literal-end (recur literal-end :code false false)
                        (supported-dispatch-tags (:tag tag)) (recur (:end tag) :code false true)
                        :else (throw (ex-info "Unsupported reader dispatch" {:offset i}))))
                    :else (recur (inc i) :code false (token-boundary? c)))))))))

(defn- children [loc]
  (when-let [child (z/down loc)]
    (take-while some? (iterate z/right child))))

(defn- validate-regex [pattern]
  ;; Restrict advanced Java constructs; nbb supports an initial (?i) flag.
  ;; Literal punctuation used by Axxium patterns is accepted. Escapes are the
  ;; ones JavaScript and Java share for a literal metacharacter or class.
  ;; Each compiled pattern is a distinct object, as with the nbb reader.
  (let [body (if (str/starts-with? pattern "(?i)") (subs pattern 4) pattern)
        stripped (-> body
                     (str/replace #"\\u[0-9a-fA-F]{4}" "x")
                     (str/replace #"\\[dDsSwWntrf./\\^$*+?(){}\[\]|#\"-]" "x"))]
    (when (or (not (re-matches #"[A-Za-z0-9_ .:%^$*+?(){}\[\]|,@/=;<>'&#~!\"\-]*" stripped))
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
                             :read-cond :allow :features #{:cljs}
                             :readers {'js (fn [form]
                                             (when-not (or (map? form) (vector? form))
                                               (throw (ex-info "Unsupported #js form" {:form form})))
                                             form)}
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

(defn- reader-tag [loc]
  (when (= :reader-macro (z/tag loc))
    (some-> loc z/down z/string)))

(defn- walk-executable [loc context]
  (lazy-seq
   (when loc
     (let [tag (z/tag loc) head (core-head (head-symbol loc) context)]
       (cond
         (#{:quote :syntax-quote :uneval :discard} tag) nil
         (#{'ns 'comment 'quote} head) nil
         ;; #inst/#uuid and reader conditionals are data or alternate branches.
         ;; #js values are compiled as expressions, so the payload is walked.
         (and (= :reader-macro tag) (= "js" (reader-tag loc)))
         (mapcat #(walk-executable % context) (rest (children loc)))
         (= :reader-macro tag) nil
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

(defn- canonical-pr
  "Print one complete readable value, independent of caller printer bindings."
  [x]
  (binding [*print-length* nil
            *print-level* nil
            *print-readably* true
            *print-dup* false
            *print-meta* false
            *print-namespace-maps* false]
    (pr-str x)))

(defn- fingerprint [candidate]
  (sha256 (canonical-pr [:heretic.cljs/candidate-v2
                         (:path candidate) (:source-sha256 candidate)
                         (:operator candidate) (:original candidate) (:replacement candidate)
                         (:line candidate) (:column candidate) (:form-id candidate)
                         (:coord candidate) (:form-index candidate) (:offset candidate)])))

(def ^:private expression-operators
  #{:replace-comparison-false :replace-comparison-true :replace-and-false :replace-or-true})

;; Replacing one of these calls with true/false puts that constant in operator
;; position: the call is the head of its parent, or a threading macro will
;; insert the threaded value as the new first argument.
(def ^:private threading-heads
  '#{-> ->> some-> some->> cond-> cond->> doto ..})

(defn- call-position? [parent context]
  (let [grand (z/up parent)]
    (and grand
         (= :list (z/tag grand))
         (or (nil? (z/left parent))
             (boolean (threading-heads (core-head (head-symbol grand) context)))))))

(defn- target-loc [loc op context]
  (if (expression-operators (:id op))
    (let [parent (z/up loc)]
      (when (and parent
                 (= :list (z/tag parent))
                 (nil? (z/left loc))
                 (not (call-position? parent context)))
        parent))
    loc))

(defn- candidate [path source digest form-index form loc op]
  (let [[line column] (z/position loc)
        original (z/string loc)
        offset (offset-at source line column)
        replacement (if (string? (:replacement op))
                      (canonical-pr (:replacement op))
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
                           :let [target (target-loc loc op context)]
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
