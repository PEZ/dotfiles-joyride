;; Best placed among your user scripts
;; How to use:
;; 0. Configure the keyboard shortcuts (see below)
;; 1. Run the command **Joyride: Run User Script...**
;;    Select `js-repl.cljs`
;; 2. In your JS files, select code and evaluate with `ctrl+enter`
;;    Clear result decorations with `ctrl+escape`
;;
;; You decide yourself what are good keyboard shortcuts, of course
;;
;; == Keyboard shortcuts ==
;;    {
;;        "key": "ctrl+enter",
;;        "when": "editorLangId == 'javascript' && joyride-js-repl:isActive",
;;        "command": "joyride.runCode",
;;        // "args": "(js-repl/evaluate-selection! false) // do not rewrite top level const and let
;;        "args": "(js-repl/evaluate-selection! true)" // rewrite top level const and let
;;    },
;;    {
;;        "key": "ctrl+escape",
;;        "when": "joyride-js-repl:hasDecorations",
;;        "command": "joyride.runCode",
;;        "args": "(js-repl/clear-decorations!)",
;;    },

(ns js-repl
    (:require ["vscode" :as vscode]
              [clojure.string :as string]
              [joyride.core :as joyride]
              [promesa.core :as p]
              ["repl" :as node-repl]
              ["vm" :as vm]
              ["acorn-loose" :as acorn]))

(def js-repl-active?-when-key "joyride-js-repl:isActive")
(def decorations?-when-key "joyride-js-repl:hasDecorations")

(defonce !db (atom {:disposables []
                    :decorations {}
                    :output-channel nil
                    :repl nil}))

(defn vm-eval
  [code context file-info callback]
  (try (let [result (vm/runInContext code context file-info)]
         (callback nil result))
       (catch :default e
         (callback e))))

(defn eval+ [code {:keys [filename line-offset column-offset]}]
  (let [!resolve (atom nil)
        repl (:repl @!db)]
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
                    ;; Some results (and errors are promises)
                    (-> (p/let [resolved-result result]
                          (if err
                            (resolve {:err err})
                            (resolve {:result resolved-result})))
                        (p/catch (fn [p-err]
                                   (resolve {:err p-err}))))))))
        ;; Some errors are not sent to the eval callback...
        (p/catch (fn [err]
                   (@!resolve {:err err}))))))

(defn start-repl! []
  (.start node-repl #js {:eval vm-eval
                         :replMode repl/REPL_MODE_SLOPPY}))

(defn find-declarations [node]
  (when (map? node)
    (when (and (= (:type node) "VariableDeclaration")
               (or (= (:kind node) "const")
                   (= (:kind node) "let")))
      {:start (:start node)
       :length (count (:kind node))})))

(defn find-top-level-declarations [body]
  (keep find-declarations body))

(defn replace-at-locations [text locations s]
  (reduce (fn [acc {:keys [start length]}]
            (str (subs acc 0 start) s (subs acc (+ start length))))
          text
          (reverse locations)))

(defn parse-js [js]
  (-> (acorn.parse js #js {:allowAwaitOutsideFunction true})
      js/JSON.stringify
      js/JSON.parse
      (js->clj :keywordize-keys true)))

(defn top-level-declarations->var [text]
  (let [locations (-> text
                      parse-js
                      :body
                      find-top-level-declarations)]
    (replace-at-locations text locations "var")))

(comment
  (require '[util :refer [time]]
           '["fs" :as fs])
  (def text (fs/readFileSync "/Users/pez/.config/joyride/test-files/large.js"
                             #js {:encoding "utf8" :flag "r"}))
  (time ; 60 ms
   (def clojure-ast (parse-js text)))
  (time ; 0.02 ms
   (def declarations (find-top-level-declarations (:body clojure-ast))))
  (count declarations) ;=> 248 for large.js
  (time ; 8 ms
   (def new-text (replace-at-locations text declarations "var")))
  (println new-text)
  (time ; 70ms
   (def replaced (top-level-declarations->var text)))
  :rcf)

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
    (vscode/commands.executeCommand "setContext" decorations?-when-key decorations?)))

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

(defn- format-error [error]
  (str "// " error "\n"
       (->> (.-stack error)
            (clojure.string/split-lines)
            (map (fn [line]
                   (str "// " line)))
            (take-while (fn [line]
                          (not (re-find #"at Script.runInContext" line))))
            (string/join "\n"))))

(defn evaluate! [range document dynamic-top-level?]
  (p/let [line (-> range .-start .-line)
          column (-> range .-start .-character)
          _ (def document document)
          filename (.-fileName document)
          selected-text (.getText document range)
          code (if dynamic-top-level?
                 (top-level-declarations->var selected-text)
                 selected-text)
          result (eval+ code {:filename filename
                              :line-offset line
                              :column-offset column})
          pretty-printed-result (stringify (:result result))]
    (doto (:output-channel @!db)
      (.append (if (:err result)
                 (format-error (:err result))
                 pretty-printed-result))
      (.append "\n---\n\n"))
    (decorate! vscode/window.activeTextEditor.selection
               pretty-printed-result
               (:err result)
               (if (:err result) "text" "js"))))

(defn evaluate-document! [dynamic-top-level?]
  (let [document vscode/window.activeTextEditor.document
        range (vscode/Range. (.positionAt document 0)
                             (.positionAt document (-> document .getText count)))]
    (evaluate! range document dynamic-top-level?)))

(defn ^:export evaluate-selection! [dynamic-top-level?]
  (evaluate! vscode/window.activeTextEditor.selection
             vscode/window.activeTextEditor.document
             dynamic-top-level?))

(defn ^:export clear-decorations! []
  (when-let [active-editor vscode/window.activeTextEditor]
    (swap! !db assoc-in [:decorations (editor->key active-editor)] nil)
    (.setDecorations active-editor eval-results-decoration-type #js [])
    (set-decorations-context! active-editor)))

(defn- clear-disposables! []
  (run! (fn [disposable]
          (.dispose disposable))
        (:disposables @!db))
  (swap! !db assoc :disposables []))

(defn- push-disposable! [disposable]
  (swap! !db update :disposables conj disposable))

(defn init! []
  (clear-disposables!)
  (clear-decorations!)
  (push-disposable! (vscode/window.onDidChangeActiveTextEditor set-decorations-context!))
  (let [channel (vscode/window.createOutputChannel "Joyride JS-REPL" "javascript")]
    (swap! !db assoc :output-channel channel)
    (push-disposable! channel))
  ;; Create a new repl context on init so that we can undeclare stuff
  (swap! !db assoc :repl (start-repl!))
  (vscode/commands.executeCommand "setContext" js-repl-active?-when-key true))

(when (= (joyride/invoked-script) joyride/*file*)
  (init!))

(comment
  (object? (new js/Object))
  (init!)
  (clear-disposables!)

  :rcf)

"🚗💨"
