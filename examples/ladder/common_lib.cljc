(ns ladder.common-lib
  (:require
   #?(:clj [cheshire.core :refer [generate-string]]
      :cljs [goog.json]))
  (:gen-class))

(defn stringify-to-json
  [value]
  #?(:clj (generate-string value)
     :cljs (goog.json.serialize (clj->js value))))
