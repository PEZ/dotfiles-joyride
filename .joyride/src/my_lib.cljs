(ns my-lib
  (:require ["vscode" :as vscode]
            [promesa.core :as p]))

;; Assuming this namespace i required by `<user/workspace>_activate.cljs`
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
;;     "args": "(my-lib/restart-clojure-lsp)"®®
;; },
    
;; If you get complaints about `my-lib` not found, you probably
;; have not required it from `user_activate.cljs` or `workspace_activate.cljs`

(comment
  (promesa.core/do
   (vscode/commands.executeCommand "editor.action.selectAll")
   (vscode/commands.executeCommand "calva.evaluateSelection")
   (vscode/commands.executeCommand "cursorUndo"))
  
  (vscode/commands.executeCommand "toggleFindRegex")
  (vscode/commands.executeCommand "search.action.openNewEditor" {:isRegex true})
  (vscode/commands.executeCommand "editor.actions.findWithArgs" {:isRegex true
                                                                 :searchString ""})
  )

(defn find-with-regex-on []
  (let [selection vscode/window.activeTextEditor.selection
        document vscode/window.activeTextEditor.document
        selectedText (.getText document selection)
        regexp-chars (js/RegExp. #"[.?+*^$\\|(){}[\]]" "g")
        newline-chars (js/RegExp. #"\n" "g")
        escapedText (-> selectedText
                        (.replace regexp-chars "\\$&")
                        (.replace newline-chars "\\n?$&"))]
    (vscode/commands.executeCommand
     "editor.actions.findWithArgs"
     #js {:isRegex true
          :searchString escapedText})))