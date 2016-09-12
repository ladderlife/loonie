(ns test-runner
  (:require [clojure.test :as ct]
            [clojure.string :as string]
            [eftest.runner :as t]
            [eftest.report.pretty :as p]
            [bultitude.core :as b]
            [clojure.java.io :as io])
  (:gen-class :name BuckTestRunner))

(defmulti report-with-out-capture :type)

(defmethod report-with-out-capture :default [m]
  (p/report m))

(def ^:dynamic *current-baos* nil)
(def ^:dynamic *previous-system-out* nil)
(def ^:dynamic *previous-system-err* nil)
(def ^:dynamic *previous-err* nil)
(def ^:dynamic *should-dump-on-end* nil)
(defmethod report-with-out-capture :begin-test-var [m]
  (let [out (java.io.ByteArrayOutputStream.)
        ps (java.io.PrintStream. out false "UTF-8")
        writer (java.io.OutputStreamWriter. out "UTF-8")]
    ;; TODO(mikekap): Create a "lazy" OutputStream implmenentation that looks up
    ;; a clojure var lazily (i.e. *out*/*err*) and delegates to it. If we have that,
    ;; we can replace System/out System/err at the start of the test and turn on multi-
    ;; threading (then logger and other java apis will get properly captured stdout/err).
    (push-thread-bindings
     {#'*current-baos* out
      #'*previous-err* *err*
      #'*err* writer
      #'*out* writer
      #'ct/*test-out* writer
      #'*should-dump-on-end* (atom false)
      #'*previous-system-out* System/out
      #'*previous-system-err* System/err})
    (System/setOut ps)
    (System/setErr ps))
  (p/report m))

(defn dump-captured-output []
  (.flush System/out)
  (.flush *err*)
  (.write *previous-err* (.toString *current-baos* "UTF-8")))

(defmethod report-with-out-capture :error [m]
  (p/report m)
  (reset! *should-dump-on-end* true))

(defmethod report-with-out-capture :fail [m]
  (p/report m)
  (reset! *should-dump-on-end* true))

(defmethod report-with-out-capture :end-test-var [m]
  (p/report m)
  (when @*should-dump-on-end*
    (dump-captured-output))
  (System/setOut *previous-system-out*)
  (System/setErr *previous-system-err*)
  (pop-thread-bindings))

;; Fix for not returning the actual results.
(defmethod report-with-out-capture :summary [m]
  (p/report m)
  m)

(defn -main
  [& args]
  (let [nses (if args
               (mapcat #(if (.isDirectory (java.io.File. %))
                          (b/namespaces-in-dir %)
                          [(second (b/ns-form-for-file %))]) args)
               (b/namespaces-on-classpath))
        _ (run! require nses)
        using-out-capture (nil? (System/getenv "CLOJURE_NO_OUT_CAPTURE"))
        options {:multithread? (not using-out-capture)
                 :report (if using-out-capture
                           report-with-out-capture
                           p/report)}
        results (atom nil)
        test-output (with-out-str
                      (binding [*err* *out*]
                        (reset! results
                                (t/run-tests (t/find-tests nses) options))))
        results @results]
    ;; A bit of a hack for buck: Add "remove color" commands at the front of each
    ;; line so the background isn't red.
    (binding [*out* *err*]
      (->> (string/split test-output #"\n")
           (map #(str "\033[0m" %))
           (run! println)))
    ;; Shutdown thread pools so we can exit the JVM without hanging for 30s.
    (shutdown-agents)
    (if (and (some? results) (ct/successful? results))
      (System/exit 0)
      (System/exit 42))))
