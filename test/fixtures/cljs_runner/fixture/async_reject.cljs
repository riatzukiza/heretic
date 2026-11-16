(ns fixture.async-reject (:require [cljs.test :refer [deftest async]]))
(deftest rejected (async done (js/Promise.reject (js/Error. "async rejection"))))
