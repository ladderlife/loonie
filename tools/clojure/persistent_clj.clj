(ns persistent-clj
  (:require [clojure.core]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.set :refer [difference]]
            [clojure.stacktrace :refer [print-throwable]]
            [clojure.string :as string])
  (:import (java.net URLClassLoader URL)
           (java.io PrintWriter))
  (:gen-class :name PersistentClj))

(defn base-classloader []
  (or (.getClassLoader clojure.lang.RT)
      (.getContextClassLoader (Thread/currentThread))))

(defn url-classloader [urls ext]
  ;; We need to use DynamicClassLoader even if there's no dynamism -
  ;; the classloader knows how to invalidate the cache of loaded .clj files
  ;; when loading a java class with a clojure namespace. The cache itself
  ;; is of course in a global static, so loading clojure namespaces with
  ;; other class loaders _WILL_ end badly.
  (let [rv (clojure.lang.DynamicClassLoader. ext)]
    (doseq [u (map io/as-url (flatten urls))]
      (.addURL rv u))
    rv))

(defn remove-lib
  "Remove lib's namespace and remove lib from the set of loaded libs."
  [lib]
  (remove-ns lib)
  (dosync (alter @#'clojure.core/*loaded-libs* disj lib)))

(defn all-ns-sym []
  (set
   (concat
    (map ns-name (all-ns))
    (deref @#'clojure.core/*loaded-libs*))))

(defn strip-suffix
  [s suffix]
  (if (.endsWith s suffix)
    (.substring s 0 (- (.length s) (.length suffix)))
    s))

(defn shell-split
  "Splits a string into an array of tokens in the same way the UNIX Bourne
   shell does.
     (shell-split \"these are 'three shell tokens'\")
     ; [\"these\" \"are\" \"three shell tokens\"]
   Ported from Ruby's Shellwords#shellsplit()"
  [line]
  ;; Note that the Pattern is in DOTALL mode
  (let [ms (re-seq #"(?s)\G\s*(?>([^\s\\\'\"]+)|'([^\']*)'|\"((?:[^\"\\]|\\.)*)\"|(\\.?)|(\S))(\s|\z)?" line)]
    (first
     (reduce
      (fn [[words ^StringBuilder field] [_ word sq dq esc garbage sep]]
        (when garbage
          (throw (IllegalArgumentException. (str "Unmatched quote: " (pr-str line)))))
        (.append field (or word sq (string/replace (or dq esc) #"\\(.)" "$1")))
        (if sep
          [(conj words (str field)) (StringBuilder.)]
          [words field]))
      [[] (StringBuilder.)] ms))))

(defn tracking-loader
  [parent]
  (let [loaded-cljs (atom #{})
        proxy (proxy [ClassLoader] [^ClassLoader parent]
                (getResource [^String resource-name]
                  (when-let [result (proxy-super getResource resource-name)]
                    (when (or (.endsWith resource-name ".clj")
                              (.endsWith resource-name ".cljc"))
                      (let [clj-namespace (-> resource-name
                                              (.replaceAll "\\.cljc?$" "")
                                              (.replaceAll "/" ".")
                                              (.replaceAll "_" "-")
                                              symbol)]
                        (swap! loaded-cljs conj clj-namespace)))
                    result)))]
    [proxy loaded-cljs]))

(defn get-ancestor-loaders
  [^ClassLoader loader]
  (loop [loader loader result [loader]]
    (if-let [parent (.getParent loader)]
      (recur parent (conj result parent))
      result)))

(def classes-field (.getDeclaredField ClassLoader "classes"))
(.setAccessible classes-field true)
(defn clojure-namespaces-in-loader
  [^ClassLoader loader]
  (set (for [class (.clone ^java.util.Vector (.get classes-field loader))
             :let [class-name (.getName class)]
             :when (.endsWith class-name "__init")]
         (-> class-name
             (strip-suffix "__init")
             (.replaceAll "_" "-")
             symbol))))

(defn clojure-namespaces-in-loader-ancestors
  [loader]
  (set (mapcat clojure-namespaces-in-loader (get-ancestor-loaders loader))))

(def class-cache-field (.getDeclaredField clojure.lang.DynamicClassLoader "classCache"))
(.setAccessible class-cache-field true)

(defn create-user-ns
  []
  (binding [*ns* (create-ns 'user)]
    (clojure.core/refer 'clojure.core)))

(def id-field (.getDeclaredField clojure.lang.RT "id"))
(.setAccessible id-field true)
(defn call-with-classpath
  [classpath to-run]
  (let [initial-nses (disj (all-ns-sym) (symbol "user"))
        _ (create-user-ns)
        base-loader (base-classloader)
        [tracked-base-loader loaded-base-clj] (tracking-loader base-loader)
        classloader (url-classloader classpath tracked-base-loader)
        loader-before (.getContextClassLoader (Thread/currentThread))
        class-cache ^java.util.concurrent.ConcurrentHashMap (.get class-cache-field nil)
        id-before (.intValue (.get id-field nil))]
    ;; Disable caching of resources; unfortunately there doesn't seem to be
    ;; a better way of doing this.
    ;; TODO(mikekap): Explore wrapping classloader with another instance
    ;; that re-implements getResourceAsStream to set useCaches on that request.
    (.setDefaultUseCaches (.openConnection (java.net.URL. "http://www.google.com")) false)
    (if (or (nil? (get (System/getenv) "NO_UNLOAD")) (not (instance? clojure.lang.DynamicClassLoader loader-before)))
      (.setContextClassLoader (Thread/currentThread) classloader)
      (doseq [u (map io/as-url (flatten classpath))]
        (.addURL loader-before u)))
    (try
      (binding [*use-context-classloader* true]
        (let [readers (reduce #'clojure.core/load-data-reader-file
                              {} (#'clojure.core/data-reader-urls))]
          (binding [*data-readers* (merge *data-readers* readers)]
            (to-run))))
      (finally
        (when (nil? (get (System/getenv) "NO_UNLOAD"))
          (.setContextClassLoader (Thread/currentThread) loader-before)
          (.close classloader)
          (.set (.get id-field nil) id-before)
          (.clear class-cache)
          (doseq [ns (difference (all-ns-sym) @loaded-base-clj initial-nses (clojure-namespaces-in-loader-ancestors base-loader))]
            (remove-lib ns)))))))

(defn run-with-classpath
  [classpath-str & args]
  (let [classpath (map #(.toURI (io/as-file %))
                       (.split classpath-str ":"))]
    (call-with-classpath classpath
                         #(apply clojure.main/main args))))

(defn read-until
  ([reader eofs] (read-until reader eofs true))
  ([reader eofs skip-whitespace]
   (let [s (StringBuilder.)]
     (loop []
       (let [c (.read reader)]
         (cond
           (= c -1)
           [(.toString s) (char 0)]
           (contains? eofs (char c))
           [(.toString s) (char c)]
           :else
           (do
             (when (or (not skip-whitespace)
                       (not (Character/isWhitespace c)))
               (.append s (char c)))
             (recur))))))))

(defn read-message
  [reader]
  (let [result (json/read reader :key-fn keyword)]
    result))

(defn write-message
  [message]
  (json/write message *out*)
  (println)
  (.flush *out*))

(defn run-buck-mode []
  (let [reader (java.io.BufferedReader. *in*)
        [_ eof] (read-until reader #{\[})
        _ (assert (= eof \[))
        handshake (read-message reader)]
    (assert (and (= (:type handshake) "handshake")
                 (= (:protocol_version handshake) "0")))
    (println "[")
    (write-message {:id (:id handshake)
                    :type "handshake"
                    :protocol_version "0"
                    :capabilities []})
    (loop []
      (let [[cmd-str eof] (read-until reader #{\, \]})]
        (if (not= eof \,)
          (do
            (assert (and (= (.trim cmd-str) "")
                         (= eof \])))
            (println "]"))
          (let [cmd (read-message reader)]
            (println ",")
            (case (:type cmd)
              "command"
              (let [args (shell-split (slurp (:args_path cmd)))
                    old-out System/out
                    new-out (java.io.PrintStream. (io/as-file (:stdout_path cmd)))
                    old-err System/err
                    new-err (java.io.PrintStream. (io/as-file (:stderr_path cmd)))
                    ok (binding [*out* (PrintWriter. new-out)
                                 *err* (PrintWriter. new-err)]
                         (System/setOut new-out)
                         (System/setErr new-err)
                         (try
                           (apply run-with-classpath args)
                           true
                           (catch Throwable t
                             (.printStackTrace t *err*)
                             false)
                           (finally
                             (System/setOut old-out)
                             (System/setErr old-err)
                             (.close *out*)
                             (.close *err*))))]
                (write-message {:id (:id cmd)
                                :type "result"
                                :exit_code (if ok 0 100)}))
              (write-message {:id (:id cmd)
                              :type "error"
                              :exit_code 1}))
            (recur)))))))

(defn -main
  [& args]
  ;; Use a lock here in case we're being run from nailgun.
  (locking (class {})
    (if args
      (apply run-with-classpath args)
      (run-buck-mode))))
