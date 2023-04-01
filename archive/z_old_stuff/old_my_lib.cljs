(ns my-lib
  (:require ["vscode" :as vscode]
            ["ext://betterthantomorrow.calva" :as calva :refer [v0]]
            ["ext://betterthantomorrow.calva$v0" :as v0 :refer [repl ranges]]
            ["ext://betterthantomorrow.calva$v0.repl" :refer [evaluateCode]]
            [joyride.core :as joyride]
            [promesa.core :as p]
            [z-joylib.calva-api]))

(def oc (joyride.core/output-channel))

(comment
  (-> (v0/repl.evaluateCode "clj" "42")
      (p/then (fn [v]
                (.appendLine oc (.-result v))))
      (p/catch (fn [e]
                 (.appendLine oc "Error:" e))))
  (-> (v0/repl.currentSessionKey)
      (p/then (fn [v]
                (.appendLine oc v)))
      (p/catch (fn [e]
                 (.appendLine oc "Error:" e))))
  (.appendLine oc (second (v0/ranges.currentTopLevelForm))))

;;(-> (ranges.currentTopLevelForm)
;;    (p/then (fn [v]
;;              (println (second v))))
;;    (p/catch (fn [e]
;;               (println "Error:" e))))

;;(-> (calva/v0.repl.evaluateCode "clj" "39")
;;    (p/then (fn [v]
;;              (.appendLine oc (str "Result: " (.-result v)))))
;;    (p/catch (fn [e]
;;               (.appendLine oc e))))

;;(-> (v0.repl.evaluateCode "clj" "40")
;;    (p/then (fn [v]
;;              (.appendLine oc (str "Result: " (.-result v)))))
;;    (p/catch (fn [e]
;;               (.appendLine oc e))))

;;(-> (repl.evaluateCode "clj" "41")
;;    (p/then (fn [v]
;;              (.appendLine oc (str "Result: " (.-result v)))))
;;    (p/catch (fn [e]
;;               (.appendLine oc e))))

;;(-> (v0/repl.evaluateCode "clj" "42")
;;    (p/then (fn [v]
;;              (.appendLine oc (str "Result: " (.-result v)))))
;;    (p/catch (fn [e]
;;               (.appendLine oc e))))

;;(-> (evaluateCode "clj" "43")
;;    (p/then (fn [v]
;;              (.appendLine oc (str "Result: " (.-result v)))))
;;    (p/catch (fn [e]
;;               (.appendLine oc e))))


;; Assuming this namespace i required by `activate.cljs`
;; you can reach vars in `my-lib` using `my-lib/<symbol>` in
;; `joyride.runCode` keybindings without requiring `my-lib``
;; there.

;; As an example, take the feature request on Calva to add a
;; **Restart clojure-lsp** command. It can be implemented with
;; with this function:

(defn restart-clojure-lsp []
  (p/do (vscode/commands.executeCommand "calva.clojureLsp.stop")
        (vscode/commands.executeCommand "calva.clojureLsp.start")))

;; And then this shortcut definition in `keybindings.json`
;; {
;;     "key": "<some-keyboard-shortcut>",
;;     "command": "joyride.runCode",
;;     "args": "(my-lib/restart-clojure-lsp)"
;; },

;; If you get complaints about `my-lib` not found, you probably
;; have not required it from `activate.cljs`

(defn clear-output-window []
  (p/do (vscode/commands.executeCommand "calva.showOutputWindow")
        (vscode/commands.executeCommand "editor.action.selectAll")
        (vscode/commands.executeCommand "deleteLeft")
        (vscode/commands.executeCommand "calva.showFileForOutputWindowNS")))

(defn replace-all-text []
  (p/do (vscode/commands.executeCommand "editor.action.selectAll")
        (vscode/commands.executeCommand "execCopy")
        (vscode/commands.executeCommand "paste-replaced.paste")))

(defn replace-last-word []
  (p/do (vscode/commands.executeCommand "cursorWordLeftSelect")
        (vscode/commands.executeCommand "execCopy")
        (vscode/commands.executeCommand "paste-replaced.paste")))

#_(defn rcf []
    (println vscode/window.activeTextEditor))

#_(aset editor "selection" original-selection)

(comment
  {:foo 1234
   :bar
   {:foo 4321
    :bar :baz
    42 :question
    :answer 42}})



(comment
  (-> (vscode/window.showQuickPick (clj->js [{:label "foo"}
                                             {:label "bar"}]))
      (p/then (fn [pick]
                (.appendLine oc (str "The pick: " (js->clj pick :keywordize-keys true))))))
  )