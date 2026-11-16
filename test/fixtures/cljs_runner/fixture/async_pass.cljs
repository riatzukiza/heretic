(ns fixture.async-pass (:require [cljs.test :refer [deftest is async]]))
(deftest delayed (async done (js/setTimeout #(do (is true) (done)) 40)))
