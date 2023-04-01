(ns fortytwo-lab
  (:require [clojure.string :as string]))

;; Evaluate selection to Cursor
(defn fortytwo-from-thirty
  []
  (let [thirty 30]
    (-> thirty
        inc
        (+ 1 2 3)
        (->>
         (+ 2 2)
         (+))
        list
        (->>
         (into [1])
         (reduce + 1))
        (- 1)
        (* -1))))

(comment
  (fortytwo-from-thirty) ;; => -42 (WRONG answer! (or question?))
  )
