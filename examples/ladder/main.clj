(ns ladder.main
  (:require
   [ladder.common-lib :refer [stringify-to-json]])
  (:gen-class))

(defn -main
  [& [file]]
  (stringify-to-json
   (if file
     (read)
     (read-string (slurp file)))))
