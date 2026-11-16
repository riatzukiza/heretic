(ns fixture.custom-report
  (:require [cljs.test :as t :refer [deftest is]]))

;; Replaces the worker's end-run method while this namespace loads.
(defmethod t/report [::t/default :end-run-tests] [_event])

(deftest passing
  (is (= 1 1)))
