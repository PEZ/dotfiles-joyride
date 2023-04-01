(ns editor-utils)

(require '["vscode" :as vsode]
         '[promesa.core :as p]
         '[joyride.core :as joy])

(defn current-selection []
  (let [editor vscode/window.activeTextEditor
        selection (.-selection editor)]
    selection))

(defn current-document []
  (let [editor vscode/window.activeTextEditor
        document (.-document editor)]
    document))

(defn current-selection-text []
  (.getText (current-document) (current-selection)))

(defn insert-text!+
  ([text]
   (insert-text!+ text vscode/window.activeTextEditor (.-active (current-selection))))
  ([text editor position]
   (-> (.edit editor
              (fn [builder]
                (.insert builder position text))
              #js {:undoStopBefore true :undoStopAfter false})
       (p/catch (fn [e]
                  (js/console.error e))))))

(defn replace-range!+
  ([text]
   (replace-range!+ text vscode/window.activeTextEditor (current-selection)))
  ([text editor range]
   (-> (.edit editor
              (fn [builder]
                (.replace builder range text))
              #js {:undoStopBefore true :undoStopAfter false})
       (p/catch (fn [e]
                  (js/console.error e))))))

(defn delete-range!
  [editor range]
  (-> (p/do (.edit editor
                   (fn [builder]
                     (.delete builder range))
                   #js {:undoStopBefore true :undoStopAfter false}))
      (p/catch (fn [e]
                 (js/console.error e)))))

(defn markdown-link! []
  (p/do (replace-range!+ (str "[" (current-selection-text) "]()"))
        (let [pos (.translate (.-end (current-selection)) 0, -1)]
          (aset vscode/window.activeTextEditor "selection" (vscode/Selection. pos pos)))))

(comment
  (markdown-link!)
  (def a-selection (current-selection))
  (aset vscode/window.activeTextEditor "selection" a-selection)
  (require '["ext://betterthantomorrow.calva$v0" :as calva]
           '[promesa.core :as p])
  (p/let [p (-> vscode/window.activeTextEditor
                .-document
                (.positionAt 0))
          ns-form (-> (calva/ranges.currentForm vscode/window.activeTextEditor p)
                      second)
          evaluated (calva/evaluateCode "clj" ns-form)
          evaluated-ns (.-ns evaluated)]

    (-> (calva/evaluateCode "clj" ns-form)
        (.then #(println %))))

  (ns javadoc
    (require '["ext://betterthantomorrow.calva$v0" :as calva]
             '["vscode" :as vscode]
             '[clojure.string :as str]
             '[promesa.core :as p]))

  (p/let [p (-> vscode/window.activeTextEditor
                .-document
                (.positionAt 0))
          ns-form (-> (calva/ranges.currentForm vscode/window.activeTextEditor p)
                      second)]
    (-> (calva/repl.evaluateCode (calva/repl.currentSessionKey) ns-form)
        (.then #(calva/repl.evaluateCode
                 "clj"
                 (str "(clojure.core/tap> \""
                      (.-ns %)
                      " evaluated\")")))))

  (insert-text!+ "foo"
                 vscode/window.activeTextEditor
                 (.-active a-selection))
  (insert-text!+ "foo"))