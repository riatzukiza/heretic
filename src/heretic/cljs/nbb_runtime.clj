(ns heretic.cljs.nbb-runtime
  "Materialize one nbb classpath for an Axxium checkout.

   Objects: the Axxium source tree, the Maven sources nbb can read, and the
   npm tree those sources require. The morphism prepare! copies sources,
   rewrites shadow libspecs, and installs node_modules once per checkout.
   Workers then share that tree; each mutant adds only an overlay file."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [heretic.cljs.nbb-sources :as sources])
  (:import [java.util.zip ZipFile]))

(def library-deps
  "The ClojureScript libraries Axxium loads. Clojure and the Closure compiler
   stay off this classpath; nbb already supplies its own core."
  {'metosin/malli {:mvn/version "0.16.4"}
   'funcool/promesa {:mvn/version "11.0.678"}
   'com.github.seancorfield/honeysql {:mvn/version "2.7.1368"}
   'org.clojure/test.check {:mvn/version "1.1.1"}
   'borkdude/edamame {:mvn/version "1.6.42"}})

(def ^:private skip-jars
  #"/(clojure|clojurescript|shadow-cljs|closure-compiler|guava)-[^/]+\.jar$")

(def ^:private skip-entries
  #{"clojure/core.clj" "clojure/core.cljc" "cljs/core.cljs" "cljs/core.cljc"})

(defn- project-root []
  (let [dir (io/file (or (System/getenv "HERETIC_PROJECT_DIR") (System/getProperty "user.dir")))]
    (if (.exists (io/file dir "scripts/nbb-runner.cljs"))
      dir
      (io/file dir "implementation/heretic"))))

(defn- patch-dep [name text]
  (cond
    (= name "clojure/test/check/rose_tree.cljc")
    (str/replace text ":cljs IIndexed" ":cljs heretic.cljs.nbb-prelude/IIndexed")
    :else text))

(defn- unzip-sources! [jar dest]
  (with-open [zip (ZipFile. (io/file jar))]
    (doseq [entry (enumeration-seq (.entries zip))
            :let [name (.getName entry)]
            :when (and (not (.isDirectory entry))
                       (re-find #"\.(?:clj|cljc|cljs)$" name)
                       (not (str/includes? name ".."))
                       (not (skip-entries name)))]
      (let [out (io/file dest name)
            text (patch-dep name (slurp (.getInputStream zip entry)))]
        (io/make-parents out)
        (spit out text)))))

(defn- dependency-sources! [dest]
  (let [{:keys [exit out err]} (shell/sh "clojure" "-Spath" "-Sdeps" (pr-str {:deps library-deps}))]
    (when-not (zero? exit)
      (throw (ex-info "Unable to resolve nbb library sources" {:exit exit :err err})))
    (doseq [path (str/split (str/trim out) #":")
            :when (and (str/ends-with? path ".jar")
                       (not (re-find skip-jars path)))]
      (unzip-sources! path dest))
    dest))

(defn- rewrite-tree! [source-root dest]
  (doseq [file (file-seq (io/file source-root))
          :when (and (.isFile file) (re-find #"\.(?:cljs|cljc)$" (.getName file)))]
    (let [rel (.relativize (.toPath (io/file source-root)) (.toPath file))
          out (io/file dest (str rel))]
      (io/make-parents out)
      (spit out (sources/rewrite-source (slurp file)))))
  dest)

(defn- ensure-modules! [axxium-root dest]
  (let [modules (io/file dest "node_modules")
        existing (io/file axxium-root "node_modules")]
    (cond
      (.exists (io/file modules "fastify")) modules
      (.exists (io/file existing "fastify"))
      (do (java.nio.file.Files/createSymbolicLink
           (.toPath modules) (.toPath existing)
           (make-array java.nio.file.attribute.FileAttribute 0))
          modules)
      :else
      (let [{:keys [exit err]} (shell/sh "npm" "ci" "--ignore-scripts" :dir (str dest))]
        (when-not (zero? exit)
          (throw (ex-info "npm ci failed for the nbb worker tree" {:exit exit :err err})))
        modules))))

(defn- copy-manifests! [axxium-root dest]
  (doseq [name ["package.json" "package-lock.json"]]
    (let [from (io/file axxium-root name)]
      (when (.exists from)
        (io/copy from (io/file dest name))))))

(defn prepare!
  "Return the shared worker inputs for one Axxium checkout.
   The returned classpath is absolute. `directory` is the checkout, so tests
   that open `resources/...` resolve there."
  [axxium-root]
  (let [root (io/file axxium-root)
        project (project-root)
        base (io/file project "target/nbb-axxium" (-> root .getCanonicalPath hash str))
        deps (io/file base "deps")
        sources (io/file base "src")
        modules (io/file base "node_modules")]
    (when-not (.exists (io/file deps "malli/core.cljc"))
      (dependency-sources! deps))
    (rewrite-tree! (io/file root "src/cljs") sources)
    (copy-manifests! root base)
    (let [modules (ensure-modules! root base)
          classpath (str/join ":" (map #(.getAbsolutePath %)
                                       [sources deps (io/file project "src")]))]
      {:classpath classpath
       :node-path (.getAbsolutePath modules)
       :directory (.getAbsolutePath root)
       :sources (.getAbsolutePath sources)
       :project (.getAbsolutePath project)
       :runner (.getAbsolutePath (io/file project "scripts/nbb-runner.cljs"))
       :nbb (.getAbsolutePath (io/file project "node_modules/nbb/cli.js"))
       :node (or (System/getenv "HERETIC_NODE") "node")})))

(defn command
  "nbb command for one classpath. `overlay` is an optional extra root."
  [runtime overlay]
  (let [cp (if overlay
             (str (.getAbsolutePath (io/file overlay)) ":" (:classpath runtime))
             (:classpath runtime))]
    [(:node runtime) (:nbb runtime) "-cp" cp (:runner runtime)]))

(defn env [runtime]
  {"NODE_PATH" (:node-path runtime)})
