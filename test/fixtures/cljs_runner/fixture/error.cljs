(ns fixture.error (:require [cljs.test :refer [deftest is]]))
(deftest erroring (is (throw (js/Error. "boom"))))
