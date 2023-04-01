(ns scratch
  (:require ["vscode" :as vscode]
            [joyride.core :as joyride]
            [promesa.core :as p]))

(comment
  (p/do (vscode/commands.executeCommand "calva.clojureLsp.stop") (vscode/commands.executeCommand "calva.clojureLsp.start"))
  (-> 10
      (* 4)
      (+ 1)
      inc)

  (vscode/window.showInformationMessage (str "Hello, answer: " (+ (* 4 10) 1 1)) ()))

"🎸"
