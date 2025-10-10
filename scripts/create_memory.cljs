(ns create-memory
  "Keyboard shortcut for creating memories from selected text or user input"
  (:require
   [memory-agent :as ma]
   [promesa.core :as p]
   ["vscode" :as vscode]))

(defn activate []
  (p/let [;; Get selected text as context
          editor vscode/window.activeTextEditor
          selection (when editor (.-selection editor))
          text (when selection
                 (.getText (.-document editor) selection))]
    (if (and text (seq text))
      ;; Use selected text
      (ma/create-and-handle-memory! text)
      ;; Ask for input
      (p/let [input (vscode/window.showInputBox
                      #js {:prompt "Enter context for memory creation"
                           :placeHolder "Mistake: ... Correction: ..."})]
        (when (and input (seq input))
          (ma/create-and-handle-memory! input))))))

(activate)
