(ns buck-autodeps
  (:require [clojure.java.classpath :as classpath]
            [clojure.tools.namespace.find :as find]
            [clojure.tools.namespace.parse :as parse]
            [clojure.tools.namespace.file :as file]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.set :as set]
            [cheshire.core :refer [generate-stream create-pretty-printer default-pretty-print-options]])
  (:import (java.util.jar JarFile))
  (:gen-class :name BuckAutodeps))

(defn relativize
  [root dir]
  (let [root (.getAbsolutePath (io/file root))
        root (if (not (.endsWith root "/")) (str root "/") root)
        dir  (.getAbsolutePath (io/file dir))]
    (if-not (.startsWith dir root)
      (throw (RuntimeException. (str dir " doesn't start with " root)))
      (.substring dir (.length root)))))

(defn strip-suffix [st suf]
  (if (string/ends-with? st suf)
    (subs st 0 (- (count st) (count suf)))
    st))

(defn target-to-buck-file
  [root t]
  (assert (string/starts-with? t "//"))
  (io/file root (second (re-matches #"^//([^:]+):.*$" t)) "BUCK.autogenerated"))

(defn buck-target-comparator [t1 t2]
  (case [(first t1) (first t2)]
    ([\: \:] [\/ \/])
    (compare t1 t2)
    [\: \/]
    -1
    [\/ \:]
    1))

(defn symlink-tree-path
  [ns file]
  (let [extension (second (re-matches #"^.*(\.[^\.]+)$" file))]
    (-> ns
        name
        (string/replace "-" "_")
        (string/replace "." "/")
        (str extension))))

(defn to-file-target
  [root file]
  (str "//" (relativize root (.getParent file)) ":"
       (string/replace
        (string/replace-first (.getName file) #"\.[^.]+$" "")
        "_" "-")))

(defn read-deps-cljs
  [jar-file file]
  (read-string (slurp (str "jar:" (.toURL jar-file) "!/" file))))

;; TODO(eric): Extend this function to support implicit macro loading.
(defn clj-deps-from-cljs-form
  [cljs-ns]
  (vec (concat
        (->> cljs-ns
             (filter #(and
                       (list? %)
                       (contains? #{:require-macros :use-macros} (first %))))
             (mapcat rest)
             (map #(if (coll? %)
                     (first %)
                     %)))
        (->> cljs-ns
             (filter #(and
                       (list? %)
                       (= :require (first %))))
             (mapcat rest)
             (filter #(and (coll? %) (some #{:refer-macros :include-macros} %)))
             (map #(if (coll? %)
                     (first %)
                     %))))))

(defn- make-ns-graph-map-value [jar-file file reader-fn target target-cljs]
  (if (= "deps.cljs" (.getName file))
    (->> (:foreign-libs (read-deps-cljs jar-file file))
         (mapcat (fn [{:keys [provides requires]}]
                   (map #(do {(symbol %) {:cljs-deps requires :file-cljs file :target-cljs target}}) provides)))
         (into {}))
    (let [clj-ns (when-not (string/ends-with? (.getName file) ".cljs")
                   (reader-fn parse/clj-read-opts))
          cljs-ns (when-not (string/ends-with? (.getName file) ".clj")
                    (reader-fn parse/cljs-read-opts))
          ns-name (parse/name-from-ns-decl (or clj-ns cljs-ns))]
      (when (or clj-ns cljs-ns)
        {ns-name
         {:clj-deps (when clj-ns (parse/deps-from-ns-decl clj-ns))
          :cljs-deps (when cljs-ns (parse/deps-from-ns-decl cljs-ns))
          :cljs-clj-deps (when cljs-ns
                           (cond-> (clj-deps-from-cljs-form cljs-ns)
                             ;; Slight hack: cljc files compiled under cljs always depend on the clojure namespace of themselves
                             (string/ends-with? (.getName file) ".cljc")
                             (conj ns-name)))
          :file-clj (when-not (string/ends-with? (.getName file) ".cljs") file)
          :file-cljs (when-not (string/ends-with? (.getName file) ".clj") file)
          :target-clj (when-not (string/ends-with? (.getName file) ".cljs") target)
          :target-cljs (when-not (string/ends-with? (.getName file) ".clj") target-cljs)
          :clj-test (some #(= 'clojure.test %) (tree-seq coll? identity clj-ns))
          :cljs-test (some #(= 'cljs.test %) (tree-seq coll? identity cljs-ns))}}))))

(defn read-graph-from-file
  [root file-name]
  (let [root (io/file root) file-name (io/file file-name) target (to-file-target root file-name) target-cjs (str (to-file-target root file-name) "_cljs")]
    (make-ns-graph-map-value nil file-name (partial file/read-file-ns-decl file-name) target target-cjs)))

(defn get-ns-graph-in-dir
  [root paths]
  (->> paths
       (map #(io/file root %))
       (mapcat (fn [v]
                 (->> (file-seq v)
                      (filter #(or (file/clojure-file? %) (file/clojurescript-file? %)))
                      (keep #(read-graph-from-file root %)))))
       (into {})))

(defn to-jar-target [root f]
  (str "//" (relativize root (.getParent f)) ":"
       (strip-suffix (second (re-matches #"^([^0-9]+).*$" (.getName f))) "-")))

(def combined-extensions (set (concat file/clojure-extensions file/clojurescript-extensions)))

(defn read-ns-decl-from-jarfile-entry [jar-file entry read-opts]
  (find/read-ns-decl-from-jarfile-entry jar-file entry {:read-opts read-opts}))

(defn get-ns-graph-in-jar-dir
  [root paths]
  (->> paths
       (map #(io/file root %))
       (mapcat file-seq)
       (filter classpath/jar-file?)
       (mapcat (fn [f]
                 (let [target (to-jar-target root f)
                       jar-file (JarFile. f)]
                   (->> (classpath/filenames-in-jar jar-file)
                        (filter (fn [f] (some #(string/ends-with? f %) combined-extensions)))
                        (keep #(make-ns-graph-map-value f (io/file %) (partial read-ns-decl-from-jarfile-entry jar-file %) target target))))))
       (apply merge-with (fn [l r] (merge-with #(or %1 %2) l r)))))

(def cli-opts
  [["-I" "--include PATH" "Extra dependencies to scan (but not write)"
    :default ["third_party"]
    :assoc-fn (fn [m k v] (update m k conj v))]
   [nil "--ns NS=TARGET" "Specify a target for a clojure namespace manually."
    :default {'datomic.api {:target-clj "//third_party/datomic:datomic-pro"}
              'datomic.db {:target-clj "//third_party/datomic:datomic-pro"}}
    :assoc-fn (fn [m k v]
                (let [[ns target] (string/split v #"=" 2)]
                  (update m k assoc (symbol ns) {:target-clj target})))]
   [nil "--ns-cljs NS=TARGET" "Specify a target for a clojurescript namespace manually."
    :default {}
    :assoc-fn (fn [m k v]
                (let [[ns target] (string/split v #"=" 2)]
                  (update m k assoc (symbol ns) {:target-cljs target})))]
   ["-h" "--help" "Prints help"]])

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline errors)))

(defn exit [status msg]
  (.println System/err msg)
  (System/exit status))

(defn sorted-deps [cwd total-ns-graph file deps target]
  (apply sorted-set-by buck-target-comparator
         (filter some?
                 (for [dep deps
                       :let [dep-target (target (get total-ns-graph dep))]]
                   (do
                     (if (some? dep-target)
                       (if (= (target-to-buck-file cwd dep-target) file)
                         (string/replace dep-target #"^[^:]+" "")
                         dep-target)
                       (if-not (string/starts-with? dep "goog.")
                         (binding [*out* *err*]
                           (.println System/err (str "Could not find target for namespace " dep
                                                     " (used in file " (relativize cwd file) ")"))))))))))

(defn -main
  [& args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-opts)
        arguments (or (not-empty arguments) ["src/"])]
    (cond
      (:help options) (exit 0 summary)
      errors (exit 1 (error-msg errors)))
    (let [cwd (.getAbsolutePath (io/file "."))
          to-write-ns-graph (get-ns-graph-in-dir cwd arguments)
          total-ns-graph (merge to-write-ns-graph
                                (:ns options)
                                (get-ns-graph-in-jar-dir cwd (:include options)))
          files-to-write (->> to-write-ns-graph
                              (map #(do {(target-to-buck-file cwd
                                                              (or (:target-clj (second %)) (:target-cljs (second %))))
                                         (sorted-set (first %))}))
                              (apply merge-with set/union))
          pretty-printer (create-pretty-printer
                          (assoc default-pretty-print-options
                                 :indent-arrays? true))]
      (doseq [[file nses] files-to-write :let [results (atom {})]]
        (doseq [ns nses
                :let [{:keys [file-clj file-cljs clj-deps cljs-deps cljs-clj-deps target-clj target-cljs clj-test cljs-test]} (get total-ns-graph ns)
                      target-name-clj (when target-clj (string/replace target-clj  #"^[^:]+:" ""))
                      target-name-cljs (when target-cljs (string/replace target-cljs #"^[^:]+:" ""))
                      file-name-clj (when file-clj (.getName file-clj))
                      file-name-cljs (when file-cljs (.getName file-cljs))
                      clj-deps (when clj-deps (sort clj-deps))
                      clj-dep-targets (when clj-deps (sorted-deps cwd total-ns-graph file clj-deps :target-clj))
                      cljs-deps (when cljs-deps
                                  (sort cljs-deps))
                      cljs-dep-targets (when cljs-deps (sorted-deps cwd total-ns-graph file cljs-deps :target-cljs))
                      cljs-clj-deps (when cljs-clj-deps
                                      (sort cljs-clj-deps))
                      cljs-clj-dep-targets (when cljs-clj-deps (sorted-deps cwd total-ns-graph file cljs-clj-deps :target-clj))]]
          (when clj-deps
            (swap! results assoc target-name-clj {:$type "clojure_library" :srcs {(symlink-tree-path ns file-name-clj) file-name-clj}
                                                  :deps clj-dep-targets :visibility ["PUBLIC"]}))
          (when cljs-deps
            (swap! results assoc target-name-cljs {:$type "cljs_library" :srcs {(symlink-tree-path ns file-name-cljs) file-name-cljs}
                                                   :deps cljs-dep-targets :compile_deps cljs-clj-dep-targets
                                                   :visibility ["PUBLIC"]}))
          (when clj-test
            (swap! results assoc-in ["test_run" :$type] "clojure_test")
            (swap! results update-in ["test_run" :libraries] #(conj (or % []) (str ":" target-name-clj))))
          (when cljs-test
            (swap! results assoc (str target-name-cljs "_run") {:$type "cljs_test" :library (str ":" target-name-cljs) :namespaces [ns]})))
        (generate-stream @results (clojure.java.io/writer file) {:pretty pretty-printer})))))