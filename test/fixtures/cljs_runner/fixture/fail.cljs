(ns fixture.fail (:require [cljs.test :refer [deftest is]]))
(deftest failing (is (= 1 2)))
