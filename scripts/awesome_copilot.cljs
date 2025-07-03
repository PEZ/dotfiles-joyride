(ns awesome-copilot
  (:require ["vscode" :as vscode]
            ["path" :as path]
            ["fs" :as fs]
            [promesa.core :as p]
            [joyride.core :as joyride]))

;; Constants for URLs
(def INDEX-URL "https://raw.githubusercontent.com/PEZ/awesome-copilot/refs/heads/pez/create-index-json/index.json")
(def CONTENT-BASE-URL "https://raw.githubusercontent.com/github/awesome-copilot/main/")

;; Helper function to get VS Code user directory
(defn get-vscode-user-dir []
  (let [app-name (.-appName vscode/env)
        is-insiders (.includes app-name "Insiders")
        code-dir-name (if is-insiders "Code - Insiders" "Code")
        user-home (.. js/process -env -HOME)]
    (if (= (.-platform js/process) "darwin")
      (.join path user-home "Library" "Application Support" code-dir-name "User")
      (.join path user-home ".config" code-dir-name "User"))))

;; Category definitions with UI metadata
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

;; Action definitions with UI metadata
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

;; Fetch and parse the index.json from GitHub
(defn fetch-index []
  (p/let [response (js/fetch INDEX-URL)
          data (.json response)
          clj-data (js->clj data :keywordize-keys true)]
    clj-data))

;; Fetch content of a specific item by its link
(defn fetch-content [link]
  (let [content-url (str CONTENT-BASE-URL link)]
    (p/let [response (js/fetch content-url)
            text (.text response)]
      text)))

;; Display the category picker menu
(defn show-category-picker []
  (p/let [selected (vscode/window.showQuickPick
                     (clj->js categories)
                     #js {:placeHolder "Select Awesome Copilot category"
                          :ignoreFocusOut true})]
    (when selected
      (js->clj selected :keywordize-keys true))))

;; Display the item picker menu for a specific category
(defn show-item-picker [items category-name]
  (p/let [items-js (clj->js
                     (mapv (fn [item]
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

;; Display the action menu for a selected item
(defn show-action-menu [item]
  (p/let [selected (vscode/window.showQuickPick
                    (clj->js actions)
                    #js {:placeHolder (str "Action for " (:title (:item item)))
                         :ignoreFocusOut true})]
    (when selected
      (js->clj selected :keywordize-keys true))))

;; Open content in untitled editor
(defn open-in-untitled-editor [content _]
  (p/let [doc (vscode/workspace.openTextDocument #js {:content content
                                                      :language "markdown"})
          _ (vscode/window.showTextDocument doc)]
    {:success true}))

;; Install content to global location based on category type
(defn install-globally [content item category]
  (p/let [;; Get VS Code user directory
          vscode-user-dir (get-vscode-user-dir)
          
          ;; Get current profile if any - keeping for future reference
          config (vscode/workspace.getConfiguration)
          _ (.get config "workbench.profiles.name")
          
          ;; Build path based on category
          dir-path (cond
                    (= category "instructions") (.join path (.. js/process -env -HOME) ".vscode" "instructions")
                    (= category "prompts") (.join path vscode-user-dir "prompts")
                    (= category "chatmodes") (.join path vscode-user-dir "prompts") ;; Yes, chatmodes go in prompts folder
                    :else nil)
          
          ;; Get the filename
          filename (:filename (:item item))]
      
    (when dir-path
      ;; Create directory if it doesn't exist
      (when-not (.existsSync fs dir-path)
        (.mkdirSync fs dir-path #js {:recursive true}))

      ;; Write file
      (let [file-path (.join path dir-path filename)]
        (.writeFileSync fs file-path content)
        (vscode/window.showInformationMessage
          (str "Installed " filename " to " (.-appName vscode/env) " User/prompts directory"))

        {:success true :path file-path}))))

;; Install content to workspace based on category type
(defn install-to-workspace [content item category]
  (if-let [workspace-folder (first vscode/workspace.workspaceFolders)]
    (let [filename (:filename (:item item))
          workspace-path (-> workspace-folder .-uri .-fsPath)

          ;; Determine directory based on category
          dir-path (cond
                     (= category "instructions") (.join path workspace-path ".github" "instructions")
                     (= category "prompts") (.join path workspace-path ".github" "prompts")
                     (= category "chatmodes") (.join path workspace-path ".github" "chatmodes")
                     :else nil)]

      (if dir-path
        (do
          ;; Create directory if it doesn't exist
          (when-not (.existsSync fs dir-path)
            (.mkdirSync fs dir-path #js {:recursive true}))

          ;; Write file
          (let [file-path (.join path dir-path filename)]
            (.writeFileSync fs file-path content)
            (vscode/window.showInformationMessage
              (str "Installed " filename " to workspace"))

            {:success true :path file-path}))

        ;; Error - unknown category
        (do
          (vscode/window.showErrorMessage
            (str "Unknown category: " category))
          {:success false :error "Unknown category"})))

    ;; Error - no workspace folder
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

;; Open file after installation
(defn open-installed-file [file-path]
  (p/let [uri (vscode/Uri.file file-path)
          doc (vscode/workspace.openTextDocument uri)
          _ (vscode/window.showTextDocument doc)]
    {:success true}))

;; Execute action for a selected item and category
(defn execute-action [item action-type category]
  (p/let [content (fetch-content (-> item :item :link))]
    (case (keyword action-type)
      :view (open-in-untitled-editor content (-> item :item :filename))

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

      ;; Default case - unknown action
      (do
        (vscode/window.showErrorMessage (str "Unknown action: " action-type))
        {:success false :error "Unknown action"}))))

;; Main command function
(defn awesome-copilot []
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

    ;; Error handler
    (fn [error]
      (vscode/window.showErrorMessage (str "Error: " (.-message error)))
      (js/console.error "Error in awesome-copilot:" error))))

;; Run the script directly when loaded
(when (= (joyride/invoked-script) joyride/*file*)
  (awesome-copilot))
