(ns awesome-copilot
  (:require ["vscode" :as vscode]
            ["path" :as path]
            ["fs" :as fs]
            [promesa.core :as p]
            [joyride.core :as joyride]))

(def INDEX-URL "https://raw.githubusercontent.com/PEZ/awesome-copilot/refs/heads/pez/create-index-json/index.json")
(def CONTENT-BASE-URL "https://raw.githubusercontent.com/github/awesome-copilot/main/")

(defn get-vscode-user-dir []
  (let [context (joyride/extension-context)
        global-storage-uri (.-globalStorageUri context)
        global-storage-path (.-fsPath global-storage-uri)]
    ;; Get the User directory, which is two levels up from the extension's globalStorage directory
    ;; The path structure is: User/globalStorage/extension-id
    (-> global-storage-path
        path/dirname
        path/dirname)))

(def categories
  [{:label "📝 Instructions"
    :description "Coding styles and best practices"
    :detail "Guidelines for generating code that follows specific patterns"
    :category "instructions"}
   {:label "🔮 Prompts"
    :description "Task-specific templates"
    :detail "Pre-defined prompts for common tasks like testing, documentation, etc."
    :category "prompts"}
   {:label "💬 Chatmodes"
    :description "Conversation behavior settings"
    :detail "Configure how Copilot Chat behaves for different activities"
    :category "chatmodes"}])

(def actions
  [{:label "✨ View Content"
    :description "Open in untitled editor"
    :detail "Preview the markdown content in an editor"
    :action :view}
   {:label "🌍 Install Globally"
    :description "Save to user profile"
    :detail "Available across all your workspaces"
    :action :global}
   {:label "📁 Install in Workspace"
    :description "Save to this workspace only"
    :detail "Only available in this project"
    :action :workspace}])

(defn fetch-index []
  (p/let [response (js/fetch INDEX-URL)
          data (.json response)
          clj-data (js->clj data :keywordize-keys true)]
    clj-data))

(defn fetch-content [link]
  (let [content-url (str CONTENT-BASE-URL link)]
    (p/let [response (js/fetch content-url)
            text (.text response)]
      text)))

(defn show-category-picker []
  (p/let [selected (vscode/window.showQuickPick
                     (clj->js categories)
                     #js {:placeHolder "Select Awesome Copilot category"
                          :ignoreFocusOut true})]
    (when selected
      (js->clj selected :keywordize-keys true))))

(defn show-item-picker [items category-name]
  (p/let [items-js (clj->js
                    (map (fn [item]
                           {:label (:title item)
                            :description (:filename item)
                            :detail (:description item)
                            :item item})
                         items))
          selected (vscode/window.showQuickPick
                     items-js
                     #js {:placeHolder (str "Select a " category-name " item")
                          :matchOnDescription true
                          :matchOnDetail true
                          :ignoreFocusOut true})]
    (when selected
      (js->clj selected :keywordize-keys true))))

(defn show-action-menu [item]
  (p/let [selected (vscode/window.showQuickPick
                    (clj->js actions)
                    #js {:placeHolder (str "Action for " (-> item :item :title))
                         :ignoreFocusOut true})]
    (when selected
      (js->clj selected :keywordize-keys true))))

(defn open-in-untitled-editor [content _]
  (p/let [doc (vscode/workspace.openTextDocument #js {:content content
                                                      :language "markdown"})
          _ (vscode/window.showTextDocument doc)]
    {:success true}))

(defn install-globally [content item category]
  (p/let [vscode-user-dir (get-vscode-user-dir)
          dir-path (cond
                    ;; Instructions go in .vscode/instructions in user home
                    (= category "instructions")
                    (.join path (.. js/process -env -HOME) ".vscode" "instructions")

                    ;; Both prompts and chatmodes go in User/prompts folder
                    (or (= category "prompts") (= category "chatmodes"))
                    (.join path vscode-user-dir "prompts")

                    ;; Unknown category
                    :else nil)

          filename (-> item :item :filename)]

    (if dir-path
      (try
        (when-not (.existsSync fs dir-path)
          (.mkdirSync fs dir-path #js {:recursive true}))

        (let [file-path (.join path dir-path filename)]
          (.writeFileSync fs file-path content)
          (vscode/window.showInformationMessage
            (str "Installed " filename " to " (.-appName vscode/env) " User/prompts directory"))

          {:success true :path file-path})
        (catch :default err
          (vscode/window.showErrorMessage
            (str "Failed to install " filename ": " (.-message err)))
          {:success false :error (.-message err)}))

      (do
        (vscode/window.showErrorMessage
          (str "Unknown category: " category))
        {:success false :error (str "Unknown category: " category)}))))

(defn install-to-workspace [content item category]
  (if-let [workspace-folder (first vscode/workspace.workspaceFolders)]
    (let [filename (:filename (:item item))
          workspace-path (-> workspace-folder .-uri .-fsPath)
          dir-path (case category
                     "instructions" (.join path workspace-path ".github" "instructions")
                     "prompts" (.join path workspace-path ".github" "prompts")
                     "chatmodes" (.join path workspace-path ".github" "chatmodes")
                     nil)]

      (if dir-path
        (do
          (when-not (.existsSync fs dir-path)
            (.mkdirSync fs dir-path #js {:recursive true}))

          (let [file-path (.join path dir-path filename)]
            (.writeFileSync fs file-path content)
            (vscode/window.showInformationMessage
              (str "Installed " filename " to workspace"))

            {:success true :path file-path}))

        (do
          (vscode/window.showErrorMessage
            (str "Unknown category: " category))
          {:success false :error "Unknown category"})))

    (do
      (vscode/window.showErrorMessage "No workspace folder open")
      {:success false :error "No workspace folder"})))

;; Special handling for instructions to copilot-instructions.md
(defn install-to-copilot-instructions [content item]
  (if-let [workspace-folder (first vscode/workspace.workspaceFolders)]
    (let [workspace-path (-> workspace-folder .-uri .-fsPath)
          github-dir (.join path workspace-path ".github")
          file-path (.join path github-dir "copilot-instructions.md")]

      ;; Create .github directory if it doesn't exist
      (when-not (.existsSync fs github-dir)
        (.mkdirSync fs github-dir #js {:recursive true}))

      ;; Check if file already exists for append vs create
      (if (.existsSync fs file-path)
        ;; Append mode
        (p/let [choice (vscode/window.showQuickPick
                         (clj->js [{:label "Append"
                                    :description "Add to existing instructions"}
                                   {:label "Replace"
                                    :description "Overwrite existing instructions"}])
                         #js {:placeHolder "How to install to copilot-instructions.md?"})
                choice-clj (when choice (js->clj choice :keywordize-keys true))
                choice-text (when choice-clj (:label choice-clj))]
          (cond
            (= choice-text "Append")
            (let [existing-content (.readFileSync fs file-path #js {:encoding "utf-8"})
                  new-content (str existing-content "\n\n" content)]
              (.writeFileSync fs file-path new-content)
              (vscode/window.showInformationMessage
                (str "Appended " (-> item :item :filename) " to copilot-instructions.md"))
              {:success true :path file-path :mode "append"})

            (= choice-text "Replace")
            (do
              (.writeFileSync fs file-path content)
              (vscode/window.showInformationMessage
                "Replaced copilot-instructions.md")
              {:success true :path file-path :mode "replace"})

            :else
            {:success false :error "Cancelled or no choice made"}))

        ;; Create new file
        (do
          (.writeFileSync fs file-path content)
          (vscode/window.showInformationMessage
            "Created copilot-instructions.md")
          {:success true :path file-path :mode "create"})))

    ;; Error - no workspace folder
    (do
      (vscode/window.showErrorMessage "No workspace folder open")
      {:success false :error "No workspace folder"})))

(defn open-installed-file [file-path]
  (p/let [uri (vscode/Uri.file file-path)
          doc (vscode/workspace.openTextDocument uri)
          _ (vscode/window.showTextDocument doc)]
    {:success true}))

(defn execute-action [item action-type category]
  (p/let [content (fetch-content (-> item :item :link))]
    (case (keyword action-type)
      :view
      (open-in-untitled-editor content (-> item :item :filename))

      :global
      (p/let [result (install-globally content item category)]
        (when (:success result)
          (open-installed-file (:path result)))
        result)

      :workspace
      (if (= category "instructions")
        (p/let [choice (vscode/window.showQuickPick
                         (clj->js [{:label "GitHub Instructions Directory"
                                    :description ".github/instructions/"}
                                   {:label "Copilot Instructions File"
                                    :description ".github/copilot-instructions.md"}])
                         #js {:placeHolder "Where to install?"})
                choice-clj (when choice (js->clj choice :keywordize-keys true))
                choice-text (when choice-clj (:label choice-clj))]
          (if (= choice-text "Copilot Instructions File")
            (p/let [result (install-to-copilot-instructions content item)]
              (when (:success result)
                (open-installed-file (:path result)))
              result)
            (p/let [result (install-to-workspace content item category)]
              (when (:success result)
                (open-installed-file (:path result)))
              result)))
        (p/let [result (install-to-workspace content item category)]
          (when (:success result)
            (open-installed-file (:path result)))
          result))

      ;; Unknown action
      (do
        (vscode/window.showErrorMessage (str "Unknown action: " action-type))
        {:success false :error "Unknown action"}))))

(defn main []
  (p/catch
    (p/let [index (fetch-index)
            category (show-category-picker)]
      (when category
        (p/let [category-name (:category category)
                category-items (get index (keyword category-name))
                item (show-item-picker category-items (subs category-name 0 (dec (count category-name))))]
          (when item
            (p/let [action (show-action-menu item)]
              (when action
                (execute-action item (:action action) category-name)))))))

    (fn [error]
      (vscode/window.showErrorMessage (str "Error: " (.-message error)))
      (js/console.error "Error in awesome-copilot:" error))))

;; Run the script directly when loaded, unless loaded in the REPL
(when (= (joyride/invoked-script) joyride/*file*)
  (main))
