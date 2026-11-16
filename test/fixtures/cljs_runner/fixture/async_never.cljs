(ns fixture.async-never (:require [cljs.test :refer [deftest async]]))
(deftest never-finishes (async done nil))
