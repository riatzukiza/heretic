(ns fixture.isolation (:require [cljs.test :refer [deftest is]]))
(deftest isolated (is (nil? (.-hereticFixtureSentinel js/globalThis))) (set! (.-hereticFixtureSentinel js/globalThis) true))
