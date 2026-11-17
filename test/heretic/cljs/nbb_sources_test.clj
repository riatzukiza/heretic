(ns heretic.cljs.nbb-sources-test
  (:require [clojure.test :refer [deftest is]]
            [heretic.cljs.campaign :as campaign]
            [heretic.cljs.nbb-sources :as sources]))

(deftest shadow-libspecs-become-nbb-imports
  (is (= "(ns a (:require [\"fastify$default\" :as Fastify] [\"node:fs\" :as fs] [\"pg$default\" :as pg]))"
         (sources/rewrite-source
          "(ns a (:require [\"fastify\" :default Fastify] [\"node:fs\" :as fs] [\"pg\" :as pg]))")))
  (is (= "(ns a (:require [\"@atproto/identity$IdResolver\" :as IdResolver]))"
         (sources/rewrite-source
          "(ns a (:require [\"@atproto/identity\" :refer [IdResolver]]))")))
  (is (= "(def x 1)" (sources/rewrite-source "(def x 1)")))
  (let [rewritten (sources/rewrite-source "(ns t (:require [cljs.test :refer [deftest is]]))\n(deftest waits (with-redefs [x 1] (is (nil? (await (identity nil))))))\n(defn ^:async ready [] (await (identity 1)))")]
    (is (re-find #"with-redefs-fn" rewritten))
    (is (re-find #"\^:async \(fn \[\]" rewritten))
    (is (re-find #"cljs.test/async done#" rewritten))
    (is (re-find #"\^:async \(fn \[\]" rewritten))
    (is (re-find #"\(defn \^:async ready" rewritten))))

(deftest verdict-keeps-the-baseline-failures
  (let [baseline {:status :assertion-killed :tests 11 :assertions 62 :failures 3 :errors 0}]
    (is (= :survived (campaign/verdict baseline (assoc baseline :status :assertion-killed))))
    (is (= :killed (campaign/verdict baseline (assoc baseline :failures 4))))
    (is (= :killed (campaign/verdict baseline (assoc baseline :errors 1))))
    (is (= :error (campaign/verdict baseline {:status :load-error :tests 0 :assertions 0
                                              :failures 0 :errors 0})))
    (is (= :timeout (campaign/verdict baseline {:status :timeout :tests 0 :assertions 0
                                                :failures 0 :errors 0})))))
