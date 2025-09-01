(ns prompt-sync
  (:require ["vscode" :as vscode]
            ["path" :as path]
            [promesa.core :as p]
            [joyride.core :as joyride]))

;; VS Code FileType constants for semantic clarity
(def ^:const VSCODE-FILE-TYPE vscode/FileType.File)

(def ^:dynamic *log-level* :debug)

(defn log!
  [level & messages]
  (when (or (= :info level)
            (= :debug *log-level*))
    (apply println (conj (vec messages) "\n"))))

(defn get-vscode-user-dir
  "Gets the VS Code User directory using Joyride extension context"
  []
  (let [context (joyride/extension-context)
        global-storage-uri (.-globalStorageUri context)
        global-storage-path (.-fsPath global-storage-uri)]
    ;; User directory is two levels up from globalStorage
    (-> global-storage-path
        path/dirname
        path/dirname)))

(defn get-user-prompts-dirs
  "Returns configuration map with stable and insiders prompt directory paths"
  ([] (get-user-prompts-dirs {}))
  ([{:prompt-sync/keys [test-mode?]}]
   (if test-mode?
     {:prompt-sync/stable-dir "/tmp/prompt-sync-test/stable/prompts"
      :prompt-sync/insiders-dir "/tmp/prompt-sync-test/insiders/prompts"
      :prompt-sync/current-is-insiders? false
      :prompt-sync/test-mode? true}
     (let [current-user-dir (get-vscode-user-dir)
           is-insiders? (.includes current-user-dir "Insiders")
           stable-dir (if is-insiders?
                        (.replace current-user-dir "Code - Insiders" "Code")
                        current-user-dir)
           insiders-dir (if is-insiders?
                          current-user-dir
                          (.replace current-user-dir "Code" "Code - Insiders"))]
       {:prompt-sync/stable-dir (path/join stable-dir "prompts")
        :prompt-sync/insiders-dir (path/join insiders-dir "prompts")
        :prompt-sync/current-is-insiders? is-insiders?
        :prompt-sync/test-mode? false}))))

(defn classify-file-type
  "Determines file type from filename for appropriate icon"
  [filename]
  (cond
    (.endsWith filename "prompt.md") :prompt-sync.type/prompt
    (.endsWith filename "chatmode.md") :prompt-sync.type/chatmode
    :else :prompt-sync.type/instruction))

(defn scan-directory!+
  "Scans directory for .md files using workspace.fs, returns promise"
  [{:prompt-sync/keys [dir-path]}]
  (let [dir-uri (vscode/Uri.file dir-path)]
    (-> (vscode/workspace.fs.readDirectory dir-uri)
        (.then (fn [entries]
                 (->> entries
                      (js->clj)
                      (filter (fn [[name type]]
                                (and (= type VSCODE-FILE-TYPE) ; Use semantic constant instead of magic number
                                     (.endsWith name ".md"))))
                      (map (fn [[filename _]]
                             {:prompt-sync.file/filename filename
                              :prompt-sync.file/path (path/join dir-path filename)
                              :prompt-sync.file/uri (vscode/Uri.file (path/join dir-path filename))
                              :prompt-sync.file/file-type (classify-file-type filename)})))))
        (.catch (fn [err]
                  (if (= (.-code err) "FileNotFound")
                    [] ; Return empty array if directory doesn't exist
                    (throw err)))))))

(defn load-file-content!+
  "Loads file content using workspace.fs, returns promise with updated file-info"
  [file-info]
  (let [decoder (js/TextDecoder.)
        {:prompt-sync.file/keys [uri]} file-info]
    (-> (vscode/workspace.fs.readFile uri)
        (.then (fn [uint8array]
                 (assoc file-info :prompt-sync.file/content (.decode decoder uint8array))))
        (.catch (fn [err]
                  (assoc file-info
                         :prompt-sync.file/content nil
                         :prompt-sync.file/error (.-message err)))))))

(defn compare-directories!+
  "Compares two directories and returns flat file list with statuses and :original-status metadata"
  [{:prompt-sync/keys [stable-dir insiders-dir]}]
  (p/let [stable-files (scan-directory!+ {:prompt-sync/dir-path stable-dir})
          insiders-files (scan-directory!+ {:prompt-sync/dir-path insiders-dir})

          ;; Load content for all files
          stable-with-content (p/all (map load-file-content!+ stable-files))
          insiders-with-content (p/all (map load-file-content!+ insiders-files))

          ;; Create lookup maps by filename
          stable-map (into {} (map (fn [f] [(:prompt-sync.file/filename f) f]) stable-with-content))
          insiders-map (into {} (map (fn [f] [(:prompt-sync.file/filename f) f]) insiders-with-content))

          filenames (set (concat (keys stable-map) (keys insiders-map)))]
    ;; Return flat list with statuses and :original-status metadata
    (sort-by :prompt-sync.file/filename
             (map (fn [filename]
                    (let [stable-file (get stable-map filename)
                          insiders-file (get insiders-map filename)]
                      (cond
                        ;; File only in stable
                        (and stable-file (not insiders-file))
                        (assoc stable-file
                               :prompt-sync.file/status :missing-in-insiders
                               :prompt-sync.file/action-needed :copy-to-insiders
                               :prompt-sync.file/original-status :original/missing-in-insiders)

                        ;; File only in insiders
                        (and insiders-file (not stable-file))
                        (assoc insiders-file
                               :prompt-sync.file/status :missing-in-stable
                               :prompt-sync.file/action-needed :copy-to-stable
                               :prompt-sync.file/original-status :original/missing-in-stable)

                        ;; File in both
                        (and stable-file insiders-file)
                        (merge stable-file
                               {:prompt-sync.file/insiders-file insiders-file}
                               (if (= (:prompt-sync.file/content stable-file)
                                      (:prompt-sync.file/content insiders-file))
                                 {:prompt-sync.file/status :identical
                                  :prompt-sync.file/action-needed :none
                                  :prompt-sync.file/original-status :original/identical}
                                 {:prompt-sync.file/status :conflict
                                  :prompt-sync.file/action-needed :resolve
                                  :prompt-sync.file/original-status :original/conflict})))))
                  filenames))))

(defn copy-file!+
  "Copies file using workspace.fs"
  [{:prompt-sync/keys [source-uri target-uri]}]
  (-> (vscode/workspace.fs.readFile source-uri)
      (.then (fn [content]
               (vscode/workspace.fs.writeFile target-uri content)))))

(defn copy-missing-files!+
  "Copies files that need copying and updates their status using :original-status metadata"
  [all-files {:prompt-sync/keys [stable-dir insiders-dir]}]
  (p/let [files-to-copy (filter #(#{:missing-in-stable :missing-in-insiders}
                                  (:prompt-sync.file/status %)) all-files)

          ;; Execute all copy operations
          _ (p/all (map (fn [file-info]
                          (case (:prompt-sync.file/status file-info)
                            :missing-in-stable
                            (copy-file!+ {:prompt-sync/source-uri (:prompt-sync.file/uri file-info)
                                          :prompt-sync/target-uri (vscode/Uri.file
                                                                   (path/join stable-dir (:prompt-sync.file/filename file-info)))})
                            :missing-in-insiders
                            (copy-file!+ {:prompt-sync/source-uri (:prompt-sync.file/uri file-info)
                                          :prompt-sync/target-uri (vscode/Uri.file
                                                                   (path/join insiders-dir (:prompt-sync.file/filename file-info)))})))
                        files-to-copy))]

    ;; Transform statuses using :original-status metadata - clean transformation
    (map (fn [file-info]
           (case (:prompt-sync.file/status file-info)
             :missing-in-stable
             (assoc file-info
                    :prompt-sync.file/status :copied
                    :prompt-sync.file/action-needed :none)

             :missing-in-insiders
             (assoc file-info
                    :prompt-sync.file/status :copied
                    :prompt-sync.file/action-needed :none)

             ;; Other statuses unchanged
             file-info))
         all-files)))

;; Obsolete functions removed - we now have flat data from the start!
;; The bucket-based approach required complex transformations that are no longer needed.

(defn show-diff-preview!+
  "Opens VS Code diff editor for conflict preview with default positioning"
  [{:prompt-sync.file/keys [filename uri insiders-file]}]
  (let [stable-uri uri  ; Current file is the stable version
        insiders-uri (:prompt-sync.file/uri insiders-file)
        title (str "Diff: " filename " (Stable ↔ Insiders)")]
    (vscode/commands.executeCommand "vscode.diff"
                                    stable-uri
                                    insiders-uri
                                    title
                                    #js {:preview true
                                         :preserveFocus true})))

(defn get-file-icon
  "Gets appropriate VS Code icon for file type"
  [file-type]
  (case file-type
    :prompt-sync.type/instruction (vscode/ThemeIcon. "list-ordered")
    :prompt-sync.type/prompt (vscode/ThemeIcon. "chevron-right")
    :prompt-sync.type/chatmode (vscode/ThemeIcon. "color-mode")
    (vscode/ThemeIcon. "diff")))

(defn create-picker-item
  "Creates QuickPick item using :original-status for copy direction display"
  [{:prompt-sync.file/keys [filename status file-type action-needed original-status]}]
  (let [icon (get-file-icon file-type)
        direction-string (case original-status
                           :original/missing-in-insiders "Stable → Insiders"
                           :original/missing-in-stable "Insiders → Stable"
                           "copied")
        detail (case status
                 :copied  direction-string
                 :conflict (str (name file-type) " • has conflicts")
                 :identical "identical"
                 :missing-in-stable (str (name file-type) " • missing in stable")
                 :missing-in-insiders (str (name file-type) " • missing in insiders")
                 :resolved (str "resolved: " direction-string)
                 (str (name file-type) " • " (name status)))
        description (case action-needed
                      :resolve "Select to choose resolution"
                      :copy-to-stable "Will be copied to stable"
                      :copy-to-insiders "Will be copied to insiders"
                      :none "Preview only"
                      "")]
    #js {:label filename
         :iconPath icon
         :description description
         :detail detail
         :fileInfo #js {:filename filename
                        :status (name status)
                        :file-type (name file-type)
                        :isConflict (= status :conflict)}}))

(defn show-file-preview!+
  "Shows file preview for non-conflict files"
  [{:prompt-sync.file/keys [uri insiders-file]}]
  (let [file-uri (or uri (:prompt-sync.file/uri insiders-file))]
    (when file-uri
      (vscode/commands.executeCommand "vscode.open" file-uri #js {:preview true
                                                                  :preserveFocus true}))))

(defn show-all-files-picker!+
  "Shows QuickPick for all files with appropriate preview and selection behavior"
  [all-files]
  (if (empty? all-files)
    (p/resolved nil)
    (let [items (map create-picker-item all-files)
          picker (vscode/window.createQuickPick)]

      (set! (.-items picker) (into-array items))
      (set! (.-title picker) "Prompt Sync - All Files")
      (set! (.-placeholder picker) "Select conflicts to resolve, others for preview")
      (set! (.-ignoreFocusOut picker) true)

      ;; Live preview on active item change
      (.onDidChangeActive picker
                          (fn [active-items]
                            (when-let [first-item (first active-items)]
                              (let [file-info (.-fileInfo first-item)
                                    filename (.-filename file-info)
                                    is-conflict (.-isConflict file-info)]
                                (if is-conflict
                                  ;; Show diff for conflicts
                                  (let [conflict-info (first (filter #(= (:prompt-sync.file/filename %) filename) all-files))]
                                    (when conflict-info
                                      (show-diff-preview!+ conflict-info)))
                                  ;; Show simple preview for non-conflicts
                                  (let [file-data (first (filter #(= (:prompt-sync.file/filename %) filename) all-files))]
                                    (when file-data
                                      (show-file-preview!+ file-data))))))))

      (js/Promise.
       (fn [resolve _reject]
         (.onDidAccept picker
                       (fn []
                         (when-let [selected (first (.-selectedItems picker))]
                           (let [file-info (.-fileInfo selected)
                                 is-conflict (.-isConflict file-info)]
                             (if is-conflict
                               ;; Return conflict data for resolution
                               (let [filename (.-filename file-info)
                                     conflict-info (first (filter #(= (:prompt-sync.file/filename %) filename) all-files))]
                                 (.hide picker)
                                 (resolve conflict-info))
                               ;; For non-conflicts, keep picker open (don't resolve)
                               (log! :debug "📁 Preview only:" (.-filename file-info)))))))
         (.onDidHide picker
                     (fn []
                       (resolve nil)))
         (.show picker))))))

(defn show-resolution-menu!+
  "Shows resolution options menu"
  [{:prompt-sync.file/keys [filename]}]
  (log! :debug "📋 show-resolution-menu!+ called for:" filename)
  (let [actions [{:label "Choose Stable"
                  :iconPath (vscode/ThemeIcon. "arrow-left")
                  :description "Copy stable version to insiders"
                  :action "prompt-sync.action/choose-stable"}
                 {:label "Choose Insiders"
                  :iconPath (vscode/ThemeIcon. "arrow-right")
                  :description "Copy insiders version to stable"
                  :action "prompt-sync.action/choose-insiders"}
                 {:label "Skip"
                  :iconPath (vscode/ThemeIcon. "close")
                  :description "Leave both files as-is"
                  :action "prompt-sync.action/skip"}]]
    (-> (vscode/window.showQuickPick
         (clj->js actions)
         #js {:placeHolder (str "How to resolve: " filename)
              :ignoreFocusOut true})
        (.then (fn [choice]
                 (log! :debug "📋 User selected:" (when choice (.-label choice)) "for" filename)
                 (log! :debug "📋 Raw action string:" (when choice (.-action choice)))
                 (when choice
                   (let [action-keyword (keyword (.-action choice))]
                     (log! :debug "📋 Action as keyword:" action-keyword)
                     action-keyword)))))))

(defn resolve-conflict!+
  "Executes the chosen resolution action, returns result data"
  [conflict choice]
  (log! :debug "🔧 resolve-conflict!+ called with:")
  (log! :debug "  Choice:" choice)
  (log! :debug "  Choice type:" (type choice))
  (log! :debug "  Conflict filename:" (:prompt-sync.file/filename conflict))
  (let [{:prompt-sync.file/keys [uri insiders-file filename]} conflict
        stable-uri uri  ; Current file is the stable version
        insiders-uri (:prompt-sync.file/uri insiders-file)]
    (log! :debug "  Stable file URI:" stable-uri)
    (log! :debug "  Insiders file URI:" insiders-uri)
    (case choice
      :prompt-sync.action/choose-stable
      (do (log! :debug "  → Copying stable to insiders")
          (p/let [_ (copy-file!+ {:prompt-sync/source-uri stable-uri
                                  :prompt-sync/target-uri insiders-uri})]
            {:prompt-sync.resolution/action :choose-stable :prompt-sync.resolution/filename filename :prompt-sync.resolution/success true}))

      :prompt-sync.action/choose-insiders
      (do (log! :debug "  → Copying insiders to stable")
          (p/let [_ (copy-file!+ {:prompt-sync/source-uri insiders-uri
                                  :prompt-sync/target-uri stable-uri})]
            {:prompt-sync.resolution/action :choose-insiders :prompt-sync.resolution/filename filename :prompt-sync.resolution/success true}))

      :prompt-sync.action/skip
      (do (log! :debug "  → Skipping")
          (p/resolved {:prompt-sync.resolution/action :skip :prompt-sync.resolution/filename filename :prompt-sync.resolution/success true}))

      (do (log! :debug "  → Cancelled")
          (p/resolved {:prompt-sync.resolution/action :cancelled :prompt-sync.resolution/filename filename :prompt-sync.resolution/success false})))))

(defn create-test-environment!+
  "Creates test directories and sample files for safe testing"
  []
  (let [test-base "/tmp/prompt-sync-test"
        test-stable (path/join test-base "stable" "prompts")
        test-insiders (path/join test-base "insiders" "prompts")
        base-uri (vscode/Uri.file test-base)
        stable-uri (vscode/Uri.file test-stable)
        insiders-uri (vscode/Uri.file test-insiders)]
    (-> (vscode/workspace.fs.createDirectory base-uri)
        (.then (fn [_] (vscode/workspace.fs.createDirectory stable-uri)))
        (.then (fn [_] (vscode/workspace.fs.createDirectory insiders-uri)))
        (.then (fn [_]
                 (log! :debug "Created test environment:")
                 (log! :debug "Stable:" test-stable)
                 (log! :debug "Insiders:" test-insiders)
                 {:prompt-sync.env/stable test-stable :prompt-sync.env/insiders test-insiders})))))

(defn populate-test-files!+
  "Creates sample test files for different sync scenarios"
  [dirs files]
  (let [encoder (js/TextEncoder.)]

    (p/all
     (for [file files]
       (cond
         ;; Identical files - create in both directories (has :content, no :location)
         (and (:prompt-sync.file/content file) (not (:prompt-sync.file/location file)))
         (let [content (.encode encoder (:prompt-sync.file/content file))
               stable-uri (vscode/Uri.file (path/join (:prompt-sync.env/stable dirs) (:prompt-sync.file/filename file)))
               insiders-uri (vscode/Uri.file (path/join (:prompt-sync.env/insiders dirs) (:prompt-sync.file/filename file)))]
           (-> (vscode/workspace.fs.writeFile stable-uri content)
               (.then (fn [_] (vscode/workspace.fs.writeFile insiders-uri content)))))

         ;; Conflict files - create different versions (has :stable-content and :insiders-content)
         (:prompt-sync.file/stable-content file)
         (let [stable-content (.encode encoder (:prompt-sync.file/stable-content file))
               insiders-content (.encode encoder (:prompt-sync.file/insiders-content file))
               stable-uri (vscode/Uri.file (path/join (:prompt-sync.env/stable dirs) (:prompt-sync.file/filename file)))
               insiders-uri (vscode/Uri.file (path/join (:prompt-sync.env/insiders dirs) (:prompt-sync.file/filename file)))]
           (-> (vscode/workspace.fs.writeFile stable-uri stable-content)
               (.then (fn [_] (vscode/workspace.fs.writeFile insiders-uri insiders-content)))))

         ;; Single location files - only create in specified location
         (= (:prompt-sync.file/location file) :stable-only)
         (let [content (.encode encoder (:prompt-sync.file/content file))
               stable-uri (vscode/Uri.file (path/join (:prompt-sync.env/stable dirs) (:prompt-sync.file/filename file)))]
           (vscode/workspace.fs.writeFile stable-uri content))

         (= (:prompt-sync.file/location file) :insiders-only)
         (let [content (.encode encoder (:prompt-sync.file/content file))
               insiders-uri (vscode/Uri.file (path/join (:prompt-sync.env/insiders dirs) (:prompt-sync.file/filename file)))]
           (vscode/workspace.fs.writeFile insiders-uri content)))))))

(defn cleanup-test-environment!+
  "Removes test environment when done"
  []
  (let [test-base-uri (vscode/Uri.file "/tmp/prompt-sync-test")]
    (-> (vscode/workspace.fs.delete test-base-uri #js {:recursive true :useTrash false})
        (.then (fn [_] (log! :debug "Cleaned up test environment")))
        (.catch (fn [err] (log! :debug "Cleanup error:" (.-message err)))))))

(defn update-file-status-after-resolution
  "Pure function for updating file status after conflict resolution in flat list"
  [all-files resolved-filename resolution-type]
  (map (fn [file-info]
         (if (= (:prompt-sync.file/filename file-info) resolved-filename)
           (assoc file-info
                  :prompt-sync.file/status :resolved
                  :prompt-sync.file/resolution resolution-type
                  :prompt-sync.file/action-needed :none)
           file-info))
       all-files))

(defn resolve-single-conflict!+
  "Handles single conflict resolution with UI interaction"
  [selected-conflict all-files]
  (p/let [choice (show-resolution-menu!+ selected-conflict)]
    (if choice
      (p/let [_ (resolve-conflict!+ selected-conflict choice)
              resolution-type (case choice
                                :prompt-sync.action/choose-stable :resolution/choose-stable
                                :prompt-sync.action/choose-insiders :resolution/choose-insiders
                                :prompt-sync.action/skip :resolution/skipped)
              updated-files (update-file-status-after-resolution all-files
                                                                 (:prompt-sync.file/filename selected-conflict)
                                                                 resolution-type)]
        updated-files)
      (p/resolved :cancelled))))

(defn main-menu-loop!+
  "Show files picker offering conflict resolution actions for conclicts
   Keep showing the files menu until the user cancels"
  [files]
  (def files files)
  (p/loop [current-files files]
    (p/let [selected-conflict (show-all-files-picker!+ current-files)]
      (if selected-conflict
        (p/let [updated-files (resolve-single-conflict!+ selected-conflict current-files)]
          (if (= updated-files :cancelled)
            (p/recur current-files)
            (p/recur updated-files)))
        (p/resolved :cancelled)))))

(defn sync-prompts!+
  "Main entry point - orchestrates the entire sync process"
  ([] (sync-prompts!+ {}))
  ([{:prompt-sync/keys [test-mode?]}]
   (if test-mode?
     (log! :info "🧪 TEST MODE: Using /tmp directories")
     (log! :debug "Starting prompt sync..."))
   (p/let [dirs (get-user-prompts-dirs {:prompt-sync/test-mode? test-mode?})
           {:prompt-sync/keys [stable-dir insiders-dir]} dirs

           compared (compare-directories!+ {:prompt-sync/stable-dir stable-dir
                                            :prompt-sync/insiders-dir insiders-dir})
           _ (def compared compared)

           ;; Copy missing files automatically and get updated file list
           updated-files (copy-missing-files!+ compared dirs)

           ;; Count what was copied for logging
           copied-count (count (filter #(= (:prompt-sync.file/status %) :copied) updated-files))]
     (do (log! :debug (str "Auto-copied: " copied-count " files"))
         nil)
     (main-menu-loop!+ updated-files))))

(def test-files [{:prompt-sync.file/filename "identical.prompt.md"
                  :prompt-sync.file/content "# Identical\nThis file is the same in both"}
                 {:prompt-sync.file/filename "conflict1.instruction.md"
                  :prompt-sync.file/stable-content
                  "# Stable Version - Instruction\nThis is from stable\n## Instructions\n- Use stable approach\n- Follow stable patterns"
                  :prompt-sync.file/insiders-content
                  "# Insiders Version - Instruction\nThis is from insiders\n## Instructions\n- Use insiders approach\n- Follow insiders patterns"}
                 {:prompt-sync.file/filename "conflict2.prompt.md"
                  :prompt-sync.file/stable-content
                  "# Stable Prompt\nYou are a stable assistant.\n\n## Rules\n- Be conservative\n- Follow stable guidelines"
                  :prompt-sync.file/insiders-content
                  "# Insiders Prompt\nYou are an experimental assistant.\n\n## Rules\n- Be innovative\n- Try new approaches"}
                 {:prompt-sync.file/filename "conflict3.chatmode.md"
                  :prompt-sync.file/stable-content
                  "# Stable Chat Mode\nconversational: true\ntemperature: 0.3\n\n## Description\nStable conversation mode"
                  :prompt-sync.file/insiders-content
                  "# Insiders Chat Mode\nconversational: true\ntemperature: 0.8\n\n## Description\nExperimental conversation mode"}
                 {:prompt-sync.file/filename "conflict4.instruction.md"
                  :prompt-sync.file/stable-content
                  "# Another Stable Instruction\nThese are stable coding guidelines.\n\n- Always use stable APIs\n- Avoid experimental features"
                  :prompt-sync.file/insiders-content
                  "# Another Insiders Instruction\nThese are experimental coding guidelines.\n\n- Try new APIs\n- Embrace experimental features"}
                 {:prompt-sync.file/filename "stable-only.chatmode.md"
                  :prompt-sync.file/content
                  "# Stable Only\nThis file only exists in stable"
                  :prompt-sync.file/location :stable-only}
                 {:prompt-sync.file/filename "insiders-only.prompt.md"
                  :prompt-sync.file/content
                  "# Insiders Only\nThis file only exists in insiders"
                  :prompt-sync.file/location :insiders-only}])

;; Export for use (disabled until we're ready making sure the test mode works )
(defn ^:export main-disabled []
  (p/catch
   (sync-prompts!+ {:prompt-sync/test-mode? false})
   (fn [error]
     (vscode/window.showErrorMessage (str "Sync error: " (.-message error)))
     (js/console.error "Prompt sync error:" error))))

(defn ^:export main-test
  "Entry point for test mode - uses /tmp directories"
  []
  (p/catch
   (binding [*log-level* :debug] ; Re-binding not working deeper down the call chain for some reason
     ;; Create test environment if in test mode
     (p/let [test-dirs (create-test-environment!+)]
       (populate-test-files!+ test-dirs test-files)
       (sync-prompts!+ {:prompt-sync/test-mode? true})))
   (fn [error]
     (vscode/window.showErrorMessage (str "Test sync error: " (.-message error)))
     (js/console.error "Test prompt sync error:" error))))

;; Auto-run when script is invoked
(when (= (joyride/invoked-script) joyride/*file*)
  (main-test))