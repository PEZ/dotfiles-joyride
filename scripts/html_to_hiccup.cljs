(ns html-to-hiccup
  (:require ["posthtml-parser" :as parser]
            [clojure.walk :as walk]))

(defn html->hiccup
  [html]
  (-> html
      (parser/parser)
      (js->clj :keywordize-keys true)
      (->> (into [:div])
           (walk/postwalk
            (fn [{:keys [tag attrs content] :as element}]
              (if tag
                (into [(keyword tag) (or attrs {})] content)
                element))))))

(comment
  (html->hiccup "<label for=\"hw\">Foo</label><ul id=\"foo\"><li>Hello</li></ul>")
  ;; => [:div [:label {:for "hw"} "Foo"] [:ul {:id "foo"} [:li {} "Hello"]]]
  )
