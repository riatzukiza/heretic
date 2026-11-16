(ns fixture.autorun
  (:require [cljs.test :refer [deftest is run-tests]]))

(deftest passing
  (is (= 1 1)))

;; A load-time run must not satisfy the worker's requested run.
(run-tests)
