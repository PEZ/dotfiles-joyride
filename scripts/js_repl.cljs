(ns js-repl
  (:require ["vscode" :as vscode]
            [joyride.core :as joyride]
            [clojure.string :as string]
            [promesa.core :as p]
            ["repl" :as node-repl]
            ["vm" :as vm]))

(def when-context-key "joyride-repl:hasDecorations")

(defonce !db (atom {:disposables []
                    :decorations {}}))

(defn vm-eval
  [code context _filename callback]
  (try (let [result (vm/runInContext code context)]
         (callback nil result))
       (catch :default e
         (callback e))))

(defonce repl (.start node-repl #js {:eval vm-eval}))

(defn eval+ [code]
  (let [!resolve (atom nil)]
    (-> (p/create (fn [resolve _reject]
                    (reset! !resolve resolve)
                    (.eval repl
                           code
                           (.-context repl)
                           ""
                           (fn [err, result]
                             (if err
                               (resolve {:err err})
                               (resolve {:result result}))))))
        (p/catch (fn [err]
                   (@!resolve {:err err}))))))

(defn- clear-disposables! []
  (run! (fn [disposable]
          (.dispose disposable))
        (:disposables @!db))
  (swap! !db assoc :disposables []))

(defn- push-disposable! [disposable]
  (swap! !db update :disposables conj disposable))

(def eval-results-decoration-type
  (vscode/window.createTextEditorDecorationType
   #js {:after #js {},
        :rangeBehavior vscode/DecorationRangeBehavior.ClosedOpen}))

(defn evaluated-render-options [range result error language]
  (let [display-results (str result error)]
    {:renderOptions {:after {:contentText (str "\u00a0=> "
                                               (string/replace display-results #" ", "\u00a0")),
                             :overflow "hidden"
                             :color (if error "red" "#db9550")}}
     :hoverMessage (str "``` " language "\n"
                        display-results
                        "\n```\n")
     :range range}))

(defn editor->key [active-editor]
  (-> active-editor .-document .-uri str))

(defn set-decorations-context! [editor]
  (let [decorations? (not (nil? (get-in @!db [:decorations (editor->key editor)])))]
    (vscode/commands.executeCommand "setContext" when-context-key decorations?)))

(defn decorate! [range s error language]
  (when-let [active-editor vscode/window.activeTextEditor]
    (let [k (editor->key active-editor)
          decorations (-> (remove (fn [decoration]
                                    (.intersection (:range decoration) range))
                                  (get-in @!db [:decorations k] []))
                          (conj (evaluated-render-options range s error language)))]
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
          _ (def result result)
          pretty-printed-result (stringify (:result result))]
    (decorate! vscode/window.activeTextEditor.selection pretty-printed-result (:err result) "js")))

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
