(ns heretic.cljs.compatibility-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [heretic.cljs.compatibility :as compatibility]))

(defn- temp-root []
  (.toFile (java.nio.file.Files/createTempDirectory "heretic-compat" (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest scan-classifies-reader-gaps
  (let [root (temp-root)
        write (fn [rel text]
                (let [file (io/file root rel)]
                  (.mkdirs (.getParentFile file))
                  (spit file text)))]
    (write "src/ok.cljs" "(defn f [x] (+ x 1))\n")
    (write "src/js.cljs" "(def o #js {:n (+ 1 2)})\n")
    (write "src/bad.cljs" "(def x #foo 1)\n")
    (let [report (compatibility/scan root)
          by-path (into {} (map (juxt :path identity) (:files report)))]
      (is (= :heretic.cljs/compatibility-v1 (:schema report)))
      (is (nil? (:commit-sha report)))
      (is (= {:files 3 :ok 2 :parse-error 1 :dispatch 1 :regex 0 :other 0}
             (dissoc (:counts report) :candidates)))
      (is (pos? (get-in report [:counts :candidates])))
      (is (= :ok (:status (by-path "src/js.cljs"))))
      (is (pos? (:candidates (by-path "src/js.cljs"))))
      (is (= :dispatch (:gap (by-path "src/bad.cljs"))))
      (is (= 64 (count (:source-sha256 (by-path "src/ok.cljs"))))))))

(def node (or (System/getenv "HERETIC_NODE") "node"))
(def cli (or (System/getenv "HERETIC_NBB_CLI") "node_modules/nbb/cli.js"))
(def project-dir (or (System/getenv "HERETIC_PROJECT_DIR")
                     (if (.exists (java.io.File. "scripts/nbb-runner.cljs")) "." "implementation/heretic")))

(deftest probe-keeps-the-worker-flag
  (let [log-dir (.toString (java.nio.file.Files/createTempDirectory
                            "heretic-probe" (make-array java.nio.file.attribute.FileAttribute 0)))
        row (compatibility/probe-namespace!
             {:command [node cli "-cp" "src:test/fixtures/cljs_runner" "scripts/nbb-runner.cljs"]
              :directory project-dir
              :timeout-ms 8000
              :request-timeout-ms 2000
              :log-dir log-dir
              :namespace 'fixture.pass})]
    (is (= :survived (:status row)) row)
    (is (= 1 (:tests row)))
    (is (false? (:qualification-eligible? row)))
    (is (= :direct-child (:containment row)))
    (is (= :observed (get-in row [:runtime :availability])))))
