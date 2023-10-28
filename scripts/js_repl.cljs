(ns js-repl
  (:require ["vscode" :as vscode]
            [joyride.core :as joyride]
            [clojure.string :as string]
            [promesa.core :as p]
            ["repl" :as repl]
            :reload))

(defonce !db (atom {:disposables []
                    :decorations {}}))

(defonce node-repl (.start repl))

(defn eval+ [code]
  (p/create (fn [resolve reject]
              (-> node-repl
                  (.eval code (.-context node-repl)
                         ""
                         (fn [err, result]
                           (if err
                             (reject err)
                             (resolve result))))))))

(def when-context-key "joyride-repl:hasDecorations")

(defn- clear-disposables! []
  (run! (fn [disposable]
          (.dispose disposable))
        (:disposables @!db))
  (swap! !db assoc :disposables []))

(defn- push-disposable! [disposable]
  (swap! !db update :disposables conj disposable))

(def eval-results-decoration-type
  (vscode/window.createTextEditorDecorationType
   #js {:after #js {:color "#db9550"},
        :rangeBehavior vscode/DecorationRangeBehavior.ClosedOpen}))

(defn evaluated-render-options [range s language]
   {:renderOptions {:after {:contentText (str "\u00a0=> " (string/replace s #" ", "\u00a0")),
                            :overflow "hidden"}}
    :hoverMessage (str "``` " language "\n"
                       s
                       "\n```\n")
    :range range})

(defn editor->key [active-editor]
  (-> active-editor .-document .-uri str))

(defn set-decorations-context! [editor]
  (let [decorations? (not (nil? (get-in @!db [:decorations (editor->key editor)])))]
    (vscode/commands.executeCommand "setContext" when-context-key decorations?)))

(defn decorate! [range s language]
  (when-let [active-editor vscode/window.activeTextEditor]
    (let [k (editor->key active-editor)
          decorations (conj (get-in @!db [:decorations k] [])
                            (evaluated-render-options range s language))]
      (swap! !db assoc-in [:decorations k] decorations)
      (.setDecorations active-editor eval-results-decoration-type (clj->js decorations))
      (set-decorations-context! active-editor))))

(defn stringify [value]
  (cond
    (nil? value) "null"
    (number? value) (str value)
    (fn? value) (str value)
    :else (let [json-str (js/JSON.stringify value nil 2)]
            (if (nil? json-str)
              (str value)
              json-str))))

(defn ^:export evaluate-selection! []
  (p/let [selection vscode/window.activeTextEditor.selection
          document vscode/window.activeTextEditor.document
          selectedText (.getText document selection)
          result (eval+ selectedText)
          pretty-printed-result (stringify result)]
    (decorate! vscode/window.activeTextEditor.selection pretty-printed-result "js")))

(defn clear-decorations! []
  (when-let [active-editor vscode/window.activeTextEditor]
    (swap! !db assoc-in [:decorations (editor->key active-editor)] nil)
    (.setDecorations active-editor eval-results-decoration-type #js [])
    (set-decorations-context! active-editor)))

(comment
  (decorate! vscode/window.activeTextEditor.selection "\"Hello World!\"" "js")
  (clear-decorations!)
  :rcf)



(defn init! []
  (clear-disposables!)
  (push-disposable! (vscode/window.onDidChangeActiveTextEditor set-decorations-context!)))


(when (= (joyride/invoked-script) joyride/*file*)
  (init!))

(comment
  (init!)
  (clear-disposables!)
  :rcf)

"🚗💨"
