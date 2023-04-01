(ns util
  (:require ["vscode" :as vscode]
            [promesa.core :as p]))

(defn ws-root
  "Returns the Uri of the first workspace folder if there is one
   otherwise the current directory."
  []
  (if (not= js/undefined
            vscode/workspace.workspaceFolders)
    (.-uri (first vscode/workspace.workspaceFolders))
    (vscode/Uri.parse ".")))

(defn slurp-ws-file+
  "Returns the content of `ws-file` as a string,
   where `ws-file` is a path relative to the workspace root"
  [ws-file]
  (-> (p/let [uri (vscode/Uri.joinPath (ws-root) ws-file)
              data (vscode/workspace.fs.readFile uri)
              text (.decode (js/TextDecoder. "utf-8") data)]
        text)
      (p/catch (fn [e]
                 (println "File not found:" ws-file e)
                 (throw (js/Error. e))))))