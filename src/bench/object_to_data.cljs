(ns bench.object-to-data
  (:require ["acorn-loose" :as acorn]
            [util :as util]
            ["./ast-util.js" :as ast-util]
            ["fs" :as fs]))

(defn ast->data [node]
  (cond
    (array? node) (mapv ast->data (into [] node))
    (and node (instance? js/Object node)) (into {} (for [k (js-keys node)
                                                         :let [v (aget node k)]]
                                                     [(keyword k) (ast->data v)]))
    :else node))

(comment
  ; wc test-files/large.js
  ; 7662   18943  169040 test-files/large.js
  (def text (fs/readFileSync "/Users/pez/.config/joyride/test-files/large.js"))
  (util/time ; Elapsed time: 8.087583065032959 msecs
   (def ast (acorn.parse text #js {:allowAwaitOutsideFunction true})))
  (util/time ; Elapsed time: 5.1631669998168945 msecs
   (def js-ast (ast-util/objectAstToData ast)))
  (util/time ; Elapsed time: 41.782166957855225 msecs
   (def clojure-ast-1 (js->clj js-ast :keywordize-keys true)))
  (util/time ; Elapsed time: 201.6672089099884 msecs
   (def clojure-ast-2 (ast->data ast)))
  (util/time ; Elapsed time: 16.00624990463257 msecs
   (def js-ast-2 (-> ast
                     js/JSON.stringify
                     js/JSON.parse)))
  (util/time ; Elapsed time: 41.43733310699463 msecs
   (def clojure-ast-3 (js->clj js-ast-2 :keywordize-keys true)))
  :rcf)