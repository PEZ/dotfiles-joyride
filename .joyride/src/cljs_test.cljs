(ns cljs-test
  (:require [cljs.test :refer [deftest is are testing]]))

(deftest foo
  (testing "foo"
    (are [x y] (= x y)
      1 2)
    (is (= 1 2))))