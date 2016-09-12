(ns ladder.common-lib-test
  (:require
   [ladder.common-lib :refer [stringify-to-json]]
   #?(:clj [clojure.test :refer [deftest testing is]]
      :cljs [cljs.test :refer-macros [deftest testing is]])))

(deftest stringify-test
  (is (= "{\"foo\": \"bar\"}" (stringify-to-json {:foo :bar}))))
