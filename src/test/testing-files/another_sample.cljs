;; Another sample file for context file testing
;; Used to test filtering of duplicate files in context

(ns test-sample.another-sample
  (:require
   [clojure.string :as string]))

(defn utility-function
  "A utility function."
  [s]
  (string/trim s))
