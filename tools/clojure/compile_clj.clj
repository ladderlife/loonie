(require '[clojure.java.io :as io])

;; This file can't depend on any 3rd-party packages since they won't
;; be compiled. See http://dev.clojure.org/jira/browse/CLJ-1544 .
(defn relativize
  [root dir]
  (if (= root dir)
    ""
    (let [root (.getAbsolutePath root)
          root (if (not (.endsWith root "/")) (str root "/") root)
          dir  (.getAbsolutePath dir)]
      (if-not (.startsWith dir root)
        (throw (RuntimeException. (str dir " doesn't start with " root)))
        (.substring dir (.length root))))))

(defn namespaces-in-dir [src]
  (for [file (file-seq (io/file src))
        :when (or (.endsWith (.getName file) ".clj")
                  (.endsWith (.getName file) ".cljc"))
        :when (not (.contains (.getAbsolutePath file) "META-INF"))
        :when (not (.contains (.getAbsolutePath file) "META_INF"))
        :when (not= (.getName file) "project.clj")
        :when (not= (.getName file) "data_readers.clj")
        ;; HACK for data.json
        :when (not= (.getName file) "json_compat_0_1.clj")
        :when (not (or (.endsWith (.getAbsolutePath file) "clj_webdriver/core_by.clj")
                       (.endsWith (.getAbsolutePath file) "clj_webdriver/core_element.clj")
                       (.endsWith (.getAbsolutePath file) "clj_webdriver/core_driver.clj")))]
    (-> (relativize (io/file src) file)
        (.replaceAll "/" ".")
        (.replaceAll "_", "-")
        (.replaceFirst "\\.cljc?$" "")
        symbol)))

(let [[[target src] args] (split-at 2 *command-line-args*)
      args (if (empty? args) (namespaces-in-dir src) (map symbol args))
      all-nses (sort args)]
  (System/setProperty "user.dir" src)  ;; to pick up any resources.
  (.mkdirs (io/file target))

  (doseq [ns all-nses
          :when (not (contains? (loaded-libs) ns))]
    (println "Compiling " ns)
    (binding [*compiler-options* {:direct-linking true}
              *compile-path* target
              *compile-files* true]
      (compile ns))))
