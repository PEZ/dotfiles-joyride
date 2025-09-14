(ns print-string
  (:require
   ["vscode" :as vscode]
   [clojure.string :as string]
   [joyride.core :as joyride]
   [promesa.core :as p]
   :reload))

(defn unescape-string
  "Convert literal \\n to actual newlines and \\\" to actual quotes"
  [s]
  (-> s
      (string/replace #"\\n" "\n")
      (string/replace #"\\\"" "\"")))

(defn- main []
  (p/let [s (vscode/window.showInputBox)
          unescaped (unescape-string s)
          _ (vscode/env.clipboard.writeText unescaped)]))

(when (= (joyride/invoked-script) joyride/*file*)
  (main)
  nil)
