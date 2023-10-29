(ns js-repl
  (:require ["vscode" :as vscode]
            [joyride.core :as joyride]
            [promesa.core :as p]
            ["repl" :as node-repl]
            ["vm" :as vm]))

(def when-context-key "joyride-repl:hasDecorations")

(defonce !db (atom {:disposables []
                    :decorations {}}))

(defn vm-eval
  [code context file-info callback]
  (try (let [result (vm/runInContext code context file-info)]
         (callback nil result))
       (catch :default e
         (callback e))))

(defonce repl (.start node-repl #js {:eval vm-eval}))

(defn eval+ [code {:keys [filename line-offset column-offset]}]
  (let [!resolve (atom nil)]
    (-> (p/create
         (fn [resolve _reject]
           (reset! !resolve resolve)
           (.eval repl
                  code
                  (.-context repl)
                  #js {:filename filename
                       :lineOffset line-offset
                       :columnOffset column-offset}
                  (fn [err, result]
                    (def err err)
                    (-> (p/let [resolved-result result]
                          (if err
                            (resolve {:err err})
                            (resolve {:result resolved-result})))
                        (p/catch (fn [p-err]
                                   (resolve {:err p-err}))))))))
        ;; Some errors are not sent to the eval callback...
        (p/catch (fn [err]
                   (@!resolve {:err err}))))))

(comment
  (joyride/js-properties err)
  (.-message err)
  (.-stack err)
  :rcf)

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
  (let [display-results (if error (str error) result)]
    {:renderOptions {:after {:contentText (str " " (when-not error "=> ") display-results),
                             :overflow "hidden"
                             :color (if error "#F55" "#db9550")}}
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
          line (-> selection .-start .-line)
          column (-> selection .-start .-character)
          document vscode/window.activeTextEditor.document
          filename (.-fileName document)
          selectedText (.getText document selection)
          result (eval+ selectedText {:filename filename
                                      :line-offset line
                                      :column-offset column})
          pretty-printed-result (stringify (:result result))]
    (decorate! vscode/window.activeTextEditor.selection pretty-printed-result (:err result) "js")))

(defn clear-decorations! []
  (when-let [active-editor vscode/window.activeTextEditor]
    (swap! !db assoc-in [:decorations (editor->key active-editor)] nil)
    (.setDecorations active-editor eval-results-decoration-type #js [])
    (set-decorations-context! active-editor)))

(comment
  (decorate! vscode/window.activeTextEditor.selection "\"Hello World!\"" "js")
  (def selection vscode/window.activeTextEditor.selection)
  (joyride/js-properties selection)
  (joyride/js-properties (-> selection .-start))
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
