(ns util)

(defn convert-node [node]
  (cond
    (array? node) (mapv convert-node (into [] node))
    (and node (instance? js/Object node)) (into {} (for [k (js-keys node)
                                                         :let [v (aget node k)]]
                                                     [(keyword k) (convert-node v)]))
    :else node))