(ns heretic.extern.process
  (:require [clojure.java.io :as io])
  (:import [java.util.concurrent TimeUnit]))

(defn- elapsed-ms [start]
  (quot (- (System/nanoTime) start) 1000000))

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

(defn- stop-child! [p]
  (when (.isAlive p)
    (.destroy p)
    (when-not (.waitFor p 250 TimeUnit/MILLISECONDS)
      (.destroyForcibly p)
      (.waitFor p 500 TimeUnit/MILLISECONDS))))

(defn- safe-slurp [path]
  (try (slurp path) (catch Exception _ nil)))

(defn execute!
  "Run and capture one direct child, preserving start evidence on failure."
  [command directory timeout-ms paths]
  (let [start (System/nanoTime)
        pb (ProcessBuilder. ^java.util.List command)]
    (when directory (.directory pb (io/file directory)))
    (.redirectOutput pb (io/file (:stdout-path paths)))
    (.redirectError pb (io/file (:stderr-path paths)))
    (let [p (.start pb)]
      (try
        (let [completed? (.waitFor p (long timeout-ms) TimeUnit/MILLISECONDS)]
          (when-not completed? (stop-child! p))
          {:spawned? true
           :exit-code (when-not (.isAlive p) (.exitValue p))
           :timed-out? (not completed?)
           :elapsed-ms (elapsed-ms start)
           :stdout (slurp (:stdout-path paths))
           :stderr (slurp (:stderr-path paths))})
        (catch Exception e
          ;; InterruptedException clears the flag. Clean up before restoring it.
          (let [interrupted? (instance? InterruptedException e)]
            (try
              (stop-child! p)
              (catch InterruptedException _ (.destroyForcibly p)))
            (let [execution {:spawned? true
                             :exit-code (when-not (.isAlive p) (.exitValue p))
                             :timed-out? false
                             :elapsed-ms (elapsed-ms start)
                             :stdout (safe-slurp (:stdout-path paths))
                             :stderr (safe-slurp (:stderr-path paths))}]
              (when interrupted? (.interrupt (Thread/currentThread)))
              (throw (ex-info "Worker execution failed after start" {:execution execution} e)))))))))
