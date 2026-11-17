(ns heretic.extern.process
  (:require [clojure.java.io :as io])
  (:import [java.util.concurrent TimeUnit]))

(defn- elapsed-ms [start]
  (quot (- (System/nanoTime) start) 1000000))

(def output-overflow
  "Stdout or stderr value when a capture file exceeds max-captured-bytes.
   The file is left on disk and is not read."
  ::output-overflow)

(def max-captured-bytes
  "Fixed per-stream capture limit. Larger logs stay on disk unread."
  (* 1024 1024))

(defn- capture [path]
  (let [file (io/file path)]
    (if (and (.exists file) (> (.length file) max-captured-bytes))
      output-overflow
      (slurp path))))

(defn prepare!
  "Create unique, retained request and output paths for one invocation."
  [log-dir request]
  (java.nio.file.Files/createDirectories
   (.toPath (io/file log-dir))
   (make-array java.nio.file.attribute.FileAttribute 0))
  (let [prefix (str "worker-" (java.util.UUID/randomUUID))
        request-file (io/file log-dir (str prefix ".request.edn"))
        stdout-file (io/file log-dir (str prefix ".stdout"))
        stderr-file (io/file log-dir (str prefix ".stderr"))]
    (spit request-file (pr-str request))
    {:request-path (.getAbsolutePath request-file)
     :stdout-path (.getAbsolutePath stdout-file)
     :stderr-path (.getAbsolutePath stderr-file)}))

(defn- signal-tree!
  "Signal the child's live descendants, then the child.
   A child that has already exited may have reparented its descendants; those
   are no longer visible. This is best-effort process-tree signaling, not a
   sandbox."
  [p]
  (let [children (fn []
                   (try
                     (vec (.toArray (.descendants (.toHandle p))))
                     (catch Exception _ [])))
        report (try
                 (let [live (children)]
                   (doseq [child live]
                     (try (.destroy child) (catch Exception _)))
                   {:descendant-count (count live)
                    :containment (if (seq live) :descendants-signaled :direct-child)})
                 (catch Exception _
                   {:descendant-count nil :containment :unknown}))]
    (when (.isAlive p)
      (.destroy p)
      (when-not (.waitFor p 250 TimeUnit/MILLISECONDS)
        (doseq [child (children)]
          (try (.destroyForcibly child) (catch Exception _)))
        (.destroyForcibly p)
        (.waitFor p 500 TimeUnit/MILLISECONDS)))
    report))

(defn- safe-slurp [path]
  (try (capture path) (catch Exception _ nil)))

(defn execute!
  "Run and capture one direct child, preserving start evidence on failure.
   `env` is extra environment entries inherited on top of this process."
  ([command directory timeout-ms paths]
   (execute! command directory timeout-ms paths nil))
  ([command directory timeout-ms paths env]
  (let [start (System/nanoTime)
        pb (ProcessBuilder. ^java.util.List command)]
    (doseq [[k v] env]
      (.put (.environment pb) (str k) (str v)))
    (when directory (.directory pb (io/file directory)))
    (.redirectOutput pb (io/file (:stdout-path paths)))
    (.redirectError pb (io/file (:stderr-path paths)))
    (let [p (.start pb)]
      (try
        (let [completed? (.waitFor p (long timeout-ms) TimeUnit/MILLISECONDS)
              report (signal-tree! p)]
          (merge report
                 {:spawned? true
                  :exit-code (when-not (.isAlive p) (.exitValue p))
                  :timed-out? (not completed?)
                  :elapsed-ms (elapsed-ms start)
                  :stdout (capture (:stdout-path paths))
                  :stderr (capture (:stderr-path paths))}))
        (catch Exception e
          ;; InterruptedException clears the flag. Clean up before restoring it.
          (let [interrupted? (instance? InterruptedException e)
                report (try
                         (signal-tree! p)
                         (catch InterruptedException _
                           (.destroyForcibly p)
                           {:descendant-count nil :containment :unknown}))]
            (let [execution (merge report
                                   {:spawned? true
                                    :exit-code (when-not (.isAlive p) (.exitValue p))
                                    :timed-out? false
                                    :elapsed-ms (elapsed-ms start)
                                    :stdout (safe-slurp (:stdout-path paths))
                                    :stderr (safe-slurp (:stderr-path paths))})]
              (when interrupted? (.interrupt (Thread/currentThread)))
              (throw (ex-info "Worker execution failed after start" {:execution execution} e))))))))))
