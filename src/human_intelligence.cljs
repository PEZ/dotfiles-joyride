(ns human-intelligence
  (:require
   ["vscode" :as vscode]
   [clojure.string :as string]
   [promesa.core :as p]))

(def timeout-ms 10000)
(def timeout-s (/ timeout-ms 1000))

(defn request-human-input! [prompt]
  (let [!state (atom {::result ::empty->what-would-rich-hickey-do?})]
    (p/create
     (fn [resolve-fn _reject]
       (let [input-box (vscode/window.createInputBox)]
         (set! (.-title input-box) "AI Agent needs input")
         (set! (.-prompt input-box) prompt)
         (set! (.-placeholder input-box) (str "Start typing to cancel auto-dismiss (" timeout-s "s timeout)..."))
         (set! (.-ignoreFocusOut input-box) true)
         (swap! !state assoc ::timeout-id
                (js/setTimeout (fn [] (.hide input-box)) timeout-ms))
         (.onDidChangeValue input-box (fn [_] (when-let [timeout-id (::timeout-id @!state)]
                                                (swap! !state dissoc ::timeout-id)
                                                (js/clearTimeout timeout-id))))
         (.onDidAccept input-box (fn []
                                   (let [value (.-value input-box)]
                                     (when-not (string/blank? value)
                                       (swap! !state assoc ::result value))
                                     (.hide input-box))))
         (.onDidHide input-box (fn []
                                 (.dispose input-box)
                                 (resolve-fn (str (::result @!state)))))
         (.show input-box))))))

(comment
  (p/let [a (request-human-input! "hello?")]
    (def a a))
  :rcf)

