(ns prompt-sync
  (:require ["vscode" :as vscode]
            ["path" :as path]
            [promesa.core :as p]
            [joyride.core :as joyride]))

;; VS Code FileType constants for semantic clarity
(def ^:const VSCODE-FILE-TYPE vscode/FileType.File)

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
  "Compares two directories and categorizes files, returns promise"
  [{:prompt-sync/keys [stable-dir insiders-dir]}]
  (p/let [stable-files (scan-directory!+ {:prompt-sync/dir-path stable-dir})
          insiders-files (scan-directory!+ {:prompt-sync/dir-path insiders-dir})

          ;; Load content for all files
          stable-with-content (p/all (map load-file-content!+ stable-files))
          insiders-with-content (p/all (map load-file-content!+ insiders-files))

          ;; Create lookup maps by filename
          stable-map (into {} (map (fn [f] [(:prompt-sync.file/filename f) f]) stable-with-content))
          insiders-map (into {} (map (fn [f] [(:prompt-sync.file/filename f) f]) insiders-with-content))

          all-filenames (set (concat (keys stable-map) (keys insiders-map)))]

    (reduce (fn [result filename]
              (let [stable-file (get stable-map filename)
                    insiders-file (get insiders-map filename)]
                (cond
                  ;; File only in stable
                  (and stable-file (not insiders-file))
                  (update result :prompt-sync.result/missing-in-insiders conj
                          (assoc stable-file :prompt-sync.file/copy-direction :copied-to-insiders))

                  ;; File only in insiders
                  (and insiders-file (not stable-file))
                  (update result :prompt-sync.result/missing-in-stable conj
                          (assoc insiders-file :prompt-sync.file/copy-direction :copied-to-stable))

                  ;; File in both - check content
                  (and stable-file insiders-file)
                  (if (= (:prompt-sync.file/content stable-file) (:prompt-sync.file/content insiders-file))
                    (update result :prompt-sync.result/identical conj
                            {:prompt-sync.file/filename filename
                             :prompt-sync.file/stable-file stable-file
                             :prompt-sync.file/insiders-file insiders-file
                             :prompt-sync.file/file-type (:prompt-sync.file/file-type stable-file)})
                    (update result :prompt-sync.result/conflicts conj
                            {:prompt-sync.file/filename filename
                             :prompt-sync.file/stable-file stable-file
                             :prompt-sync.file/insiders-file insiders-file
                             :prompt-sync.file/file-type (:prompt-sync.file/file-type stable-file)})))))
            {:prompt-sync.result/missing-in-stable []
             :prompt-sync.result/missing-in-insiders []
             :prompt-sync.result/conflicts []
             :prompt-sync.result/identical []}
            all-filenames)))

(defn copy-file!+
  "Copies file using workspace.fs"
  [{:prompt-sync/keys [source-uri target-uri]}]
  (-> (vscode/workspace.fs.readFile source-uri)
      (.then (fn [content]
               (vscode/workspace.fs.writeFile target-uri content)))))

(defn copy-missing-files!+
  "Copies missing files automatically, returns summary"
  [{:prompt-sync.result/keys [missing-in-stable missing-in-insiders]} {:prompt-sync/keys [stable-dir insiders-dir]}]
  (p/let [_ (p/all (map #(copy-file!+ {:prompt-sync/source-uri (:prompt-sync.file/uri %)
                                       :prompt-sync/target-uri (vscode/Uri.file
                                                                (path/join stable-dir (:prompt-sync.file/filename %)))})
                        missing-in-stable))
          _ (p/all (map #(copy-file!+ {:prompt-sync/source-uri (:prompt-sync.file/uri %)
                                       :prompt-sync/target-uri (vscode/Uri.file
                                                                (path/join insiders-dir (:prompt-sync.file/filename %)))})
                        missing-in-insiders))]
    {:prompt-sync.result/copied-from-stable (count missing-in-stable)
     :prompt-sync.result/copied-from-insiders (count missing-in-insiders)}))

(defn add-file-status
  "Adds status and optional metadata to file data - replaces all transformer functions!"
  [file-data status & {:keys [resolution copy-direction]}]
  (cond-> (assoc file-data :prompt-sync.file/status status)
    resolution (assoc :prompt-sync.file/resolution resolution)
    copy-direction (assoc :prompt-sync.file/copy-direction copy-direction)))

(defn enhance-sync-result
  "Adds unified all-files view with UNIFORM file objects - much simpler with uniform namespace!"
  [sync-result]
  (let [{:prompt-sync.result/keys [conflicts missing-in-stable missing-in-insiders identical resolved]} sync-result
        ;; Track filenames that were copied to exclude them from identical list
        copied-filenames (set (concat (map :prompt-sync.file/filename missing-in-insiders)
                                      (map :prompt-sync.file/filename missing-in-stable)))
        all-files (concat
                   ;; Simple status assignments instead of complex transformations!
                   (map #(add-file-status % :conflict) conflicts)
                   (map #(add-file-status % :resolved :resolution (:prompt-sync.resolved/action %)) (or resolved []))
                   (map #(add-file-status % :copied :copy-direction :copied-to-insiders) missing-in-insiders)
                   (map #(add-file-status % :copied :copy-direction :copied-to-stable) missing-in-stable)
                   ;; Filter out copied files from identical list
                   (->> identical
                        (remove #(copied-filenames (:prompt-sync.file/filename %)))
                        (map #(add-file-status % :identical))))
        ;; Sort all files by filename to maintain consistent order
        sorted-files (sort-by :prompt-sync.file/filename all-files)]
    (assoc sync-result :prompt-sync.result/all-files sorted-files)))


(defn show-diff-preview!+
  "Opens VS Code diff editor for conflict preview with default positioning"
  [{:prompt-sync.file/keys [stable-file insiders-file filename]}]
  (let [stable-uri (:prompt-sync.file/uri stable-file)
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

(defn create-all-files-picker-item
  "Creates QuickPick item for any file type with appropriate description"
  [{:prompt-sync.file/keys [filename status file-type copy-direction resolution]}]
  (let [icon (get-file-icon file-type)
        description (case status
                      :conflict (str (name file-type) " • has conflicts")
                      :identical "identical"
                      :copied (case copy-direction
                                :copied-to-insiders "Stable → Insiders"
                                :copied-to-stable "Insiders → Stable"
                                "copied") ; fallback
                      :resolved (case resolution
                                  :resolution/choose-stable "resolved • chose Stable"
                                  :resolution/choose-insiders "resolved • chose Insiders"
                                  :resolution/skipped "resolved • skipped"
                                  "resolved") ; fallback
                      (str (name file-type) " • " (name status)))
        detail (case status
                 :conflict "Select to choose resolution"
                 :resolved "Resolution complete"
                 "Preview only")]
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
  [{:prompt-sync.file/keys [stable-file insiders-file]}]
  (let [file-to-preview (or stable-file insiders-file)
        uri (:prompt-sync.file/uri file-to-preview)]
    (when uri
      (vscode/commands.executeCommand "vscode.open" uri #js {:preview true
                                                             :preserveFocus true}))))

(defn show-all-files-picker!+
  "Shows QuickPick for all files with appropriate preview and selection behavior"
  [{:prompt-sync.result/keys [all-files conflicts]}]
  (if (empty? all-files)
    (p/resolved nil)
    (let [items (map create-all-files-picker-item all-files)
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
                                  (let [conflict-info (first (filter #(= (:prompt-sync.file/filename %) filename) conflicts))]
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
                         (let [selected (first (.-selectedItems picker))]
                           (when selected
                             (let [file-info (.-fileInfo selected)
                                   is-conflict (.-isConflict file-info)]
                               (if is-conflict
                                 ;; Return conflict data for resolution
                                 (let [filename (.-filename file-info)
                                       conflict-info (first (filter #(= (:prompt-sync.file/filename %) filename) conflicts))]
                                   (.hide picker)
                                   (resolve conflict-info))
                                 ;; For non-conflicts, keep picker open (don't resolve)
                                 (println "📁 Preview only:" (.-filename file-info))))))))
         (.onDidHide picker
                     (fn []
                       (resolve nil)))
         (.show picker))))))

(defn show-resolution-menu!+
  "Shows resolution options menu"
  [{:prompt-sync.file/keys [filename]}]
  (println "📋 show-resolution-menu!+ called for:" filename)
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
                 (println "📋 User selected:" (when choice (.-label choice)) "for" filename)
                 (println "📋 Raw action string:" (when choice (.-action choice)))
                 (when choice
                   (let [action-keyword (keyword (.-action choice))]
                     (println "📋 Action as keyword:" action-keyword)
                     action-keyword)))))))

(defn resolve-conflict!+
  "Executes the chosen resolution action, returns result data"
  [conflict choice]
  (println "🔧 resolve-conflict!+ called with:")
  (println "  Choice:" choice)
  (println "  Choice type:" (type choice))
  (println "  Conflict filename:" (:prompt-sync.file/filename conflict))
  (let [{:prompt-sync.file/keys [stable-file insiders-file filename]} conflict]
    (println "  Stable file URI:" (:prompt-sync.file/uri stable-file))
    (println "  Insiders file URI:" (:prompt-sync.file/uri insiders-file))
    (case choice
      :prompt-sync.action/choose-stable
      (do (println "  → Copying stable to insiders")
          (p/let [_ (copy-file!+ {:prompt-sync/source-uri (:prompt-sync.file/uri stable-file)
                                  :prompt-sync/target-uri (:prompt-sync.file/uri insiders-file)})]
            {:prompt-sync.resolution/action :choose-stable :prompt-sync.resolution/filename filename :prompt-sync.resolution/success true}))

      :prompt-sync.action/choose-insiders
      (do (println "  → Copying insiders to stable")
          (p/let [_ (copy-file!+ {:prompt-sync/source-uri (:prompt-sync.file/uri insiders-file)
                                  :prompt-sync/target-uri (:prompt-sync.file/uri stable-file)})]
            {:prompt-sync.resolution/action :choose-insiders :prompt-sync.resolution/filename filename :prompt-sync.resolution/success true}))

      :prompt-sync.action/skip
      (do (println "  → Skipping")
          (p/resolved {:prompt-sync.resolution/action :skip :prompt-sync.resolution/filename filename :prompt-sync.resolution/success true}))

      (do (println "  → Cancelled")
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
                 (println "Created test environment:")
                 (println "Stable:" test-stable)
                 (println "Insiders:" test-insiders)
                 {:prompt-sync.env/stable test-stable :prompt-sync.env/insiders test-insiders})))))

(defn populate-test-files!+
  "Creates sample test files for different sync scenarios"
  [dirs]
  (let [encoder (js/TextEncoder.)
        files [{:prompt-sync.file/filename "identical.prompt.md"
                :prompt-sync.file/content "# Identical\nThis file is the same in both"}
               {:prompt-sync.file/filename "conflict1.instruction.md"
                :prompt-sync.file/stable-content "# Stable Version - Instruction\nThis is from stable\n## Instructions\n- Use stable approach\n- Follow stable patterns"
                :prompt-sync.file/insiders-content "# Insiders Version - Instruction\nThis is from insiders\n## Instructions\n- Use insiders approach\n- Follow insiders patterns"}
               {:prompt-sync.file/filename "conflict2.prompt.md"
                :prompt-sync.file/stable-content "# Stable Prompt\nYou are a stable assistant.\n\n## Rules\n- Be conservative\n- Follow stable guidelines"
                :prompt-sync.file/insiders-content "# Insiders Prompt\nYou are an experimental assistant.\n\n## Rules\n- Be innovative\n- Try new approaches"}
               {:prompt-sync.file/filename "conflict3.chatmode.md"
                :prompt-sync.file/stable-content "# Stable Chat Mode\nconversational: true\ntemperature: 0.3\n\n## Description\nStable conversation mode"
                :prompt-sync.file/insiders-content "# Insiders Chat Mode\nconversational: true\ntemperature: 0.8\n\n## Description\nExperimental conversation mode"}
               {:prompt-sync.file/filename "conflict4.instruction.md"
                :prompt-sync.file/stable-content "# Another Stable Instruction\nThese are stable coding guidelines.\n\n- Always use stable APIs\n- Avoid experimental features"
                :prompt-sync.file/insiders-content "# Another Insiders Instruction\nThese are experimental coding guidelines.\n\n- Try new APIs\n- Embrace experimental features"}
               {:prompt-sync.file/filename "stable-only.chatmode.md"
                :prompt-sync.file/content "# Stable Only\nThis file only exists in stable"
                :prompt-sync.file/location :stable-only}
               {:prompt-sync.file/filename "insiders-only.prompt.md"
                :prompt-sync.file/content "# Insiders Only\nThis file only exists in insiders"
                :prompt-sync.file/location :insiders-only}]]

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
        (.then (fn [_] (println "Cleaned up test environment")))
        (.catch (fn [err] (println "Cleanup error:" (.-message err)))))))

;; Clean separation of concerns helper functions

(defn handle-cancellation
  "Centralized cancellation handling"
  []
  (p/resolved :cancelled))

(defn update-sync-result-after-resolution
  "Pure function for updating sync result after conflict resolution"
  [enhanced-result resolved-conflict resolution-type]
  (let [updated-conflicts (remove #(= % resolved-conflict) (:prompt-sync.result/conflicts enhanced-result))
        resolved-entry {:prompt-sync.file/filename (:prompt-sync.file/filename resolved-conflict)
                        :prompt-sync.file/stable-file (:prompt-sync.file/stable-file resolved-conflict)
                        :prompt-sync.file/insiders-file (:prompt-sync.file/insiders-file resolved-conflict)
                        :prompt-sync.file/file-type (:prompt-sync.file/file-type resolved-conflict)
                        :prompt-sync.resolved/action resolution-type}
        existing-resolved (get enhanced-result :prompt-sync.result/resolved [])
        updated-enhanced (-> enhanced-result
                            (assoc :prompt-sync.result/conflicts updated-conflicts)
                            (assoc :prompt-sync.result/resolved (conj existing-resolved resolved-entry)))]
    (enhance-sync-result updated-enhanced)))

(defn resolve-single-conflict!+
  "Handles single conflict resolution with UI interaction"
  [selected-conflict enhanced-result]
  (p/let [choice (show-resolution-menu!+ selected-conflict)]
    (if choice
      (p/let [_ (resolve-conflict!+ selected-conflict choice)
              resolution-type (case choice
                                :prompt-sync.action/choose-stable :resolution/choose-stable
                                :prompt-sync.action/choose-insiders :resolution/choose-insiders
                                :prompt-sync.action/skip :resolution/skipped)
              updated-result (update-sync-result-after-resolution enhanced-result selected-conflict resolution-type)]
        (do (vscode/window.showInformationMessage (str "Resolved: " (:prompt-sync.file/filename selected-conflict)))
            updated-result))
      (handle-cancellation))))

(defn handle-conflicts-clean!+
  "Clean iterative conflict handling using p/loop instead of deep recursion"
  [enhanced-sync-result]
  (p/loop [current-result enhanced-sync-result]
    (let [remaining-conflicts (:prompt-sync.result/conflicts current-result)]
      (if (empty? remaining-conflicts)
        (p/resolved (do (vscode/window.showInformationMessage "Prompt sync completed!")
                        :completed))
        (p/let [selected-conflict (show-all-files-picker!+ current-result)]
          (if selected-conflict
            (p/let [updated-result (resolve-single-conflict!+ selected-conflict current-result)]
              (if (= updated-result :cancelled)
                (handle-cancellation)
                (p/recur updated-result)))
            (handle-cancellation)))))))

(defn sync-prompts!+
  "Main entry point - orchestrates the entire sync process"
  ([] (sync-prompts!+ {}))
  ([{:prompt-sync/keys [test-mode?]}]
   (p/let [dirs (get-user-prompts-dirs {:prompt-sync/test-mode? test-mode?})
           {:prompt-sync/keys [stable-dir insiders-dir test-mode?]} dirs

           _ (if test-mode?
               (do (vscode/window.showInformationMessage "🧪 TEST MODE: Using /tmp directories") nil)
               (do (vscode/window.showInformationMessage "Starting prompt sync...") nil))

           ;; Create test environment if in test mode
           _ (when test-mode?
               (p/let [test-dirs (create-test-environment!+)]
                 (populate-test-files!+ test-dirs)))

           sync-result (compare-directories!+ {:prompt-sync/stable-dir stable-dir
                                               :prompt-sync/insiders-dir insiders-dir})

           ;; Copy missing files automatically
           copy-summary (copy-missing-files!+ sync-result dirs)

           _ (do (vscode/window.showInformationMessage
                  (str "Auto-copied: " (:prompt-sync.result/copied-from-stable copy-summary) " from stable, "
                       (:prompt-sync.result/copied-from-insiders copy-summary) " from insiders"))
                 nil)

           ;; Enhance sync result for UI after copying
           enhanced-result (enhance-sync-result sync-result)]

     ;; Handle conflicts using clean approach
     (handle-conflicts-clean!+ enhanced-result))))

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
   (sync-prompts!+ {:prompt-sync/test-mode? true})
   (fn [error]
     (vscode/window.showErrorMessage (str "Test sync error: " (.-message error)))
     (js/console.error "Test prompt sync error:" error))))

;; Auto-run when script is invoked
(when (= (joyride/invoked-script) joyride/*file*)
  (main-test))