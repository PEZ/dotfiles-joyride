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

(defn classify-instruction-type
  "Determines instruction type from filename for appropriate icon"
  [filename]
  (cond
    (.endsWith filename "prompt.md") :instruction.type/prompt
    (.endsWith filename "chatmode.md") :instruction.type/chatmode
    :else :instruction.type/instruction))

(defn scan-directory!+
  "Scans directory for .md files using workspace.fs, returns promise"
  [{:scan/keys [dir-path]}]
  (let [dir-uri (vscode/Uri.file dir-path)]
    (-> (vscode/workspace.fs.readDirectory dir-uri)
        (.then (fn [entries]
                 (->> entries
                      (js->clj)
                      (filter (fn [[name type]]
                                (and (= type VSCODE-FILE-TYPE) ; Use semantic constant instead of magic number
                                     (.endsWith name ".md"))))
                      (map (fn [[filename _]]
                             {:location/filename filename
                              :location/path (path/join dir-path filename)
                              :location/uri (vscode/Uri.file (path/join dir-path filename))
                              :location/instruction-type (classify-instruction-type filename)})))))
        (.catch (fn [err]
                  (if (= (.-code err) "FileNotFound")
                    [] ; Return empty array if directory doesn't exist
                    (throw err)))))))

(defn load-file-content!+
  "Loads file content using workspace.fs, returns promise with updated location-info"
  [location-info]
  (let [decoder (js/TextDecoder.)
        {:location/keys [uri]} location-info]
    (-> (vscode/workspace.fs.readFile uri)
        (.then (fn [uint8array]
                 (assoc location-info :location/content (.decode decoder uint8array))))
        (.catch (fn [err]
                  (assoc location-info
                         :location/content nil
                         :location/error (.-message err)))))))

(defn compare-directories!+
  "Compares two directories and returns symmetric instruction-centric structures"
  [{:prompt-sync/keys [stable-dir insiders-dir]}]
  (p/let [stable-locations (scan-directory!+ {:scan/dir-path stable-dir})
          insiders-locations (scan-directory!+ {:scan/dir-path insiders-dir})

          ;; Load content for all locations
          stable-with-content (p/all (map load-file-content!+ stable-locations))
          insiders-with-content (p/all (map load-file-content!+ insiders-locations))

          ;; Create lookup maps by filename
          stable-map (into {} (map (fn [loc] [(:location/filename loc) loc]) stable-with-content))
          insiders-map (into {} (map (fn [loc] [(:location/filename loc) loc]) insiders-with-content))

          all-filenames (set (concat (keys stable-map) (keys insiders-map)))]

    ;; Return instruction-centric symmetric structures
    (sort-by :instruction/filename
             (map (fn [filename]
                    (let [stable-location (get stable-map filename)
                          insiders-location (get insiders-map filename)]
                      (cond
                        ;; File only in stable
                        (and stable-location (not insiders-location))
                        {:instruction/filename filename
                         :instruction/instruction-type (:location/instruction-type stable-location)
                         :instruction/status :missing-in-insiders
                         :instruction/action-needed :copy-to-insiders
                         :instruction/original-status :original/missing-in-insiders
                         :instruction/stable stable-location
                         :instruction/insiders nil}

                        ;; File only in insiders
                        (and insiders-location (not stable-location))
                        {:instruction/filename filename
                         :instruction/instruction-type (:location/instruction-type insiders-location)
                         :instruction/status :missing-in-stable
                         :instruction/action-needed :copy-to-stable
                         :instruction/original-status :original/missing-in-stable
                         :instruction/stable nil
                         :instruction/insiders insiders-location}

                        ;; File in both
                        (and stable-location insiders-location)
                        (let [content-match? (= (:location/content stable-location)
                                                (:location/content insiders-location))]
                          {:instruction/filename filename
                           :instruction/instruction-type (:location/instruction-type stable-location)
                           :instruction/status (if content-match? :identical :conflict)
                           :instruction/action-needed (if content-match? :none :resolve)
                           :instruction/original-status (if content-match? :original/identical :original/conflict)
                           :instruction/stable stable-location
                           :instruction/insiders insiders-location}))))
                  all-filenames))))

(defn copy-file!+
  "Copies file using workspace.fs"
  [{:prompt-sync/keys [source-uri target-uri]}]
  (-> (vscode/workspace.fs.readFile source-uri)
      (.then (fn [content]
               (vscode/workspace.fs.writeFile target-uri content)))))

(defn copy-missing-files!+
  "Copies files that need copying and updates their status using new instruction structure"
  [all-instructions {:prompt-sync/keys [stable-dir insiders-dir]}]
  (p/let [instructions-to-copy (filter #(#{:missing-in-stable :missing-in-insiders}
                                         (:instruction/status %)) all-instructions)

          ;; Execute all copy operations
          _ (p/all (map (fn [instruction]
                          (case (:instruction/status instruction)
                            :missing-in-stable
                            (let [source-uri (-> instruction :instruction/insiders :location/uri)
                                  target-uri (vscode/Uri.file
                                             (path/join stable-dir (:instruction/filename instruction)))]
                              (copy-file!+ {:prompt-sync/source-uri source-uri
                                            :prompt-sync/target-uri target-uri}))
                            :missing-in-insiders
                            (let [source-uri (-> instruction :instruction/stable :location/uri)
                                  target-uri (vscode/Uri.file
                                             (path/join insiders-dir (:instruction/filename instruction)))]
                              (copy-file!+ {:prompt-sync/source-uri source-uri
                                            :prompt-sync/target-uri target-uri}))))
                        instructions-to-copy))]

    ;; Transform statuses - mark copied files as completed
    (map (fn [instruction]
           (case (:instruction/status instruction)
             :missing-in-stable
             (assoc instruction
                    :instruction/status :copied
                    :instruction/action-needed :none)

             :missing-in-insiders
             (assoc instruction
                    :instruction/status :copied
                    :instruction/action-needed :none)

             ;; Other statuses unchanged
             instruction))
         all-instructions)))

;; Obsolete functions removed - we now have flat data from the start!
;; The bucket-based approach required complex transformations that are no longer needed.

(defn show-diff-preview!+
  "Opens VS Code diff editor for conflict preview with default positioning"
  [{:instruction/keys [filename stable insiders]}]
  (let [stable-uri (:location/uri stable)
        insiders-uri (:location/uri insiders)
        title (str "Diff: " filename " (Stable ↔ Insiders)")]
    (vscode/commands.executeCommand "vscode.diff"
                                    stable-uri
                                    insiders-uri
                                    title
                                    #js {:preview true
                                         :preserveFocus true})))

(defn get-instruction-icon
  "Gets appropriate VS Code icon for instruction type"
  [instruction-type]
  (case instruction-type
    :instruction.type/instruction (vscode/ThemeIcon. "list-ordered")
    :instruction.type/prompt (vscode/ThemeIcon. "chevron-right")
    :instruction.type/chatmode (vscode/ThemeIcon. "color-mode")
    (vscode/ThemeIcon. "diff")))

(defn create-picker-item
  "Creates QuickPick item using new symmetric instruction structure"
  [{:instruction/keys [filename status instruction-type action-needed original-status resolution]}]
  (let [icon (get-instruction-icon instruction-type)
        status-string (case status
                        :copied  (case original-status
                                   :original/missing-in-insiders "Auto-copied: Stable → Insiders"
                                   :original/missing-in-stable "Auto-copied: Stable ← Insiders"
                                   "copied")
                        :conflict "Has conflicts"
                        :identical "Identical"
                        :resolved (case resolution
                                    :resolution/choose-stable "Conflict resolved, copied: Stable → Insiders"
                                    :resolution/choose-insiders "Conflict resolved, copied: Stable ← Insiders"
                                    :resolution/skipped "Conflict skipped"
                                    "resolved")
                        (name status))
        description (when (= :resolve action-needed)
                      "Select to choose resolution")]
    #js {:label filename
         :iconPath icon
         :description description
         :detail (str (name instruction-type) " • " status-string)
         :fileInfo #js {:filename filename
                        :status (name status)
                        :instruction-type (name instruction-type)
                        :isConflict (= status :conflict)}}))

(defn create-status-item
  "Creates a descriptive status menu item for the picker"
  [instructions]
  (let [status-counts (frequencies (map :instruction/status instructions))
        conflicts (:conflict status-counts 0)
        resolved (:resolved status-counts 0)
        copied (:copied status-counts 0)
        identical (:identical status-counts 0)
        total (count instructions)]
    #js {:label (str total " instructions: "
                     "I:" identical ", "
                     "A:" copied ", "
                     "CR:" resolved ", "
                     "C:" conflicts)
         :description (str "Identical: " identical
                      " • Auto-copied: " copied
                      " • Resolved: " resolved
                      " • Conflicts: " conflicts)
         :fileInfo #js {:isStatus true}}))

(defn show-file-preview!+
  "Shows file preview for non-conflict files"
  [{:instruction/keys [stable insiders]}]
  (let [file-uri (or (:location/uri stable) (:location/uri insiders))]
    (when file-uri
      (vscode/commands.executeCommand "vscode.open" file-uri #js {:preview true
                                                                  :preserveFocus true}))))

(defn show-all-files-picker!+
  "Shows QuickPick for all files with appropriate preview and selection behavior"
  ([all-instructions] (show-all-files-picker!+ all-instructions nil))
  ([all-instructions last-active-item]
   (if (empty? all-instructions)
     (p/resolved nil)
     (let [file-items (map create-picker-item all-instructions)
           status-item (create-status-item all-instructions)
           items (into [status-item] file-items)  ; Status item first
           picker (vscode/window.createQuickPick)
           ;; Find the index of the last active item if provided (offset by 1 for status item)
           last-active-index (when last-active-item
                               (when-let [file-index (->> file-items
                                                          (map-indexed vector)
                                                          (filter (fn [[_idx item]]
                                                                    (= (.-filename (.-fileInfo item))
                                                                       (:instruction/filename last-active-item))))
                                                          (first)
                                                          (first))]
                                 (inc file-index)))]  ; Add 1 to account for status item

       (set! (.-items picker) (into-array items))
       (set! (.-title picker) "Prompt Sync: Stable ↔ Insiders")
       (set! (.-placeholder picker) "Select conflicts to resolve, others for preview")
       (set! (.-ignoreFocusOut picker) true)

       ;; Set active item to the last selected one if available
       (when (and last-active-index (< last-active-index (count items)))
         (set! (.-activeItems picker) #js [(nth items last-active-index)]))

       ;; Live preview on active item change
       (.onDidChangeActive picker
                           (fn [active-items]
                             (when-let [first-item (first active-items)]
                               (let [file-info (.-fileInfo first-item)]
                                 (if (.-isStatus file-info)
                                   ;; Status item selected - no preview
                                   nil
                                   ;; File item selected - show preview
                                   (let [filename (.-filename file-info)
                                         is-conflict (.-isConflict file-info)]
                                     (if is-conflict
                                       ;; Show diff for conflicts
                                       (let [instruction-info (first (filter #(= (:instruction/filename %) filename) all-instructions))]
                                         (when instruction-info
                                           (show-diff-preview!+ instruction-info)))
                                       ;; Show simple preview for non-conflicts
                                       (let [instruction-data (first (filter #(= (:instruction/filename %) filename) all-instructions))]
                                         (when instruction-data
                                           (show-file-preview!+ instruction-data))))))))))

       (js/Promise.
        (fn [resolve _reject]
          (.onDidAccept picker
                        (fn []
                          (when-let [selected (first (.-selectedItems picker))]
                            (let [file-info (.-fileInfo selected)]
                              (if (.-isStatus file-info)
                                ;; Status item selected - ignore
                                nil
                                ;; File item selected - process
                                (let [is-conflict (.-isConflict file-info)]
                                  (if is-conflict
                                    ;; Return conflict data for resolution
                                    (let [filename (.-filename file-info)
                                          instruction-info (first (filter #(= (:instruction/filename %) filename) all-instructions))]
                                      (.hide picker)
                                      (resolve instruction-info))
                                    ;; For non-conflicts, keep picker open (don't resolve)
                                    (log! :debug "📁 Preview only:" (.-filename file-info)))))))))
          (.onDidHide picker
                      (fn []
                        (resolve nil)))
          (.show picker)))))))

(defn show-resolution-menu!+
  "Shows resolution options menu"
  [{:instruction/keys [filename]}]
  (log! :debug "📋 show-resolution-menu!+ called for:" filename)
  (let [actions [{:label "Choose Stable"
                  :iconPath (vscode/ThemeIcon. "arrow-right")
                  :description "Copy stable version to insiders"
                  :action "prompt-sync.action/choose-stable"}
                 {:label "Choose Insiders"
                  :iconPath (vscode/ThemeIcon. "arrow-left")
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
  [instruction choice]
  (log! :debug "🔧 resolve-conflict!+ called with:")
  (log! :debug "  Choice:" choice)
  (log! :debug "  Choice type:" (type choice))
  (log! :debug "  Instruction filename:" (:instruction/filename instruction))
  (let [{:instruction/keys [filename stable insiders]} instruction
        stable-uri (:location/uri stable)
        insiders-uri (:location/uri insiders)]
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

(defn update-instruction-status-after-resolution
  "Pure function for updating instruction status after conflict resolution"
  [all-instructions resolved-filename resolution-type]
  (map (fn [instruction]
         (if (= (:instruction/filename instruction) resolved-filename)
           (assoc instruction
                  :instruction/status :resolved
                  :instruction/resolution resolution-type
                  :instruction/action-needed :none)
           instruction))
       all-instructions))

(defn resolve-single-conflict!+
  "Handles single conflict resolution with UI interaction"
  [selected-instruction all-instructions]
  (p/let [choice (show-resolution-menu!+ selected-instruction)]
    (if choice
      (p/let [_ (resolve-conflict!+ selected-instruction choice)
              resolution-type (case choice
                                :prompt-sync.action/choose-stable :resolution/choose-stable
                                :prompt-sync.action/choose-insiders :resolution/choose-insiders
                                :prompt-sync.action/skip :resolution/skipped)
              updated-instructions (update-instruction-status-after-resolution all-instructions
                                                                              (:instruction/filename selected-instruction)
                                                                              resolution-type)]
        updated-instructions)
      (p/resolved :cancelled))))

(defn main-menu-loop!+
  "Show instructions picker offering conflict resolution actions for conflicts
   Keep showing the instructions menu until the user cancels"
  ([instructions] (main-menu-loop!+ instructions nil))
  ([instructions last-active-item]
   (def instructions instructions)
   (p/loop [current-instructions instructions
            last-active last-active-item]
     (p/let [selected-instruction (show-all-files-picker!+ current-instructions last-active)]
       (if selected-instruction
         (p/let [updated-instructions (resolve-single-conflict!+ selected-instruction current-instructions)]
           (if (= updated-instructions :cancelled)
             (p/recur current-instructions selected-instruction) ; Keep the selected item as the last active
             (p/recur updated-instructions selected-instruction))) ; Pass along the selected item for memory
         (p/resolved :cancelled))))))

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

           ;; Copy missing files automatically and get updated instruction list
           updated-instructions (copy-missing-files!+ compared dirs)

           ;; Count what was copied for logging
           copied-count (count (filter #(= (:instruction/status %) :copied) updated-instructions))]
     (do (log! :debug (str "Auto-copied: " copied-count " files"))
         nil)
     (main-menu-loop!+ updated-instructions))))

(def test-files [{:prompt-sync.file/filename "identical.prompt.md"
                  :prompt-sync.file/stable-content "# Identical\nThis file is the same in both"
                  :prompt-sync.file/insiders-content "# Identical\nThis file is the same in both"}
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
                  :prompt-sync.file/stable-content
                  "# Stable Only\nThis file only exists in stable"
                  :prompt-sync.file/location :stable-only}
                 {:prompt-sync.file/filename "insiders-only.prompt.md"
                  :prompt-sync.file/insiders-content
                  "# Insiders Only\nThis file only exists in insiders"
                  :prompt-sync.file/location :insiders-only}])

(defn populate-test-files!+
  "Creates sample test files using only stable-content and insiders-content keys"
  [dirs files]
  (let [encoder (js/TextEncoder.)]
    (->> (into []
               (for [file files]
                 (let [{:prompt-sync.file/keys [filename
                                                stable-content
                                                insiders-content location]} file
                       stable-uri (vscode/Uri.file
                                   (path/join (:prompt-sync.env/stable dirs) filename))
                       insiders-uri (vscode/Uri.file
                                     (path/join (:prompt-sync.env/insiders dirs) filename))

                       ;; Create stable file if content exists and location allows it
                       stable (when (and stable-content
                                         (not= location :insiders-only))
                                (vscode/workspace.fs.writeFile
                                 stable-uri (.encode encoder stable-content)))

                       ;; Create insiders file if content exists and location allows it
                       insiders (when (and insiders-content
                                           (not= location :stable-only))
                                  (vscode/workspace.fs.writeFile
                                   insiders-uri (.encode encoder insiders-content)))]
                   [stable insiders])))
         (filter some?)
         p/all)))

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
                 (log! :info "Created test environment:")
                 (log! :info "Stable:" test-stable)
                 (log! :info "Insiders:" test-insiders)
                 {:prompt-sync.env/stable test-stable :prompt-sync.env/insiders test-insiders})))))

(defn cleanup-test-environment!+
  "Removes test environment when done"
  []
  (let [test-base-uri (vscode/Uri.file "/tmp/prompt-sync-test")]
    (-> (vscode/workspace.fs.delete test-base-uri #js {:recursive true :useTrash false})
        (.then (fn [_] (log! :info "Cleaned up test environment")))
        (.catch (fn [err] (log! :info "Cleanup error:" (.-message err)))))))

;; Export for use (disabled until we're ready making sure the test mode works)
(defn ^:export main-disabled []
  (p/catch
   (sync-prompts!+ {:prompt-sync/test-mode? false})
   (fn [error]
     (vscode/window.showErrorMessage (str "Sync error: " (.-message error)))
     (js/console.error "Prompt sync error:" error))))

(defn ^:export main-test
  "Entry point for test mode - uses /tmp directories"
  []
  (->
   (p/let [_ (cleanup-test-environment!+) ; Clean first, then create
           test-dirs (create-test-environment!+)
           _ (populate-test-files!+ test-dirs test-files)]
     (sync-prompts!+ {:prompt-sync/test-mode? true}))
   (.catch (fn [error]
             (vscode/window.showErrorMessage (str "Test sync error: " (.-message error)))
             (js/console.error "Test prompt sync error:" error)))))

;; Auto-run when script is invoked
(when (= (joyride/invoked-script) joyride/*file*)
  (main-test))