(ns fixture.async-late (:require [cljs.test :refer [deftest is async]]))
(deftest too-late (async done (js/setTimeout #(do (is true) (done)) 500)))
