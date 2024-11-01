(ns fuzzy
  (:require ["vscode" :as vscode]
            [clojure.string :as string]
            [joyride.core :as joyride]
            [promesa.core :as p]))

(defn- configured-exclude-patterns []
  (str "{"
       (->> ["search.exclude" "files.exclude"]
            (mapcat (fn [config-key]
                      (-> (.get (vscode/workspace.getConfiguration) config-key)
                          (js/Object.entries)
                          (.filter (fn [[_k v]] v))
                          (.map (fn [[k _v]] k)))))
            (string/join ","))
       "}"))

(defn- uri->line-items!+ [uri]
  (p/let [data (vscode/workspace.fs.readFile uri)
          relative-path (vscode/workspace.asRelativePath uri)]
    (if (.includes data 0) ; Excludes binary files
      []
      (let [lines (-> data
                      (.toString "utf8")
                      (.split "\n")
                      (.map #(.trim %)))]
        (keep-indexed (fn [idx line]
                        (when-not (string/blank? line)
                          #js {:label (str line " - " relative-path)
                               :detail (str relative-path ", Line: " (inc idx))
                               :uri uri
                               :range (vscode/Range. idx 0 idx (count line))}))
                      lines)))))

(defn- uris->line-items!+ [uris]
  (p/let [line-items (p/all (map uri->line-items!+ uris))]
    (apply concat line-items)))

(defn- find-files!+ []
  (p/let [excludes (configured-exclude-patterns)]
    (vscode/workspace.findFiles "**/*" excludes)))

(defn- reveal-picked! [selected-item]
  (when selected-item
    (p/let [document (vscode/workspace.openTextDocument (.-uri selected-item))
            editor (vscode/window.showTextDocument document)
            range (.-range selected-item)]
      (.revealRange editor range vscode/TextEditorRevealType.InCenter)
      (set! (.-selection editor)
            (vscode/Selection. (.-start range) (.-start range))))))

(defn show-search-box! []
  (p/let [uris (find-files!+)
          all-items (uris->line-items!+ uris)
          _ (.appendLine (joyride/output-channel) (str "lines: " (count all-items)))
          pick (vscode/window.showQuickPick (into-array all-items)
                                            #js {:title "Fuzzy file search"
                                                 :placeHolder "Use `foo*bar` to match `foo<whatever>bar`"
                                                 :matchOnDescription true
                                                 :matchOnDetail true})]
    (reveal-picked! pick)))

(when (= (joyride/invoked-script) joyride/*file*)
  (show-search-box!))
