(require '[cljs.compiler :as comp]
         '[cljs.util :as util]
         '[cljs.compiler.api :as comp-api]
         '[cljs.js-deps :as deps]
         '[cljs.env :as env]
         '[cljs.analyzer :as ana]
         '[cljs.closure :as closure]
         '[clojure.edn :as edn]
         '[clojure.zip :as zip]
         '[clojure.java.io :as io]
         '[clojure.string :as string])

(def start (System/currentTimeMillis))

(defn lg [& args]
  (.println
   *err*
   (apply str
    (- (System/currentTimeMillis) start)
    ": "
    args)))

(defn clean-potential-path
  [v]
  (string/replace-first v #"^.*buck-out/gen/" ""))

(defn mapvals
  "Maps a values of a map to another map"
  [f m]
  (->> m
       (map (fn [[k v]] [k (f v)]))
       (into {})))

(defn read-edn-file
  [& file]
  (let [file (apply io/file file)
        maybe-gzip (fn [s]
                     (if (string/ends-with? (.getName file) ".gz")
                       (java.util.zip.GZIPInputStream. s)
                       s))]
    (with-open [reader (-> file
                           io/input-stream
                           maybe-gzip
                           io/reader
                           java.io.PushbackReader.)]
      (edn/read reader))))

(def namespace-cache-filename "all-namespaces.cache.edn.gz")

(defn merge-deps
  [existing new]
  (merge-with #(-> (concat %1 %2)
                   set
                   vec) existing new))

(defn gather-deps
  [deps]
  (lg "Iterate " (count deps) " deps")
  (let [map-fn (if (> (count deps) 5) pmap map)
        full-deps (->> deps
                       (map #(io/file % "deps.cljs"))
                       (map-fn (fn [f]
                                 (if (.exists f)
                                   (read-edn-file f)
                                   {}))))
        namespaces (->> deps
                        (map #(io/file % namespace-cache-filename))
                        (map-fn (fn [f]
                                  (if (.exists f)
                                    (read-edn-file f)
                                    {}))))
        result [(reduce merge-deps {} full-deps)
                (reduce merge {} namespaces)]]
    (lg "Done with deps")
    result))

(defn clean-edn
  [v]
  (cond
    (map? v)
    (into {} (map (fn [[k v]]
                    (let [v (case k
                              (:line :column :end-line :end-column)
                              0  ;; strip line numbers for better caching.
                              (clean-edn v))]
                      [(clean-edn k) v]))
                  v))
    (seq? v)
    (map clean-edn v)
    (vector? v)
    (mapv clean-edn v)
    (string? v)
    (clean-potential-path v)
    :else
    v))

(defn relativize
  [root dir]
  (let [root (.getAbsolutePath (io/file root))
        root (if (not (.endsWith root "/")) (str root "/") root)
        dir  (.getAbsolutePath (io/file dir))]
    (if-not (.startsWith dir root)
      (throw (RuntimeException. (str dir " doesn't start with " root)))
      (.substring dir (.length root)))))

(defn compile-dir
  [src-dir target compiler-env full-options full-deps]
  (io/make-parents target namespace-cache-filename)

  (let [artifacts (comp-api/compile-root compiler-env (io/file src-dir) (io/file target) full-options)]
    (binding [*out* (->
                     (io/file target "ijavascript.edn.gz")
                     io/output-stream
                     java.util.zip.GZIPOutputStream.
                     io/writer)]
      (->> artifacts
          (mapv (fn [v]
                  (-> v
                      (update :file #(relativize target %))
                      (dissoc :file-name)
                      (update :source-file #(relativize src-dir %)))))
          clean-edn
          (mapv #(do (pr %) (println))))

      (lg "Searching for closure libraries")
      (->> (deps/load-library (.getAbsolutePath (io/file src-dir)))
           (filter (fn [v] (not (-> v
                                    :url
                                    io/file
                                    (.getAbsolutePath)
                                    (.endsWith "/core.aot.js")))))
           (map (fn [v]
                  (let [rel-path (relativize src-dir (:url v))
                        target-file (io/file target rel-path)]
                    (io/make-parents target-file)
                    (io/copy (io/file (:url v)) target-file)
                    (merge v
                           {:file rel-path :url nil :lib-path nil}))))
          (mapv #(do (pr %) (println))))
      (.close *out*)))

  (lg "Copy foreign deps")
  (let [deps-file (io/file src-dir "deps.cljs")
        my-deps (if (.exists deps-file)
                  (read-edn-file deps-file)
                  {})]
    (doseq [{:keys [file-min file]} (:foreign-libs my-deps)]
      (when file-min
        (io/make-parents (io/file target file-min))
        (io/copy (io/file src-dir file-min)
                 (io/file target file-min)))
      (io/make-parents (io/file target file))
      (io/copy (io/file src-dir file)
               (io/file target file)))

    (doseq [file (:externs my-deps)]
      (io/make-parents (io/file target file))
      (io/copy (io/file src-dir file)
               (io/file target file))))

  ;; Dump the analysis cache out for dependent rules.
  (lg "Outputting namespace cache")
  (when-let [namespaces (:cljs.analyzer/namespaces @compiler-env)]
    (binding [*out* (->
                     (io/file target namespace-cache-filename)
                     io/output-stream
                     java.util.zip.GZIPOutputStream.
                     io/writer)]
      (->> namespaces
           ;; For some reason :order isn't stable between builds. It's not
           ;; really used anywhere though, so just sort it.
           (mapvals #(update-in % [:cljs.analyzer/constants :order]
                                (fn [v] (sort-by name v))))
           clean-edn
           pr)                        
      (.close *out*)))

  (lg "Outputting deps cache")
  (when full-deps
    (spit (io/file target "deps.cljs") full-deps)))

(defn better-map->javascript-file
  [src-dir m]
  (letfn [(get-url [f] (let [file (if (.isAbsolute (io/file f))
                                    (io/file f)
                                    (io/file src-dir f))]
                         (if (.exists file)
                           (io/as-url file)
                           (deps/find-url f))))]
    (merge (closure/map->javascript-file
            (assoc m :file (get-url (:file m))))
           (when (:file-min m)
             {:url-min (get-url (:file-min m))}))))

(defn produce-final-output
  [src-dir target compiler-env full-options]
  (env/with-compiler-env compiler-env
    (when (:emit-constants full-options)
      (comp/emit-constants-table-to-file
       (::ana/constant-table @env/*compiler*)
       (str target "/constants_table.js")))

    (lg "Loading sources")
    (let [js-sources
          (vec
           (concat
            ;; goog
            (do
              (lg "Loading goog.*")
              (->> (deps/goog-dependencies)
                   (map (partial better-map->javascript-file src-dir))
                   (map #(do (assert %) %))
                   (vec)))

            ;; Compiled clojurescript
            (with-open [reader (-> (io/file src-dir "ijavascript.edn.gz")
                                   io/input-stream
                                   java.util.zip.GZIPInputStream.
                                   io/reader
                                   java.io.PushbackReader.)]
              (lg "Loading ijavascript.edn.gz")
              (->> reader
                   (partial edn/read {:eof :theend})
                   repeatedly
                   (take-while (partial not= :theend))
                   (map #(update % :file (partial io/file src-dir)))
                   (map #(update % :source-file (comp io/as-url io/file)))
                   (map closure/compiled-file)
                   ;; https://github.com/clojure/clojurescript/pull/59
                   (map #(do (swap! env/*compiler* update ::comp/compiled-cljs assoc (.getPath (:url %)) %) %))

                   (map #(do (assert %) %))
                   vec))

            ;; External deps
            ;; This is a slight re-implementation of load-foreign-library
            (do
              (lg "Loading deps.clj " (count (:foreign-libs full-options)))
              (->> (:foreign-libs full-options)
                   (map #(assoc % :foreign true))
                   (map (partial better-map->javascript-file src-dir))
                   (map #(do (assert %) %))
                   (vec)))

            ;; Constants
            (when (:emit-constants full-options)
               (let [url (io/as-url (io/file target "constants_table.js"))]
                 [(closure/javascript-file nil url url ["constants-table"] ["cljs.core"] nil nil)]))))
          _ (lg "Building index")
          index (deps/build-index js-sources)
          main (index (name (:main full-options)))
          _ (assert main (str "Couldn't find " (:main full-options) " in " (keys index)))
          _ (lg "Pruning sources")
          ;; This is very close to deps/dependency-order.
          scoped-sources (->> (deps/dependency-order-visit
                               (assoc index :order [])
                               (name (:main full-options)))
                              :order
                              distinct
                              closure/add-goog-base)
          missing (->> scoped-sources
                       (mapcat deps/-requires)
                       (remove #(contains? index %))
                       vec)
          _ (assert (empty? missing) (str "Couldn't find deps: " missing))
          externs (->> (:externs full-options)
                       (map #(.getAbsolutePath (io/file src-dir %)))
                       vec)
          full-options (assoc full-options
                              :externs externs
                              :source-map (if (:source-map full-options)
                                            (str target "/" (name (:main full-options)) ".js.map")
                                            nil)
                              :asset-path "."
                              :output-dir target
                              :output-to (str target "/" (name (:main full-options)) ".js"))
          optim (:optimizations full-options)]
      (io/make-parents (io/file (:output-to full-options)))
      (if (and optim (not= optim :none))
        (let [_ (lg "Optimizing " (count scoped-sources) " sources")
              foreign-deps-str (closure/foreign-deps-str
                                full-options
                                (filter closure/foreign-source? scoped-sources))
              full-options (assoc full-options
                                  :foreign-deps-line-count
                                  (- (count (.split #"\r?\n" foreign-deps-str -1)) 1))
              optimized (apply closure/optimize full-options
                               (remove closure/foreign-source? scoped-sources))]
          (lg "Done with optimizations")
          (assert optimized)
          
          (->> optimized
               (closure/add-wrapper full-options)
               (closure/add-source-map-link full-options)
               (str foreign-deps-str)
               (closure/add-header full-options)
               (closure/output-one-file full-options)))
        (let [scoped-sources (->> scoped-sources
                                  (map
                                   (fn [{:keys [url] :as ij}]
                                     (cond
                                       (and (some? url) (not= (.getProtocol url) "jar"))
                                       (let [src (io/file url)
                                             dest (io/file target (relativize src-dir src))]
                                         (io/make-parents dest)
                                         (io/copy src dest)
                                         (assoc ij :url (io/as-url dest)))
                                       :else
                                       ij))))]
          (lg "Outputting unoptimized")
          (apply closure/output-unoptimized full-options scoped-sources)
          (spit (io/file target "index.html") (str "<html><head><script src=\"" (:main full-options) ".js\"></script></head></html>"))))

      (when (:emit-constants full-options)
        (.delete (io/file target "constants_table.js")))
      (lg "Wrote output"))))

(let [compiler-options (edn/read-string (first *command-line-args*))
      target (second *command-line-args*)
      src-dir (nth *command-line-args* 2)
      deps (if (:final-output compiler-options)
             (drop 2 *command-line-args*)  ;; include src-dir
             (drop 3 *command-line-args*))
      _ (if-not (:final-output compiler-options)
          (lg "Start transpiling " (clean-potential-path src-dir))
          (lg "Start compiling " (clean-potential-path target)))
      options (-> compiler-options
                  (closure/add-implicit-options)
                  (merge {:force true
                          :cache-analysis false}))
      [deps-deps namespaces] (gather-deps deps)
      full-deps (if (and src-dir (.exists (io/file src-dir "deps.cljs")))
                  (->
                   (read-edn-file src-dir "deps.cljs")
                   (merge-deps deps-deps))
                  deps-deps)]

  ;; Replicate what ana/analyze-file does when loading the .edn cache.
  ;; I am not proud of this.
  (let [full-options (merge options full-deps)
        compiler-env (-> @(env/default-compiler-env full-options)
                         (update :cljs.analyzer/namespaces merge namespaces)
                         atom)]
    (lg "Updated namespaces")
    (env/with-compiler-env compiler-env
      (doseq [cached-ns (vals namespaces)]
        (doseq [x (get-in cached-ns [:cljs.analyzer/constants :order])]
          (ana/analyze-keyword nil x))))

    (lg "Compiling")
    (System/setProperty "user.dir" src-dir)  ;; to pick up any resources.
    (binding [ana/*cljs-static-fns*
              (or (and (= (:optimizations full-options) :advanced)
                       (not (false? (:static-fns full-options))))
                  (:static-fns full-options)
                  ana/*cljs-static-fns*)
              *assert* (not= (:elide-asserts full-options) true)
              ana/*load-tests* (not= (:load-tests full-options) false)
              ana/*macro-infer* false
              ana/*cljs-warnings*
              (let [warnings (full-options :warnings true)]
                (merge
                 ana/*cljs-warnings*
                 (if (or (true? warnings)
                         (false? warnings))
                   (zipmap
                    [:unprovided :undeclared-var
                     :undeclared-ns :undeclared-ns-form]
                    (repeat warnings))
                   warnings)))
              ana/*verbose* (:verbose full-options)]
      (if-not (:final-output compiler-options)
        (compile-dir src-dir target compiler-env full-options full-deps)
        (produce-final-output src-dir target compiler-env full-options)))))

