(ns heretic.cljs.compatibility
  "Read an Axxium-shaped source tree and classify what this backend can discover.

   scan is the discovery morphism over a directory: each .cljs/.cljc file becomes
   a status, a reader-gap class, and its candidates. probe-namespace! is the
   execution morphism for one test namespace. Neither morphism sets
   :qualification-eligible? true."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [heretic.cljs.discovery :as discovery]
            [heretic.cljs.process :as process])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(defn- sha256 [s]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes ^String s StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and 0xff %)) digest))))

(defn- git-rev [dir rev]
  (let [{:keys [exit out]} (shell/sh "git" "rev-parse" rev :dir (str dir))]
    (let [text (str/trim (or out ""))]
      (when (and (zero? exit) (re-matches #"[0-9a-f]{40}" text))
        text))))

(defn git-identity
  "Commit and tree SHAs of dir, or nils when dir is not a Git checkout."
  [dir]
  {:commit-sha (git-rev dir "HEAD")
   :tree-sha (git-rev dir "HEAD^{tree}")})

(defn- source-files [root]
  (->> (file-seq (io/file root))
       (filter #(and (.isFile %) (re-find #"\.(cljs|cljc)$" (.getName %))))
       (map (fn [file]
              (let [root-path (.toPath (io/file root))
                    rel (-> root-path (.relativize (.toPath file)) str)]
                (str/replace rel #"\\" "/"))))
       (remove #(or (str/starts-with? % "/")
                    (some #{"." ".."} (str/split % #"/"))))
       sort
       vec))

(defn gap [message]
  (cond
    (nil? message) nil
    (str/includes? message "reader dispatch") :dispatch
    (str/includes? message "regex") :regex
    (str/includes? message "#js") :js
    :else :other))

(defn scan
  "Discover every ClojureScript file under root.
   :candidates is the count. Full candidate maps stay out of this summary;
   discover-source remains the way to obtain them."
  [root]
  (let [root-file (io/file root)
        files (mapv (fn [path]
                      (let [source (slurp (io/file root-file path))
                            result (discovery/discover-source {:path path :source source})]
                        {:path path
                         :source-sha256 (or (:source-sha256 result) (sha256 source))
                         :status (:status result)
                         :message (:message result)
                         :gap (gap (:message result))
                         :candidates (count (:candidates result))}))
                    (source-files root-file))
        identity (git-identity root-file)]
    {:schema :heretic.cljs/compatibility-v1
     :commit-sha (:commit-sha identity)
     :tree-sha (:tree-sha identity)
     :files files
     :counts {:files (count files)
              :ok (count (filter #(= :ok (:status %)) files))
              :parse-error (count (filter #(= :parse-error (:status %)) files))
              :dispatch (count (filter #(= :dispatch (:gap %)) files))
              :regex (count (filter #(= :regex (:gap %)) files))
              :other (count (filter #(= :other (:gap %)) files))
              :candidates (reduce + 0 (map :candidates files))}}))

(defn probe-namespace!
  "Run one test namespace in a fresh worker. The returned flag is the worker's."
  [{:keys [command directory timeout-ms log-dir namespace request-timeout-ms]}]
  (let [request {:schema :heretic.cljs/request-v1
                 :test-namespaces [namespace]
                 :timeout-ms (or request-timeout-ms 2000)
                 :expected-tests nil}
        response (process/run-worker! {:command command
                                       :directory directory
                                       :timeout-ms (or timeout-ms 8000)
                                       :log-dir log-dir
                                       :request request})
        result (:result response)]
    {:namespace namespace
     :status (:status result)
     :tests (:tests result)
     :assertions (:assertions result)
     :failures (:failures result)
     :errors (:errors result)
     :message (:message result)
     :qualification-eligible? (:qualification-eligible? result)
     :containment (get-in response [:execution :containment])
     :runtime (get-in response [:execution :runtime])}))
