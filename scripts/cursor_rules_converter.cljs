;; Script for Converting Cursor Rules to GitHub Copilot Instructions
;; Based on: https://github.com/PatrickJS/awesome-cursorrules
;;
;; Joyride: https://github.com/BetterThanTomorrow/joyride
;; Install it from the Extensions pane in VS Code
;;
;; Install in Joyride as User script:
;; 1. Select all script code + Copy
;; 2. In VS Code Command Palette: Joyride: Create User Script...
;;    * Name it 'cursor-rules-converter'
;; 3. In the editor that opens: Select all + Paste
;;
;; Use, from any VS Code window:
;; 1. Command Palette: Joyride: Run User Script...
;; 2. Select 'cursor_rules_converter.cljs'
;;
;; Hack the script, make it your own
;; 1. Command palette: Joyride: Open User Joyride Directory in New Window

(ns cursor-rules-converter
  (:require ["vscode" :as vscode]
            ["path" :as path]
            ["fs" :as fs]
            [promesa.core :as p]
            [joyride.core :as joyride]))

(def INDEX-URL "https://pez.github.io/awesome-copilot-index/awesome-cursorrules.json")
(def CONTENT-BASE-URL "https://raw.githubusercontent.com/PatrickJS/awesome-cursorrules/main/")

;; Preference management for picker memory
(def PREFS-KEY "cursor-rules-converter-preferences")

(defn get-vscode-user-dir []
  (let [context (joyride/extension-context)
        global-storage-uri (.-globalStorageUri context)
        global-storage-path (.-fsPath global-storage-uri)]
    ;; Get the User directory, which is two levels up from the extension's globalStorage directory
    ;; The path structure is: User/globalStorage/extension-id
    (-> global-storage-path
        path/dirname
        path/dirname)))

(defn get-preferences []
  (let [context (joyride/extension-context)
        global-state (.-globalState context)
        stored (.get global-state PREFS-KEY)]
    (if stored
      (js->clj (js/JSON.parse stored) :keywordize-keys true)
      {})))

(defn save-preference [key value]
  (let [context (joyride/extension-context)
        global-state (.-globalState context)
        current-prefs (get-preferences)
        updated-prefs (assoc current-prefs key value)]
    (.update global-state PREFS-KEY (js/JSON.stringify (clj->js updated-prefs)))))

(defn get-preference [key default-value]
  (get (get-preferences) key default-value))

(defn show-picker-with-memory+
  [items {:keys [title placeholder preference-key match-fn save-fn]}]
  (let [last-choice (get-preference preference-key nil)
        items-js (clj->js items)
        picker (vscode/window.createQuickPick)]

    (set! (.-items picker) items-js)
    (set! (.-title picker) title)
    (set! (.-placeholder picker) placeholder)
    (set! (.-ignoreFocusOut picker) true)

    (when last-choice
      (when-let [active-index (some->> items-js
                                       (map-indexed vector)
                                       (some (fn [[idx item]]
                                               (when (match-fn item last-choice) idx))))]
        (set! (.-activeItems picker) #js [(aget items-js active-index)])))

    ;; Return a promise that handles the user interaction
    (js/Promise.
     (fn [resolve _reject]
       (.onDidAccept picker
                     (fn []
                       (let [selected (first (.-selectedItems picker))]
                         (.hide picker)
                         (when selected
                           (let [selected-clj (js->clj selected :keywordize-keys true)]
                             (save-preference preference-key (save-fn selected-clj))
                             (resolve selected-clj))))))
       (.onDidHide picker
                   (fn []
                     (resolve nil)))
       (.show picker)))))

;; Copilot output formats
(def conversion-targets
  [{:label "Instructions"
    :iconPath (vscode/ThemeIcon. "list-ordered")
    :description "GitHub Copilot Instructions"
    :detail "Convert to .github/copilot-instructions.md format"
    :format "instructions"}
   {:label "Prompts"
    :iconPath (vscode/ThemeIcon. "chevron-right")
    :description "GitHub Copilot Prompts"
    :detail "Convert to .github/prompts/*.prompt.md format"
    :format "prompts"}
   {:label "Chat Modes"
    :iconPath (vscode/ThemeIcon. "color-mode")
    :description "GitHub Copilot Chat Modes"
    :detail "Convert to .github/chatmodes/*.chatmode.md format"
    :format "chatmodes"}])

(defn fetch-index+ []
  (p/let [response (js/fetch INDEX-URL)
          data (.json response)
          clj-data (js->clj data :keywordize-keys true)]
    ;; Extract the actual rules from the cursor-rules key
    (get clj-data :cursor-rules)))

(defn fetch-component-content+ [link]
  (let [content-url (str CONTENT-BASE-URL link)]
    (p/let [response (js/fetch content-url)
            text (.text response)]
      text)))

(defn prepare-component-for-display [component]
  {:label (str (:tech-stack component) " - " (:domain component))
   :iconPath (vscode/ThemeIcon. "copilot")
   :description (str (:component-type component) " component")
   :detail (:description component)
   :component component})

(defn show-component-picker+ [components]
  (let [prepared-components (map prepare-component-for-display components)]
    (show-picker-with-memory+
     prepared-components
     {:title "Cursor Rules Converter"
      :placeholder "Select a cursor rule component to convert"
      :preference-key :last-component
      :match-fn (fn [item last-choice]
                  (= (-> item .-component .-link) (:link last-choice)))
      :save-fn (fn [selected-clj] (-> selected-clj :component))})))

(defn show-format-picker+ []
  (show-picker-with-memory+
   conversion-targets
   {:title "Cursor Rules Converter"
    :placeholder "Select output format"
    :preference-key :last-format
    :match-fn (fn [format-item last-choice] (= (.-format format-item) (name last-choice)))
    :save-fn :format}))

(defn parse-frontmatter 
  "Simple frontmatter parser - extracts YAML frontmatter from markdown content"
  [content]
  (if (.startsWith content "---")
    (let [parts (.split content "---")
          frontmatter-lines (when (> (count parts) 2)
                              (.split (get parts 1) "\n"))
          content-part (when (> (count parts) 2)
                         (.join (.slice parts 2) "---"))
          frontmatter (reduce (fn [acc line]
                                (let [trimmed (.trim line)]
                                  (if-not (empty? trimmed)
                                    (let [[key value] (.split trimmed ": ")]
                                      (assoc acc (keyword (.trim key)) (.trim value)))
                                    acc)))
                              {}
                              frontmatter-lines)]
      {:frontmatter frontmatter
       :content (.trim content-part)})
    {:frontmatter {}
     :content content}))

(defn convert-to-instructions 
  "Convert cursor rule to GitHub Copilot Instructions format"
  [component _content parsed]
  (let [tech-stack (:tech-stack component)
        domain (:domain component)
        description (:description component)
        rule-content (:content parsed)]
    
    (str "# " tech-stack " - " domain "\n\n"
         description "\n\n"
         "## Original Source\n"
         "- **Tech Stack**: " tech-stack "\n"
         "- **Domain**: " domain "\n"
         "- **Component Type**: " (:component-type component) "\n"
         "- **Source**: https://github.com/PatrickJS/awesome-cursorrules\n\n"
         "## Instructions\n\n"
         rule-content)))

(defn convert-to-prompt 
  "Convert cursor rule to GitHub Copilot Prompt format"
  [component _content parsed]
  (let [tech-stack (:tech-stack component)
        domain (:domain component)
        description (:description component)
        rule-content (:content parsed)]
    
    (str "---\n"
         "title: " tech-stack " - " domain "\n"
         "description: " description "\n"
         "authors:\n"
         "  - Awesome Cursor Rules Community\n"
         "tags:\n"
         "  - " (.toLowerCase (.replace tech-stack " " "-")) "\n"
         "  - " (.toLowerCase (.replace domain " " "-")) "\n"
         "---\n\n"
         rule-content)))

(defn convert-to-chatmode 
  "Convert cursor rule to GitHub Copilot Chat Mode format"
  [component _content parsed]
  (let [tech-stack (:tech-stack component)
        domain (:domain component)
        description (:description component)
        rule-content (:content parsed)]
    
    (str "---\n"
         "title: " tech-stack " - " domain "\n"
         "description: " description "\n"
         "behavior: |\n"
         "  " (.replace rule-content "\n" "\n  ") "\n"
         "tags:\n"
         "  - " (.toLowerCase (.replace tech-stack " " "-")) "\n"
         "  - " (.toLowerCase (.replace domain " " "-")) "\n"
         "---\n\n"
         "This chat mode applies " tech-stack " best practices for " domain ".\n\n"
         rule-content)))

(defn convert-content 
  "Convert cursor rule content to the specified format"
  [component content format]
  (let [parsed (parse-frontmatter content)]
    (case format
      "instructions" (convert-to-instructions component content parsed)
      "prompts" (convert-to-prompt component content parsed)
      "chatmodes" (convert-to-chatmode component content parsed)
      content))) ; fallback to original content

(defn get-filename 
  "Generate appropriate filename for the converted content"
  [component format]
  (let [tech-stack (-> (:tech-stack component)
                       (.toLowerCase)
                       (.replace #"[^a-z0-9]+" "-")
                       (.replace #"^-|-$" ""))
        domain (-> (:domain component)
                   (.toLowerCase)
                   (.replace #"[^a-z0-9]+" "-")
                   (.replace #"^-|-$" ""))]
    (case format
      "instructions" "copilot-instructions.md"
      "prompts" (str tech-stack "-" domain ".prompt.md")
      "chatmodes" (str tech-stack "-" domain ".chatmode.md")
      (str tech-stack "-" domain ".md"))))

(defn install-to-workspace! 
  "Install converted content to workspace"
  [converted-content component format]
  (if-let [workspace-folder (first vscode/workspace.workspaceFolders)]
    (let [workspace-path (-> workspace-folder .-uri .-fsPath)
          filename (get-filename component format)
          dir-path (case format
                     "instructions" (.join path workspace-path ".github")
                     "prompts" (.join path workspace-path ".github" "prompts")
                     "chatmodes" (.join path workspace-path ".github" "chatmodes")
                     workspace-path)]

      ;; Create directory if it doesn't exist
      (when-not (.existsSync fs dir-path)
        (.mkdirSync fs dir-path #js {:recursive true}))

      (let [file-path (.join path dir-path filename)]
        ;; Handle special case for instructions (might want to append)
        (if (and (= format "instructions") (.existsSync fs file-path))
          (p/let [choice (vscode/window.showQuickPick
                          (clj->js [{:label "Append"
                                     :iconPath (vscode/ThemeIcon. "add")
                                     :description "Add to existing instructions"}
                                    {:label "Replace"
                                     :iconPath (vscode/ThemeIcon. "replace-all")
                                     :description "Overwrite existing instructions"}])
                          #js {:placeHolder "File exists. How to proceed?"})]
            (when choice
              (let [choice-text (.-label choice)]
                (if (= choice-text "Append")
                  (let [existing-content (.readFileSync fs file-path #js {:encoding "utf-8"})
                        new-content (str existing-content "\n\n---\n\n" converted-content)]
                    (.writeFileSync fs file-path new-content)
                    (vscode/window.showInformationMessage
                     (str "Appended to " filename))
                    {:success true :path file-path :mode "append"})
                  (do
                    (.writeFileSync fs file-path converted-content)
                    (vscode/window.showInformationMessage
                     (str "Replaced " filename))
                    {:success true :path file-path :mode "replace"})))))
          
          ;; Create new file or overwrite
          (do
            (.writeFileSync fs file-path converted-content)
            (vscode/window.showInformationMessage
             (str "Created " filename " in " (case format
                                               "instructions" ".github/"
                                               "prompts" ".github/prompts/"
                                               "chatmodes" ".github/chatmodes/"
                                               "workspace")))
            {:success true :path file-path :mode "create"}))))

    ;; Error - no workspace folder
    (do
      (vscode/window.showErrorMessage "No workspace folder open")
      {:success false :error "No workspace folder"})))

(defn open-file+ 
  "Open the created file in VS Code"
  [file-path]
  (p/let [uri (vscode/Uri.file file-path)
          doc (vscode/workspace.openTextDocument uri)
          _ (vscode/window.showTextDocument doc)]
    {:success true}))

(defn show-preview 
  "Show a preview of the converted content"
  [content component format]
  (let [filename (get-filename component format)
        preview-content (str ";; Preview of converted content\n"
                             ";; Target file: " filename "\n"
                             ";; Format: " format "\n\n"
                             content)]
    (p/let [doc (vscode/workspace.openTextDocument #js {:content preview-content
                                                        :language "markdown"})
            _ (vscode/window.showTextDocument doc)]
      {:success true})))

(defn main []
  (p/catch
   (p/let [components (fetch-index+)
           selected-component (show-component-picker+ components)]
     (when selected-component
       (p/let [format-choice (show-format-picker+)]
         (when format-choice
           (p/let [content (fetch-component-content+ (-> selected-component :component :link))
                   converted-content (convert-content (:component selected-component) 
                                                     content 
                                                     (:format format-choice))
                   
                   ;; Show action menu
                   action (vscode/window.showQuickPick
                           (clj->js [{:label "Preview"
                                      :iconPath (vscode/ThemeIcon. "preview")
                                      :description "Show preview in editor"}
                                     {:label "Install to Workspace"
                                      :iconPath (vscode/ThemeIcon. "file-add")
                                      :description "Save to workspace .github directory"}])
                           #js {:placeHolder "What would you like to do?"})]
             
             (when action
               (let [action-text (.-label action)]
                 (if (= action-text "Preview")
                   (show-preview converted-content (:component selected-component) (:format format-choice))
                   (p/let [result (install-to-workspace! converted-content 
                                                        (:component selected-component) 
                                                        (:format format-choice))]
                     (when (:success result)
                       (open-file+ (:path result))))))))))))

   (fn [error]
     (vscode/window.showErrorMessage (str "Error in cursor-rules-converter: " (.-message error)))
     (js/console.error "Error in cursor-rules-converter:" error))))

;; Run the script directly when loaded, unless loaded in the REPL
(when (= (joyride/invoked-script) joyride/*file*)
  (main))
