(ns heretic.cljs.protocol
  (:require [clojure.string :as str]
            [heretic.cljs.outcome :as outcome]
            #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])))

(def marker "HERETIC_CLJS_RESULT ")

(defn invalid [request message]
  (outcome/result request {:kind :invalid :message message}))

(defn- one-edn [s]
  #?(:clj (let [r (java.io.PushbackReader. (java.io.StringReader. s))
                value (edn/read {:eof ::eof} r)
                remainder (slurp r)]
            (when (and (not= ::eof value) (str/blank? remainder)) value))
     :cljs (let [values (edn/read-string (str "[" s "\n]"))]
             (when (= 1 (count values)) (first values)))))

(def runtime-marker "HERETIC_CLJS_RUNTIME ")

(defn runtime-observation
  "Extract unambiguous worker-reported versions; absence is explicit."
  [stdout]
  (let [lines (filter #(str/starts-with? % runtime-marker)
                      (str/split-lines (or stdout "")))
        unavailable (fn [reason] {:availability :unavailable
                                  :provenance :worker-reported :reason reason})]
    (cond
      (empty? lines) (unavailable :missing)
      (not= 1 (count lines)) (unavailable :duplicate)
      :else
      (try
        (let [value (one-edn (subs (first lines) (count runtime-marker)))
              node-version (:node-version value)
              nbb-version (:nbb-version value)]
          (if (and (map? value)
                   (= :heretic.cljs/runtime-v1 (:schema value))
                   (string? node-version) (re-matches #"v[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?" node-version)
                   (string? nbb-version) (re-matches #"[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?" nbb-version))
            {:availability :observed :provenance :worker-reported
             :node-version node-version :nbb-version nbb-version}
            (unavailable :malformed)))
        (catch #?(:clj Exception :cljs :default) _ (unavailable :malformed))))))

(defn- valid-result? [request m]
  (let [{:keys [schema backend status completed? tests assertions failures errors
                test-namespaces qualification-eligible?]} m
        counters? (every? #(and (integer? %) (<= 0 %)) [tests assertions failures errors])
        basic? (and (map? m)
                    (= schema :heretic.cljs/result-v1)
                    (= backend :nbb)
                    (= test-namespaces (:test-namespaces request))
                    (false? qualification-eligible?)
                    (boolean? completed?)
                    counters?
                    (<= (+ (or failures 0) (or errors 0)) (or assertions 0)))
        count-ok? (and counters? (pos? tests) (pos? assertions)
                       (or (nil? (:expected-tests request))
                           (= tests (:expected-tests request))))]
    (and basic?
         (case status
           :survived (and completed? count-ok? (zero? failures) (zero? errors))
           :assertion-killed (and completed? count-ok? (pos? failures) (zero? errors))
           :test-error (or (and completed? (pos? errors))
                           (and (not completed?) (zero? tests) (zero? assertions)))
           :timeout (and (not completed?) (zero? tests) (zero? assertions))
           :load-error (and (not completed?) (zero? tests) (zero? assertions))
           :invalid-test-output true
           false))))

(defn validate-output
  "Validate the child's single terminal result against its request and execution."
  [request stdout exit-code timed-out?]
  (cond
    timed-out? (outcome/result request {:kind :timeout :message "Parent hard deadline exceeded"})
    (not (outcome/valid-request? request)) (invalid request "Invalid request")
    (not= 0 exit-code) (invalid request "Child exited unsuccessfully")
    :else
    (try
      (let [lines (str/split-lines (or stdout ""))
            nonblank (remove str/blank? lines)
            markers (filter #(str/starts-with? % marker) lines)]
        (if (and (= 1 (count markers)) (= (last nonblank) (first markers)))
          (let [value (one-edn (subs (first markers) (count marker)))]
            (if (and (valid-result? request value)
                     (or (not (#{:survived :assertion-killed} (:status value)))
                         (= :observed (:availability (runtime-observation stdout)))))
              value
              (invalid request "Invalid child result or runtime observation")))
          (invalid request "Missing, duplicate, or non-final result marker")))
      (catch #?(:clj Exception :cljs :default) _
        (invalid request "Malformed child EDN")))))
