(ns open-file-here
  (:require ["vscode" :as vscode]
            ["os" :as os]))

(defn ^:async open-file-here []
  (when-let [input (await (vscode/window.showInputBox
                           #js {:prompt "File path to open in this window"
                                :placeHolder "~/path/to/file.cljs"}))]
    (let [resolved (if (.startsWith input "~")
                     (str (os/homedir) (.substring input 1))
                     input)
          uri (vscode/Uri.file resolved)
          doc (await (vscode/workspace.openTextDocument uri))]
      (vscode/window.showTextDocument doc))))

(defn ^:export open []
  (open-file-here))
