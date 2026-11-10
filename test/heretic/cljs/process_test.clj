(ns heretic.cljs.process-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.java.shell :as shell]
            [heretic.cljs.process :as process]
            [heretic.cljs.protocol :as protocol]))

(def request {:schema :heretic.cljs/request-v1 :test-namespaces ['fixture.pass] :timeout-ms 500 :expected-tests 1})
(def good {:schema :heretic.cljs/result-v1 :backend :nbb :status :assertion-killed
           :completed? true :tests 1 :assertions 1 :failures 1 :errors 0
           :test-namespaces ['fixture.pass] :qualification-eligible? false})
(def runtime-header
  "HERETIC_CLJS_RUNTIME {:schema :heretic.cljs/runtime-v1 :node-version \"v22.23.3\" :nbb-version \"1.4.207\"}\n")
(defn output [m] (str runtime-header "HERETIC_CLJS_RESULT " (pr-str m) "\n"))
(defn parse [stdout exit timeout?] (protocol/validate-output request stdout exit timeout?))
(deftest output-validation
  (is (= :assertion-killed (:status (parse (output good) 0 false))))
  (doseq [out ["" (str (output good) (output good)) (str (output good) "later\n")
               (str "HERETIC_CLJS_RESULT " (pr-str good) " {}\n")
               "HERETIC_CLJS_RESULT {not edn\n"
               (output (assoc good :schema :other))
               (output (assoc good :test-namespaces ['other]))
               (output (assoc good :tests 2))
               (output (assoc good :failures -1))
               (output (assoc good :assertions 0))
               (output (assoc good :qualification-eligible? true))
               (output (assoc good :completed? false))
               (output (assoc good :status :survived))]]
    (is (= :invalid-test-output (:status (parse out 0 false))) out))
  (is (= :invalid-test-output (:status (parse (str "HERETIC_CLJS_RESULT " (pr-str good) "\n") 0 false))))
  (is (= :invalid-test-output (:status (parse (str runtime-header (output good)) 0 false))))
  (is (= :invalid-test-output (:status (parse (str (str/replace runtime-header "v22.23.3" "v24.0.0") (output good)) 0 false))))
  (is (= :invalid-test-output (:status (parse (str "HERETIC_CLJS_RUNTIME bad\n" (subs (output good) (count runtime-header))) 0 false))))
  (is (= :invalid-test-output (:status (parse (output good) 2 false))))
  (is (= :timeout (:status (parse (output good) 0 true))))
  (is (= :test-error (:status (parse (output (assoc good :status :test-error :errors 1 :assertions 2)) 0 false)))))

(def node (or (System/getenv "HERETIC_NODE") "node"))
(def cli (or (System/getenv "HERETIC_NBB_CLI") "node_modules/nbb/cli.js"))
(def project-dir (or (System/getenv "HERETIC_PROJECT_DIR") (if (.exists (java.io.File. "scripts/nbb-runner.cljs")) "." "implementation/heretic")))
(defn logs [] (.toString (java.nio.file.Files/createTempDirectory "heretic-parent-test" (make-array java.nio.file.attribute.FileAttribute 0))))
(defn invoke [command req timeout] (process/run-worker! {:command command :directory project-dir :request req :timeout-ms timeout :log-dir (logs)}))
(deftest process-boundary
  (let [r (invoke [node "-e" "while(true){}"] request 300)]
    (is (= :timeout (get-in r [:result :status])))
    (is (true? (get-in r [:execution :timed-out?])))
    (is (< (get-in r [:execution :elapsed-ms]) 3000))
    (is (= :unknown (get-in r [:execution :containment]))))
  (let [r (invoke [node "-e" (str "console.log(" (pr-str (str "HERETIC_CLJS_RESULT " (pr-str good))) "); while(true){} ")] request 300)]
    (is (= :timeout (get-in r [:result :status])))
    (is (true? (get-in r [:execution :timed-out?]))))
  (doseq [[ns expected] [['fixture.pass :survived] ['fixture.fail :assertion-killed] ['fixture.error :test-error]]]
    (let [r (invoke [node cli "-cp" "src:test/fixtures/cljs_runner" "scripts/nbb-runner.cljs"]
                    (assoc request :test-namespaces [ns]) 4000)]
      (is (= expected (get-in r [:result :status])) (pr-str r))
      (is (= 0 (get-in r [:execution :exit-code])))
      (is (string? (get-in r [:execution :stdout-path])))))
  (let [r (invoke [node "-e" "process.exit(7)"] request 2000)]
    (is (= :invalid-test-output (get-in r [:result :status])))
    (is (= 7 (get-in r [:execution :exit-code]))))
  (let [r (invoke [node "-e" "process.exit(0)"] (assoc request :timeout-ms 0) 2000)]
    (is (= :invalid-test-output (get-in r [:result :status])))
    (is (false? (get-in r [:execution :spawned?])))))

(deftest interrupted-started-child-is-recorded
  (let [dir (logs)
        signal (str dir "/started")
        done (promise)
        code (str "require('fs').writeFileSync(" (pr-str signal) ", String(process.pid)); while(true){}")
        thread (Thread. #(deliver done (process/run-worker! {:command [node "-e" code]
                                                            :directory project-dir :request request
                                                            :timeout-ms 10000 :log-dir dir})))]
    (.start thread)
    (loop [n 0]
      (when (and (not (.exists (java.io.File. signal))) (< n 300))
        (Thread/sleep 10)
        (recur (inc n))))
    (is (.exists (java.io.File. signal)) "Child must start before interruption")
    (.interrupt thread)
    (let [r (deref done 3000 ::timeout)]
      (is (not= ::timeout r))
      (is (true? (get-in r [:execution :spawned?])))
      (is (some? (get-in r [:execution :exit-code])))
      (let [handle (java.lang.ProcessHandle/of (Long/parseLong (slurp signal)))]
        (is (or (not (.isPresent handle)) (not (.isAlive (.get handle))))))
      (is (= :invalid-test-output (get-in r [:result :status])))
      (is (= :unknown (get-in r [:execution :containment]))))))

(deftest runtime-observation-validation
  (let [observed (protocol/runtime-observation runtime-header)]
    (is (= :observed (:availability observed)))
    (is (= :worker-reported (:provenance observed)))
    (is (= "v22.23.3" (:node-version observed)))
    (is (= "1.4.207" (:nbb-version observed))))
  (doseq [s ["" "HERETIC_CLJS_RUNTIME {oops\n"
             (str runtime-header runtime-header)
             (str runtime-header (str/replace runtime-header "v22.23.3" "v24.0.0"))
             "HERETIC_CLJS_RUNTIME {:schema :heretic.cljs/runtime-v1 :node-version nil :nbb-version \"1.4.207\"}\n"
             (str (str/trim runtime-header) " {}\n")]]
    (let [m (protocol/runtime-observation s)]
      (is (= :unavailable (:availability m)) s)
      (is (nil? (:node-version m)))
      (is (nil? (:nbb-version m))))))

(deftest actual-worker-runtime-observation
  (let [r (invoke [node cli "-cp" "src:test/fixtures/cljs_runner" "scripts/nbb-runner.cljs"] request 4000)
        reported (get-in r [:execution :runtime])
        node-probe (shell/sh node "--version" :dir project-dir)
        nbb-probe (shell/sh node cli "-e" "(require '[nbb.core :as n]) (println (n/version))" :dir project-dir)]
    (is (= 0 (:exit node-probe)))
    (is (= 0 (:exit nbb-probe)))
    (is (= :observed (:availability reported)))
    (is (= :worker-reported (:provenance reported)))
    (is (= (str/trim (:out node-probe)) (:node-version reported)))
    (is (= (str/trim (:out nbb-probe)) (:nbb-version reported)))
    (is (= :survived (get-in r [:result :status]))))
  (let [r (invoke [node "-e" "while(true){}"] request 300)]
    (is (= :unavailable (get-in r [:execution :runtime :availability])))
    (is (= :timeout (get-in r [:result :status]))))
  (let [script (str "console.log(" (pr-str (str/trim runtime-header)) "); while(true){}")
        r (invoke [node "-e" script] request 300)]
    (is (= :observed (get-in r [:execution :runtime :availability])))
    (is (= :worker-reported (get-in r [:execution :runtime :provenance])))
    (is (= :timeout (get-in r [:result :status])))))
