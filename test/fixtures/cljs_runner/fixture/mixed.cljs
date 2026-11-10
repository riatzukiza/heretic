(ns fixture.mixed (:require [cljs.test :refer [deftest is]]))
(deftest failing (is (= 1 2)))
(deftest erroring (is (throw (js/Error. "boom"))))
