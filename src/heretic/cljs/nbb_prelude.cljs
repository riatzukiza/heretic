(ns heretic.cljs.nbb-prelude
  "Install the names nbb does not provide and Axxium's sources resolve.
   HoneySQL's CLJS branch extends PersistentArrayMap and its siblings.
   Shadow code calls unqualified await. cljs.test classes use
   cljs.core/ExceptionInfo, which SCI resolves through clojure.core.
   test.check calls bit-shift-right-zero-fill and es6-iterator-seq."
  (:require [cljs.test :as test]
            [nbb.core :as nbb]
            [sci.core :as sci]))

(defn- ilookup
  "Membership test used by test.check on transient sets. nbb has no ILookup."
  ([coll k]
   (cond
     (map? coll) (get coll k)
     (set? coll) (when (contains? coll k) k)
     (and coll (fn? (.-has coll))) (when (.has coll k) k)
     :else nil))
  ([coll k not-found]
   (let [found (ilookup coll k)]
     (if (nil? found) not-found found))))

(defn- es6-iterator-seq [iter]
  (when iter
    ((fn walk [cursor]
       (lazy-seq
        (let [step (.next cursor)]
          (when-not (.-done step)
            (cons (.-value step) (walk cursor))))))
     iter)))

(defprotocol IIndexed
  "Indexed lookup used by clojure.test.check.rose-tree on ClojureScript.
   nbb does not ship cljs.core/IIndexed."
  (-nth [this i] [this i not-found]))

(def as-js
  "Wrap a SCI function as a real JavaScript function so host code can call
   Function.prototype.bind on it. ^:async functions are not JS functions."
  (js/eval "(function(f){return function(){return f.apply(null, arguments);};})"))

(defn install!
  "Make the compatibility names resolvable before any requested namespace loads.
   Idempotent: interning the same root again is the same mapping."
  []
  (intern 'clojure.core 'await nbb/await)
  (intern 'clojure.core 'ExceptionInfo (resolve 'ExceptionInfo))
  (intern 'clojure.core 'es6-iterator-seq es6-iterator-seq)
  (intern 'clojure.core '-lookup ilookup)
  (intern 'clojure.core '-nth -nth)
  (when-let [ushr (resolve 'unsigned-bit-shift-right)]
    (intern 'clojure.core 'bit-shift-right-zero-fill ushr))
  (when (find-ns 'cljs.test)
    (intern 'cljs.test 'file-and-line (fn [_exception _depth] {:file "nbb" :line 1}))
    (when-not (resolve 'cljs.test/*current-env*)
      (intern 'cljs.test '*current-env* {})))
  (let [multi-fn (js/eval "(function MultiFn() {})")]
    (sci/add-class! (nbb/get-sci-ctx) 'MultiFn multi-fn)
    (intern 'clojure.core 'MultiFn multi-fn))
  (let [ctx (nbb/get-sci-ctx)]
    (doseq [[sym value] [['PersistentArrayMap (type {})]
                         ['PersistentHashMap (type {})]
                         ['PersistentVector (type [])]
                         ['Keyword (type :k)]
                         ['Symbol (type 's)]]]
      (sci/add-class! ctx sym value)))
  (-> (js/Promise.resolve (require '[clojure.test.check.clojure-test.assertions]))
      (.then (fn []
               (defmethod test/assert-expr 'clojure.test.check.clojure-test/check?
                 [msg form]
                 ((resolve 'clojure.test.check.clojure-test.assertions/check?) msg form))))
      (.catch (fn [_] nil))))
