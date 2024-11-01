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

(defn- find-files!+ []
  (p/let [excludes (configured-exclude-patterns)]
    (vscode/workspace.findFiles "**/*" excludes)))

(defn- uri->line-items!+ [uri]
  (p/let [data (vscode/workspace.fs.readFile uri)
          relative-path (vscode/workspace.asRelativePath uri)]
    (if (.includes data 0) ; Excludes binary files
      []
      (let [lines (-> data (.toString "utf8") (.split "\n"))]
        (keep-indexed (fn [idx line]
                        (when-not (string/blank? line)
                          #js {:label (str (.trim line) " - " relative-path)
                               :detail (str relative-path ", Line: " (inc idx))
                               :uri uri
                               :range (vscode/Range. idx (.search line #"\S") idx (count line))}))
                      lines)))))

(defn- uris->line-items!+ [uris]
  (p/let [line-items (p/all (map uri->line-items!+ uris))]
    (apply concat line-items)))

(def ^:private !decorated-editor (atom nil))

(def line-decoration-type
  (vscode/window.createTextEditorDecorationType #js {:backgroundColor "rgba(255,255,255,0.15)"}))

(defn- clear-decorations! [editor]
  (.setDecorations editor line-decoration-type #js []))

(defn- highlight-item! [item preview?]
  (when (some-> item .-range)
    (p/let [document (vscode/workspace.openTextDocument (.-uri item))
            editor (vscode/window.showTextDocument document #js {:preview preview? :preserveFocus preview?})
            range (.-range item)]
      (.revealRange editor range vscode/TextEditorRevealType.InCenter)
      (clear-decorations! editor)
      (if preview?
        (do
          (.setDecorations editor line-decoration-type #js [range])
          (reset! !decorated-editor editor))
        (set! (.-selection editor)
              (vscode/Selection. (.-start range) (.-start range)))))))

(defn show-search-box! []
  (p/let [uris (find-files!+)
          all-items (uris->line-items!+ uris)
          _ (.appendLine (joyride/output-channel) (str "Fuzzy lines: " (count all-items)))
          quick-pick (vscode/window.createQuickPick)]
    (set! (.-items quick-pick) (into-array (concat [#js {:description (str "LOC: " (count all-items))}] all-items)))
    (set! (.-title quick-pick) "Fuzzy file search")
    (set! (.-placeHolder quick-pick) "Use `foo*bar` to match `foo<whatever>bar`")
    (set! (.-matchOnDetail quick-pick) true)
    (doto quick-pick
      (.onDidChangeActive (fn [active-items]
                            (highlight-item! (first active-items) true)))
      (.onDidAccept (fn []
                      (highlight-item! (first (.-selectedItems quick-pick)) false)
                      (.dispose quick-pick)))
      (.onDidHide (fn [e]
                    (.appendLine (joyride/output-channel) (str "Fuzzy search cancelled: " e))
                    (when-let [editor @!decorated-editor]
                      (clear-decorations! editor))))
      (.show))))

(when (= (joyride/invoked-script) joyride/*file*)
  (show-search-box!))
