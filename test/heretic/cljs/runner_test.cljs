(ns heretic.cljs.runner-test
  (:require [cljs.test :as test :refer [deftest is run-tests]]
            [cljs.reader :as reader]
            [heretic.cljs.outcome :as outcome]
            ["node:child_process" :as child]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def cli (or (.-HERETIC_NBB_CLI js/process.env) "node_modules/nbb/cli.js"))
(def marker "HERETIC_CLJS_RESULT ")
(defn invoke [request]
  (let [dir (.mkdtempSync fs (.join path (.tmpdir os) "heretic-runner-test-"))
        file (.join path dir "request.edn")]
    (try
      (.writeFileSync fs file (if (string? request) request (pr-str request)))
      (let [p (.spawnSync child (.-execPath js/process)
                          (clj->js [cli "-cp" "src:test/fixtures/cljs_runner" "scripts/nbb-runner.cljs" file])
                          #js {:encoding "utf8" :timeout 4000})
            output-lines (remove empty? (.split (.-stdout p) "\n"))
            lines (filter #(.startsWith % marker) output-lines)]
        (when-not (and (= 0 (.-status p)) (= 1 (count lines)) (= (last output-lines) (first lines)))
          (throw (js/Error. (str "Expected exactly one marker: " (.-stdout p) " stderr: " (.-stderr p)))))
        (reader/read-string (subs (first lines) (count marker))))
      (finally (.rmSync fs dir #js {:recursive true :force true})))))
(defn request [ns] {:schema :heretic.cljs/request-v1 :test-namespaces [ns] :timeout-ms 300 :expected-tests nil})
(deftest worker-contract
  (doseq [[fixture status] [['fixture.pass :survived] ['fixture.fail :assertion-killed]
                             ['fixture.error :test-error] ['fixture.mixed :test-error]
                             ['fixture.async-pass :survived] ['fixture.async-reject :test-error] ['fixture.async-never :timeout] ['fixture.async-late :timeout]
                             ['fixture.missing :load-error] ['fixture.empty :invalid-test-output]
                             ['fixture.no-assertions :invalid-test-output]]]
    (let [result (invoke (request fixture))]
      (is (= status (:status result)) (str fixture " " result))
      (is (false? (:qualification-eligible? result)))
      (is (= :heretic.cljs/result-v1 (:schema result)))))
  (is (= :survived (:status (invoke (assoc (request 'fixture.pass) :test-namespaces ['fixture.pass 'fixture.async-pass] :expected-tests 2)))))
  (is (= :invalid-test-output (:status (invoke (assoc (request 'fixture.pass) :expected-tests 2)))))
  (is (= :invalid-test-output (:status (invoke (assoc (request 'fixture.pass) :test-namespaces ['fixture.pass 'fixture.pass])))))
  (is (= :invalid-test-output (:status (invoke (assoc (request 'fixture.pass) :timeout-ms 0)))))
  (is (= :invalid-test-output (:status (invoke (str (pr-str (request 'fixture.pass)) " {}")))))
  (is (= :invalid-test-output (:status (invoke (str (pr-str (request 'fixture.pass)) " trailing")))))
  (is (= :invalid-test-output (:status (invoke (str (pr-str (request 'fixture.pass)) " #_ 42")))))
  (is (= :survived (:status (invoke (request 'fixture.isolation)))))
  (is (= :survived (:status (invoke (request 'fixture.isolation))))))
(deftest malformed-events
  (let [req (request 'fixture.pass)
        result #(outcome/result req %)]
    (is (= :invalid-test-output (:status (result {:kind :completed :completion-count 2 :counters {:test 1 :pass 1 :fail 0 :error 0}}))))
    (is (= :invalid-test-output (:status (result {:kind :completed :completion-count 1 :counters {:test 1 :pass -1 :fail 0 :error 0}}))))
    (is (= :invalid-test-output (:status (result {:kind :completed :completion-count 1 :counters {:test 1 :pass 0 :fail 0 :error 0}}))))
    (is (= :test-error (:status (result {:kind :completed :completion-count 1 :counters {:test 1 :pass 0 :fail 1 :error 1}}))))
    (is (false? (:completed? (result {:kind :timeout}))))))
(defmethod test/report [::test/default :end-run-tests] [event]
  (when (or (pos? (:fail event)) (pos? (:error event)))
    (set! (.-exitCode js/process) 1)))
(run-tests 'heretic.cljs.runner-test)
