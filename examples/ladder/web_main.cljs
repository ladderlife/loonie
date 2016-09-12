(ns ladder.web-main
  (:require
   [ladder.common-lib :refer [stringify-to-json]]))

(enable-console-print!)

(.write js/document
        (stringify-to-json
         {:foo :bar}))
