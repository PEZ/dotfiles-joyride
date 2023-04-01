(ns resolve-issue
  (:require [clojure.string :as string]
            [clojure.string :as s]))

(defn resolve-string [s]
  (->> s
       symbol
       resolve))