(ns heretic.extern.nbb
  (:require [nbb.core :as nbb]
            ["node:fs" :as fs]))

(defn emit-runtime []
  (.writeSync fs 1 (str "HERETIC_CLJS_RUNTIME "
                        (pr-str {:schema :heretic.cljs/runtime-v1
                                 :node-version (.-version js/process)
                                 :nbb-version (nbb/version)}) "\n")))

(defn read-file [path] (.readFileSync fs path "utf8"))
(defn load-code [code] (nbb/load-string code))
(defn later [f ms] (js/setTimeout f ms))
(defn on-async-error [f]
  (.on js/process "unhandledRejection" f)
  (.on js/process "uncaughtException" f))
(defn cancel [timer] (js/clearTimeout timer))
;; A fresh worker has one terminal result. Synchronous output and a clean exit
;; prevent late async test callbacks from printing after the result. The parent
;; separately handles processes that never yield to the timer (exit 0 = emitted).
(defn emit [result]
  (.writeSync fs 1 (str "HERETIC_CLJS_RESULT " (pr-str result) "\n"))
  (.exit js/process 0))
