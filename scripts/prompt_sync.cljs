(ns prompt-sync
  (:require ["vscode" :as vscode]
            ["path" :as path]
            [promesa.core :as p]
            [joyride.core :as joyride]))

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
    (.includes filename "instruction") :prompt-sync.file/instruction
    (.includes filename "chatmode") :prompt-sync.file/chatmode
    :else :prompt-sync.file/prompt))

(defn scan-directory!+
  "Scans directory for .md files using workspace.fs, returns promise"
  [{:prompt-sync/keys [dir-path]}]
  (let [dir-uri (vscode/Uri.file dir-path)]
    (-> (vscode/workspace.fs.readDirectory dir-uri)
        (.then (fn [entries]
                 (->> entries
                      (js->clj)
                      (filter (fn [[name type]]
                                (and (= type 1) ; file type
                                     (.endsWith name ".md"))))
                      (map (fn [[filename _]]
                             {:prompt-sync.file/name filename
                              :prompt-sync.file/path (path/join dir-path filename)
                              :prompt-sync.file/uri (vscode/Uri.file (path/join dir-path filename))
                              :prompt-sync.file/type (classify-file-type filename)})))))
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
          stable-map (into {} (map (fn [f] [(:prompt-sync.file/name f) f]) stable-with-content))
          insiders-map (into {} (map (fn [f] [(:prompt-sync.file/name f) f]) insiders-with-content))

          all-filenames (set (concat (keys stable-map) (keys insiders-map)))]

    (reduce (fn [result filename]
              (let [stable-file (get stable-map filename)
                    insiders-file (get insiders-map filename)]
                (cond
                  ;; File only in stable
                  (and stable-file (not insiders-file))
                  (update result :prompt-sync.result/missing-in-insiders conj stable-file)

                  ;; File only in insiders
                  (and insiders-file (not stable-file))
                  (update result :prompt-sync.result/missing-in-stable conj insiders-file)

                  ;; File in both - check content
                  (and stable-file insiders-file)
                  (if (= (:prompt-sync.file/content stable-file) (:prompt-sync.file/content insiders-file))
                    (update result :prompt-sync.result/identical conj
                            {:prompt-sync.conflict/filename filename
                             :prompt-sync.conflict/stable-file stable-file
                             :prompt-sync.conflict/insiders-file insiders-file})
                    (update result :prompt-sync.result/conflicts conj
                            {:prompt-sync.conflict/filename filename
                             :prompt-sync.conflict/stable-file stable-file
                             :prompt-sync.conflict/insiders-file insiders-file
                             :prompt-sync.conflict/type (:prompt-sync.file/type stable-file)})))))
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
                                                               (path/join stable-dir (:prompt-sync.file/name %)))})
                        missing-in-stable))
          _ (p/all (map #(copy-file!+ {:prompt-sync/source-uri (:prompt-sync.file/uri %)
                                      :prompt-sync/target-uri (vscode/Uri.file
                                                               (path/join insiders-dir (:prompt-sync.file/name %)))})
                        missing-in-insiders))]
    {:copied-from-stable (count missing-in-stable)
     :copied-from-insiders (count missing-in-insiders)}))

(defn create-conflict-picker-item
  "Creates QuickPick item for conflict with appropriate icon"
  [{:prompt-sync.conflict/keys [filename type]}]
  (let [icon (case type
               :prompt-sync.file/instruction (vscode/ThemeIcon. "list-ordered")
               :prompt-sync.file/prompt (vscode/ThemeIcon. "chevron-right")
               :prompt-sync.file/chatmode (vscode/ThemeIcon. "color-mode")
               (vscode/ThemeIcon. "diff"))]
    #js {:label filename
         :iconPath icon
         :description (str (name type) " • has conflicts")
         :detail "Select to view diff and choose resolution"
         :conflict #js {:filename filename
                       :type type}}))

(defn show-diff-preview!+
  "Opens VS Code diff editor for conflict preview with default positioning"
  [{:prompt-sync.conflict/keys [stable-file insiders-file filename]}]
  (let [stable-uri (:prompt-sync.file/uri stable-file)
        insiders-uri (:prompt-sync.file/uri insiders-file)
        title (str "Diff: " filename " (Stable ↔ Insiders)")]
    (vscode/commands.executeCommand "vscode.diff"
                                    stable-uri
                                    insiders-uri
                                    title
                                    #js {:preview true
                                         :preserveFocus true})))

(defn show-conflict-picker!+
  "Shows QuickPick for conflict selection with live diff preview"
  [{:prompt-sync.result/keys [conflicts]}]
  (if (empty? conflicts)
    (p/resolved nil)
    (let [items (map create-conflict-picker-item conflicts)
          picker (vscode/window.createQuickPick)]

      (set! (.-items picker) (into-array items))
      (set! (.-title picker) "Prompt Sync - Resolve Conflicts")
      (set! (.-placeholder picker) "Select a file to resolve conflicts")
      (set! (.-ignoreFocusOut picker) true)

      ;; Live diff preview on active item change
      (.onDidChangeActive picker
                          (fn [active-items]
                            (when-let [first-item (first active-items)]
                              (let [conflict-data (.-conflict first-item)
                                    filename (.-filename conflict-data)
                                    conflict-info (first (filter #(= (:prompt-sync.conflict/filename %) filename) conflicts))]
                                (when conflict-info
                                  (show-diff-preview!+ conflict-info))))))

      (js/Promise.
       (fn [resolve _reject]
         (.onDidAccept picker
                       (fn []
                         (let [selected (first (.-selectedItems picker))]
                           (.hide picker)
                           (when selected
                             (let [conflict-data (.-conflict selected)
                                   filename (.-filename conflict-data)
                                   conflict-info (first (filter #(= (:prompt-sync.conflict/filename %) filename) conflicts))]
                               (resolve conflict-info))))))
         (.onDidHide picker
                     (fn []
                       (resolve nil)))
         (.show picker))))))

(defn show-resolution-menu!+
  "Shows resolution options menu"
  [{:prompt-sync.conflict/keys [filename]}]
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
  (println "  Conflict filename:" (:prompt-sync.conflict/filename conflict))
  (let [{:prompt-sync.conflict/keys [stable-file insiders-file filename]} conflict]
    (println "  Stable file URI:" (:prompt-sync.file/uri stable-file))
    (println "  Insiders file URI:" (:prompt-sync.file/uri insiders-file))
    (case choice
      :prompt-sync.action/choose-stable
      (do (println "  → Copying stable to insiders")
          (p/let [_ (copy-file!+ {:prompt-sync/source-uri (:prompt-sync.file/uri stable-file)
                                  :prompt-sync/target-uri (:prompt-sync.file/uri insiders-file)})]
            {:action :choose-stable :filename filename :success true}))

      :prompt-sync.action/choose-insiders
      (do (println "  → Copying insiders to stable")
          (p/let [_ (copy-file!+ {:prompt-sync/source-uri (:prompt-sync.file/uri insiders-file)
                                  :prompt-sync/target-uri (:prompt-sync.file/uri stable-file)})]
            {:action :choose-insiders :filename filename :success true}))

      :prompt-sync.action/skip
      (do (println "  → Skipping")
          (p/resolved {:action :skip :filename filename :success true}))

      (do (println "  → Cancelled")
          (p/resolved {:action :cancelled :filename filename :success false})))))

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
                 {:stable test-stable :insiders test-insiders})))))

(defn populate-test-files!+
  "Creates sample test files for different sync scenarios"
  [dirs]
  (let [encoder (js/TextEncoder.)
        files [{:name "identical.prompt.md"
                :content "# Identical\nThis file is the same in both"}
               {:name "conflict1.instruction.md"
                :stable-content "# Stable Version - Instruction\nThis is from stable\n## Instructions\n- Use stable approach\n- Follow stable patterns"
                :insiders-content "# Insiders Version - Instruction\nThis is from insiders\n## Instructions\n- Use insiders approach\n- Follow insiders patterns"}
               {:name "conflict2.prompt.md"
                :stable-content "# Stable Prompt\nYou are a stable assistant.\n\n## Rules\n- Be conservative\n- Follow stable guidelines"
                :insiders-content "# Insiders Prompt\nYou are an experimental assistant.\n\n## Rules\n- Be innovative\n- Try new approaches"}
               {:name "conflict3.chatmode.md"
                :stable-content "# Stable Chat Mode\nconversational: true\ntemperature: 0.3\n\n## Description\nStable conversation mode"
                :insiders-content "# Insiders Chat Mode\nconversational: true\ntemperature: 0.8\n\n## Description\nExperimental conversation mode"}
               {:name "conflict4.instruction.md"
                :stable-content "# Another Stable Instruction\nThese are stable coding guidelines.\n\n- Always use stable APIs\n- Avoid experimental features"
                :insiders-content "# Another Insiders Instruction\nThese are experimental coding guidelines.\n\n- Try new APIs\n- Embrace experimental features"}
               {:name "stable-only.chatmode.md"
                :content "# Stable Only\nThis file only exists in stable"
                :location :stable-only}
               {:name "insiders-only.prompt.md"
                :content "# Insiders Only\nThis file only exists in insiders"
                :location :insiders-only}]]

    (p/all
     (for [file files]
       (cond
         ;; Identical files - create in both directories (has :content, no :location)
         (and (:content file) (not (:location file)))
         (let [content (.encode encoder (:content file))
               stable-uri (vscode/Uri.file (path/join (:stable dirs) (:name file)))
               insiders-uri (vscode/Uri.file (path/join (:insiders dirs) (:name file)))]
           (-> (vscode/workspace.fs.writeFile stable-uri content)
               (.then (fn [_] (vscode/workspace.fs.writeFile insiders-uri content)))))

         ;; Conflict files - create different versions (has :stable-content and :insiders-content)
         (:stable-content file)
         (let [stable-content (.encode encoder (:stable-content file))
               insiders-content (.encode encoder (:insiders-content file))
               stable-uri (vscode/Uri.file (path/join (:stable dirs) (:name file)))
               insiders-uri (vscode/Uri.file (path/join (:insiders dirs) (:name file)))]
           (-> (vscode/workspace.fs.writeFile stable-uri stable-content)
               (.then (fn [_] (vscode/workspace.fs.writeFile insiders-uri insiders-content)))))

         ;; Single location files - only create in specified location
         (= (:location file) :stable-only)
         (let [content (.encode encoder (:content file))
               stable-uri (vscode/Uri.file (path/join (:stable dirs) (:name file)))]
           (vscode/workspace.fs.writeFile stable-uri content))

         (= (:location file) :insiders-only)
         (let [content (.encode encoder (:content file))
               insiders-uri (vscode/Uri.file (path/join (:insiders dirs) (:name file)))]
           (vscode/workspace.fs.writeFile insiders-uri content)))))))

(defn cleanup-test-environment!+
  "Removes test environment when done"
  []
  (let [test-base-uri (vscode/Uri.file "/tmp/prompt-sync-test")]
    (-> (vscode/workspace.fs.delete test-base-uri #js {:recursive true :useTrash false})
        (.then (fn [_] (println "Cleaned up test environment")))
        (.catch (fn [err] (println "Cleanup error:" (.-message err)))))))

(defn sync-prompts!+
  "Main entry point - orchestrates the entire sync process"
  ([] (sync-prompts!+ {}))
  ([{:prompt-sync/keys [test-mode?]}]
   (letfn [(handle-conflicts [remaining-conflicts]
             (if (empty? remaining-conflicts)
               (p/resolved (do (vscode/window.showInformationMessage "Prompt sync completed!")
                               :completed))
               (p/let [selected-conflict (show-conflict-picker!+ {:prompt-sync.result/conflicts remaining-conflicts})]
                 (println "🔍 Selected conflict:" (when selected-conflict (:prompt-sync.conflict/filename selected-conflict)))
                 (if selected-conflict
                   (p/let [choice (show-resolution-menu!+ selected-conflict)]
                     (println "🎯 User choice:" choice)
                     (if choice
                       (p/let [resolution-result (resolve-conflict!+ selected-conflict choice)]
                         (println "🎯 Resolution result:" resolution-result)
                         (do (vscode/window.showInformationMessage (str "Resolved: " (:prompt-sync.conflict/filename selected-conflict)))
                             (handle-conflicts (remove #(= % selected-conflict) remaining-conflicts))))
                       ;; User cancelled resolution menu
                       (p/resolved (do (vscode/window.showInformationMessage "Prompt sync cancelled")
                                       :cancelled))))
                   ;; User cancelled conflict picker
                   (p/resolved (do (vscode/window.showInformationMessage "Prompt sync cancelled")
                                   :cancelled))))))]

     (p/let [dirs (get-user-prompts-dirs {:prompt-sync/test-mode? test-mode?})
             {:prompt-sync/keys [stable-dir insiders-dir test-mode?]} dirs

             _ (if test-mode?
                 (do (vscode/window.showInformationMessage "🧪 TEST MODE: Using /tmp directories")
                     nil)
                 (do (vscode/window.showInformationMessage "Starting prompt sync...")
                     nil))

             ;; Create test environment if in test mode
             _ (when test-mode?
                 (p/let [test-dirs (create-test-environment!+)]
                   (populate-test-files!+ test-dirs)))

             sync-result (compare-directories!+ {:prompt-sync/stable-dir stable-dir
                                                 :prompt-sync/insiders-dir insiders-dir})

             {:prompt-sync.result/keys [conflicts]} sync-result

             ;; Copy missing files automatically
             copy-summary (copy-missing-files!+ sync-result dirs)

             _ (do (vscode/window.showInformationMessage
                    (str "Auto-copied: " (:copied-from-stable copy-summary) " from stable, "
                         (:copied-from-insiders copy-summary) " from insiders"))
                   nil)]

       ;; Handle conflicts using letfn recursion instead of loop/recur
       (handle-conflicts conflicts)))))

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