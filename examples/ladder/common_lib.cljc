(ns ladder.common-lib
  (:require
   #?(:clj [cheshire.core :refer [generate-string]]
      :cljs [goog.json :as gjson])))

(defn stringify-to-json
  [value]
  #?(:clj (generate-string value)
     :cljs (gjson/serialize (clj->js value))))
