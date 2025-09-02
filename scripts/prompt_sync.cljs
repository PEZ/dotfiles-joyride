(ns prompt-sync
  (:require ["vscode" :as vscode]
            ["path" :as path]
            [promesa.core :as p]
            [joyride.core :as joyride]))

(def ^:const VSCODE-FILE-TYPE vscode/FileType.File)

(def ^:dynamic *log-level* :debug)

(defn log!
  [level & messages]
  (when (or (= level :error)
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

(defn construct-directory-paths
  "Pure function to construct directory paths from user directory and insiders check"
  [current-user-dir is-insiders?]
  (let [stable-dir (if is-insiders?
                     (.replace current-user-dir "Code - Insiders" "Code")
                     current-user-dir)
        insiders-dir (if is-insiders?
                       current-user-dir
                       (.replace current-user-dir "Code" "Code - Insiders"))]
    {:prompt-sync/stable-dir stable-dir
     :prompt-sync/insiders-dir insiders-dir
     :prompt-sync/current-is-insiders? is-insiders?}))

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
           dir-paths (construct-directory-paths current-user-dir is-insiders?)]
       (-> dir-paths
           (update :prompt-sync/stable-dir #(path/join % "prompts"))
           (update :prompt-sync/insiders-dir #(path/join % "prompts"))
           (assoc :prompt-sync/test-mode? false))))))

(defn classify-instruction-type
  "Determines instruction type from filename for appropriate icon"
  [filename]
  (cond
    (.endsWith filename "prompt.md") :instruction.type/prompt
    (.endsWith filename "chatmode.md") :instruction.type/chatmode
    :else :instruction.type/instruction))

(defn is-md-file?
  "Checks if directory entry is a markdown file"
  [[name type]]
  (and (= type VSCODE-FILE-TYPE)
       (.endsWith name ".md")))

(defn entry->location-info
  "Converts directory entry to location info map"
  [dir-path [filename _]]
  {:location/filename filename
   :location/path (path/join dir-path filename)
   :location/uri (vscode/Uri.file (path/join dir-path filename))
   :location/instruction-type (classify-instruction-type filename)})

(defn scan-directory!+
  "Scans directory for .md files using workspace.fs, returns promise"
  [{:scan/keys [dir-path]}]
  (let [dir-uri (vscode/Uri.file dir-path)]
    (-> (vscode/workspace.fs.readDirectory dir-uri)
        (.then (fn [entries]
                 (->> entries
                      (js->clj)
                      (filter is-md-file?)
                      (map (partial entry->location-info dir-path)))))
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

(defn create-instruction-from-locations
  "Pure function to create instruction from stable and insiders locations"
  [filename stable-location insiders-location]
  (cond
    ;; File only in stable
    (and stable-location (not insiders-location))
    {:instruction/filename filename
     :instruction/instruction-type (:location/instruction-type stable-location)
     :instruction/status :status/missing-in-insiders
     :instruction/action-needed :resolve
     :instruction/original-status :original/missing-in-insiders
     :instruction/stable stable-location
     :instruction/insiders nil}

    ;; File only in insiders
    (and insiders-location (not stable-location))
    {:instruction/filename filename
     :instruction/instruction-type (:location/instruction-type insiders-location)
     :instruction/status :status/missing-in-stable
     :instruction/action-needed :resolve
     :instruction/original-status :original/missing-in-stable
     :instruction/stable nil
     :instruction/insiders insiders-location}

    ;; File in both
    (and stable-location insiders-location)
    (let [content-match? (= (:location/content stable-location)
                            (:location/content insiders-location))]
      {:instruction/filename filename
       :instruction/instruction-type (:location/instruction-type stable-location)
       :instruction/status (if content-match? :status/identical :status/conflict)
       :instruction/action-needed (if content-match? :none :resolve)
       :instruction/original-status (if content-match? :original/identical :original/conflict)
       :instruction/stable stable-location
       :instruction/insiders insiders-location})))

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

    (sort-by :instruction/filename
             (map (fn [filename]
                    (let [stable-location (get stable-map filename)
                          insiders-location (get insiders-map filename)]
                      (create-instruction-from-locations filename stable-location insiders-location)))
                  all-filenames))))

(defn copy-file!+
  "Copies file using workspace.fs"
  [{:prompt-sync/keys [source-uri target-uri]}]
  (-> (vscode/workspace.fs.readFile source-uri)
      (.then (fn [content]
               (vscode/workspace.fs.writeFile target-uri content)))))

(defn show-diff-preview!+
  "Opens VS Code diff editor for conflict preview"
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

(defn instruction-status->display-string
  "Converts instruction status to human-readable display string"
  [status resolution]
  (case status
    :status/missing-in-stable "Missing in Stable"
    :status/missing-in-insiders "Missing in Insiders"
    :status/conflict "Has conflicts"
    :status/identical "Identical"
    :status/resolved (case resolution
                       :resolution/choose-stable "Conflict resolved, copied: Stable → Insiders"
                       :resolution/choose-insiders "Conflict resolved, copied: Stable ← Insiders"
                       :resolution/sync-to-stable "Missing file synced: Insiders → Stable"
                       :resolution/sync-to-insiders "Missing file synced: Stable → Insiders"
                       :resolution/skipped "Skipped"
                       "resolved")
    (name status)))

(defn instruction->quickpick-item
  "Creates QuickPick item"
  [{:instruction/keys [filename status instruction-type action-needed resolution]}]
  (def status status)
  (let [icon (get-instruction-icon instruction-type)
        status-string (instruction-status->display-string status resolution)
        description (when (= :resolve action-needed)
                      "Select to choose resolution")]
    #js {:label filename
         :iconPath icon
         :description description
         :detail (str (name instruction-type) " • " status-string)
         :itemType "file"
         :fileInfo #js {:filename filename
                        :status (name status)
                        :instruction-type (name instruction-type)
                        :isConflict (= status :status/conflict)}}))

(defn format-status-counts
  "Formats status counts for the summary label"
  [total identical missing-stable missing-insiders resolved conflicts]
  (str total " instructions: "
       "I:" identical ", "
       "MS:" missing-stable ", "
       "MI:" missing-insiders ", "
       "R:" resolved ", "
       "C:" conflicts))

(defn format-status-description
  "Formats detailed status description"
  [identical missing-stable missing-insiders resolved conflicts]
  (str "Identical: " identical
       " • Missing in Stable: " missing-stable
       " • Missing in Insiders: " missing-insiders
       " • Resolved: " resolved
       " • Conflicts: " conflicts))

(defn calculate-status-summary
  "Pure function to calculate status counts and labels from instructions"
  [instructions]
  (let [status-counts (frequencies (map :instruction/status instructions))
        conflicts (:status/conflict status-counts 0)
        resolved (:status/resolved status-counts 0)
        missing-stable (:status/missing-in-stable status-counts 0)
        missing-insiders (:status/missing-in-insiders status-counts 0)
        identical (:status/identical status-counts 0)
        total (count instructions)]
    {:status-summary/total total
     :status-summary/identical identical
     :status-summary/missing-stable missing-stable
     :status-summary/missing-insiders missing-insiders
     :status-summary/resolved resolved
     :status-summary/conflicts conflicts
     :status-summary/label (format-status-counts total identical missing-stable missing-insiders resolved conflicts)
     :status-summary/description (format-status-description identical missing-stable missing-insiders resolved conflicts)}))

(defn instructions->status-summary-item
  "Creates a descriptive status menu item for the picker"
  [instructions]
  (let [summary (calculate-status-summary instructions)]
    #js {:label (:status-summary/label summary)
         :description (:status-summary/description summary)
         :fileInfo #js {:isStatus true}
         :itemType "status"}))

(def ^:const original-status-priority
  "Priority order for displaying original status groups"
  {:original/conflict 1
   :original/missing-in-stable 2
   :original/missing-in-insiders 3
   :original/identical 4})

(defn group-by-original-status
  "Groups instructions by their original status"
  [instructions]
  (group-by :instruction/original-status instructions))

(defn sort-groups-by-priority
  "Sorts grouped instructions by original status priority"
  [grouped-instructions]
  (->> grouped-instructions
       (sort-by (fn [[original-status _]]
                  (get original-status-priority original-status 999)))
       (into [])))

(defn original-status->header-text
  "Generates header text for different original status types"
  [original-status total-count unresolved-count]
  (let [resolved-count (- total-count unresolved-count)]
    (case original-status
      :original/conflict (str "Conflicting (" resolved-count "/" total-count " resolved)")
      :original/missing-in-stable (str "Missing in Stable (" resolved-count "/" total-count " resolved)")
      :original/missing-in-insiders (str "Missing in Insiders (" resolved-count "/" total-count " resolved)")
      :original/identical (str "Identical (" total-count " instructions)")
      (str "Unknown Status (" total-count " instructions)"))))

(defn create-section-header-item
  "Creates a section header item for grouped display"
  [original-status total-count unresolved-count]
  (let [header-text (original-status->header-text original-status total-count unresolved-count)]
    #js {:label header-text
         :kind vscode/QuickPickItemKind.Separator
         :itemType "section-header"
         :sectionInfo #js {:originalStatus (name original-status)
                           :totalCount total-count}}))

(defn count-unresolved-in-group
  "Counts unresolved files in a group (not :status/resolved status)"
  [group]
  (->> group
       (filter #(not= (:instruction/status %) :status/resolved))
       count))

(defn instructions->menu-items
  "Creates hierarchical menu structure with buttons on first items"
  [instructions]
  (let [grouped (group-by-original-status instructions)
        sorted-groups (sort-groups-by-priority grouped)]
    (->> sorted-groups
         (mapcat (fn [[original-status group]]
                   (let [total-count (count group)
                         unresolved-count (count-unresolved-in-group group)
                         section-header (create-section-header-item original-status total-count unresolved-count)
                         file-items (map instruction->quickpick-item group)
                         ;; Add button to first file item if we have unresolved items and it's a missing group
                         actioned-file-items (if (and (> unresolved-count 0)
                                                      (#{:original/missing-in-stable :original/missing-in-insiders} original-status)
                                                      (seq file-items))
                                               (let [first-item (first file-items)
                                                     rest-items (rest file-items)
                                                     bulk-button (case original-status
                                                                   :original/missing-in-stable
                                                                   #js {:iconPath (vscode/ThemeIcon. "arrow-left")
                                                                        :tooltip (str "Sync All to Stable (" unresolved-count " files)")}
                                                                   :original/missing-in-insiders
                                                                   #js {:iconPath (vscode/ThemeIcon. "arrow-right")
                                                                        :tooltip (str "Sync All to Insiders (" unresolved-count " files)")})
                                                     actioned-first-item #js {:label (.-label first-item)
                                                                              :iconPath (.-iconPath first-item)
                                                                              :description (.-description first-item)
                                                                              :detail (.-detail first-item)
                                                                              :itemType "file-with-bulk-button"
                                                                              :buttons #js [bulk-button]
                                                                              :fileInfo (.-fileInfo first-item)
                                                                              :bulkAction (case original-status
                                                                                            :original/missing-in-stable "sync-all-to-stable"
                                                                                            :original/missing-in-insiders "sync-all-to-insiders")}]
                                                 (concat [actioned-first-item] rest-items))
                                               file-items)]
                     (concat [section-header] actioned-file-items))))
         (into []))))

(defn show-file-preview!+
  "Shows file preview for non-conflict files"
  [{:instruction/keys [stable insiders]}]
  (let [file-uri (or (:location/uri stable) (:location/uri insiders))]
    (when file-uri
      (vscode/commands.executeCommand "vscode.open" file-uri #js {:preview true
                                                                  :preserveFocus true}))))

(defn handle-button-trigger
  "Pure function to handle picker item button trigger events"
  [button item instructions]
  (let [action (-> button .-iconPath keyword)]
    (case action
      :prompt-sync.action/sync-to-stable [:action/sync-to-stable (:instruction/filename item)]
      :prompt-sync.action/sync-to-insiders [:action/sync-to-insiders (:instruction/filename item)]
      :prompt-sync.action/skip [:action/skip (:instruction/filename item)]
      :prompt-sync.action/diff [:action/diff item]
      [:action/unknown action])))

(defn handle-picker-accept
  "Pure function to determine bulk action from picker selection"
  [picker]
  (when-let [selected-items (and picker (.-selectedItems picker))]
    (when (> (.-length selected-items) 0)
      (let [first-item (aget selected-items 0)
            item-label (.-label first-item)]
        (case item-label
          "🚀 Sync all to Stable" :prompt-sync.action/sync-all-to-stable
          "🚀 Sync all to Insiders" :prompt-sync.action/sync-all-to-insiders
          "📋 Sync all missing to both" :prompt-sync.action/sync-all-missing
          "⏭️ Skip all missing" :prompt-sync.action/skip-all-missing
          nil)))))

(defn show-instructions-picker!+
  "Shows QuickPick for all files with appropriate preview and selection behavior"
  ([all-instructions] (show-instructions-picker!+ all-instructions nil))
  ([all-instructions last-active-item]
   (if (empty? all-instructions)
     (p/resolved nil)
     (let [status-item (instructions->status-summary-item all-instructions)
           grouped-items (instructions->menu-items all-instructions)
           items (into [status-item] grouped-items)
           picker (vscode/window.createQuickPick)
           last-active-index (when last-active-item
                               (when-let [item-index (->> items
                                                          (map-indexed vector)
                                                          (filter (fn [[_idx item]]
                                                                    (and (#{"file" "file-with-bulk-button"} (.-itemType item))
                                                                         (when (.-fileInfo item)
                                                                           (= (.-filename (.-fileInfo item))
                                                                              (:instruction/filename last-active-item))))))
                                                          (first)
                                                          (first))]
                                 item-index))]

       (set! (.-items picker) (into-array items))
       (set! (.-title picker) "Prompt Sync: Stable ↔ Insiders")
       (set! (.-placeholder picker) "Select conflicts to resolve, others for preview")
       (set! (.-ignoreFocusOut picker) true)

       (when (and last-active-index (< last-active-index (count items)))
         (set! (.-activeItems picker) #js [(nth items last-active-index)]))

       (.onDidChangeActive picker
                           (fn [active-items]
                             (when-let [first-item (first active-items)]
                               (let [item-type (.-itemType first-item)]
                                 (case item-type
                                   "status" nil
                                   "section-header" nil
                                   "bulk-action" nil
                                   ("file" "file-with-bulk-button")
                                   (let [file-info (.-fileInfo first-item)
                                         filename (.-filename file-info)
                                         is-conflict (.-isConflict file-info)]
                                     (if is-conflict
                                       (let [instruction-info (first (filter #(= (:instruction/filename %) filename) all-instructions))]
                                         (when instruction-info
                                           (show-diff-preview!+ instruction-info)))
                                       (let [instruction-data (first (filter #(= (:instruction/filename %) filename) all-instructions))]
                                         (when instruction-data
                                           (show-file-preview!+ instruction-data)))))
                                   nil)))))

       (js/Promise.
        (fn [resolve _reject]
          ;; Button event handling - use the resolve function from the Promise
          (.onDidTriggerItemButton picker
                                   (fn [event]
                                     (let [item (.-item event)
                                           button (.-button event)
                                           tooltip (.-tooltip button)
                                           bulk-action (.-bulkAction item)
                                           file-info (.-fileInfo item)
                                           filename (when file-info (.-filename file-info))]
                                       (log! :debug "🔘 Button triggered! Tooltip:" tooltip "Action:" bulk-action)
                                       ;; Hide picker and resolve with bulk action, preserving which item was clicked
                                       (.hide picker)
                                       (resolve {:bulk-action-request true
                                                 :action bulk-action
                                                 :all-instructions all-instructions
                                                 :button-item-filename filename}))))

          (.onDidAccept picker
                        (fn []
                          (when-let [selected (first (.-selectedItems picker))]
                            (let [item-type (.-itemType selected)]
                              (case item-type
                                "status"
                                (do
                                  (.hide picker)
                                  (resolve {:bulk-operation-request true
                                            :all-instructions all-instructions}))

                                "section-header"
                                (log! :debug "📂 Section header selected - no action")

                                "bulk-action"
                                (let [bulk-action (.-bulkAction selected)]
                                  (log! :debug "🔄 Bulk action triggered:" bulk-action)
                                  (.hide picker)
                                  (resolve {:bulk-action-request true
                                            :action bulk-action
                                            :all-instructions all-instructions}))

                                ("file" "file-with-bulk-button")
                                (let [file-info (.-fileInfo selected)
                                      filename (.-filename file-info)
                                      instruction-info (first (filter #(= (:instruction/filename %) filename) all-instructions))
                                      needs-resolution? (= (:instruction/action-needed instruction-info) :resolve)]
                                  (if needs-resolution?
                                    (do (.hide picker)
                                        (resolve instruction-info))
                                    (log! :debug "📁 Preview only:" filename)))

                                (log! :debug "❓ Unknown item type selected:" item-type))))))
          (.onDidHide picker
                      (fn []
                        (resolve nil)))
          (.show picker)))))))

(def conflict-actions
  [{:label "Choose Stable"
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
    :action "prompt-sync.action/skip"}])

(def missing-stable-actions
  [{:label "Sync to Stable"
    :iconPath (vscode/ThemeIcon. "arrow-left")
    :description "Copy file from insiders to stable"
    :action "prompt-sync.action/sync-to-stable"}
   {:label "Skip"
    :iconPath (vscode/ThemeIcon. "close")
    :description "Leave file only in insiders"
    :action "prompt-sync.action/skip"}])

(def missing-insiders-actions
  [{:label "Sync to Insiders"
    :iconPath (vscode/ThemeIcon. "arrow-right")
    :description "Copy file from stable to insiders"
    :action "prompt-sync.action/sync-to-insiders"}
   {:label "Skip"
    :iconPath (vscode/ThemeIcon. "close")
    :description "Leave file only in stable"
    :action "prompt-sync.action/skip"}])

(def default-actions
  [{:label "Skip"
    :iconPath (vscode/ThemeIcon. "close")
    :description "No action available"
    :action "prompt-sync.action/skip"}])

(defn show-resolution-menu!+
  "Shows resolution options menu for conflicts and missing files"
  [{:instruction/keys [filename status]}]
  (log! :debug "📋 show-resolution-menu!+ called for:" filename "status:" status)
  (let [actions (case status
                  :status/conflict conflict-actions
                  :status/missing-in-stable missing-stable-actions
                  :status/missing-in-insiders missing-insiders-actions
                  default-actions)]
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

(defn show-status-resolution-menu!+
  "Shows bulk resolution options for all missing files based on status summary"
  [instructions]
  (let [missing-stable (filter #(= (:instruction/status %) :status/missing-in-stable) instructions)
        missing-insiders (filter #(= (:instruction/status %) :status/missing-in-insiders) instructions)
        all-missing (concat missing-stable missing-insiders)]
    ;; No bulk operations available - return nil (no-op like section headers)
    (if-not (seq all-missing)
      (p/resolved nil)
      (let [actions (cond-> []
                      (seq missing-stable)
                      (conj {:label (str "Sync All to Stable (" (count missing-stable) " files)")
                             :iconPath (vscode/ThemeIcon. "arrow-left")
                             :description "Copy all missing files from Insiders to Stable"
                             :action "prompt-sync.action/sync-all-to-stable"})

                      (seq missing-insiders)
                      (conj {:label (str "Sync All to Insiders (" (count missing-insiders) " files)")
                             :iconPath (vscode/ThemeIcon. "arrow-right")
                             :description "Copy all missing files from Stable to Insiders"
                             :action "prompt-sync.action/sync-all-to-insiders"})

                      (seq all-missing)
                      (conj {:label (str "Sync All Missing (" (count all-missing) " files)")
                             :iconPath (vscode/ThemeIcon. "arrow-swap")
                             :description "Copy all missing files to their appropriate destinations"
                             :action "prompt-sync.action/sync-all-missing"})

                      (seq all-missing)
                      (conj {:label "Skip All Missing"
                             :iconPath (vscode/ThemeIcon. "close")
                             :description "Leave all missing files as-is"
                             :action "prompt-sync.action/skip-all-missing"}))]
        (-> (vscode/window.showQuickPick
             (clj->js actions)
             #js {:placeHolder "Bulk operations for missing files"
                  :ignoreFocusOut true})
            (.then (fn [choice]
                     (when choice
                       (keyword (.-action choice))))))))))

(defn construct-file-uris
  "Pure function to construct file URIs from instruction and directories"
  [instruction {:prompt-sync/keys [stable-dir insiders-dir]}]
  (let [{:instruction/keys [filename stable insiders]} instruction
        stable-uri (or (:location/uri stable)
                       (when stable-dir (vscode/Uri.file (path/join stable-dir filename))))
        insiders-uri (or (:location/uri insiders)
                         (when insiders-dir (vscode/Uri.file (path/join insiders-dir filename))))]
    {:file-uris/stable-uri stable-uri
     :file-uris/insiders-uri insiders-uri}))

(defn resolve-instruction!+
  "Executes the chosen resolution action for conflicts and missing files, returns result data"
  [instruction choice dirs]
  (log! :debug "🔧 resolve-instruction!+ called with:")
  (log! :debug "  Choice:" choice)
  (log! :debug "  Choice type:" (type choice))
  (log! :debug "  Instruction filename:" (:instruction/filename instruction))
  (let [{:instruction/keys [filename]} instruction
        {:file-uris/keys [stable-uri insiders-uri]} (construct-file-uris instruction dirs)]
    (log! :debug "  Stable file URI:" stable-uri)
    (log! :debug "  Insiders file URI:" insiders-uri)
    (case choice
      ;; Conflict resolution actions
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

      ;; Missing file sync actions
      :prompt-sync.action/sync-to-stable
      (do (log! :debug "  → Syncing to stable (missing file)")
          (p/let [_ (copy-file!+ {:prompt-sync/source-uri insiders-uri
                                  :prompt-sync/target-uri stable-uri})]
            {:prompt-sync.resolution/action :sync-to-stable :prompt-sync.resolution/filename filename :prompt-sync.resolution/success true}))

      :prompt-sync.action/sync-to-insiders
      (do (log! :debug "  → Syncing to insiders (missing file)")
          (p/let [_ (copy-file!+ {:prompt-sync/source-uri stable-uri
                                  :prompt-sync/target-uri insiders-uri})]
            {:prompt-sync.resolution/action :sync-to-insiders :prompt-sync.resolution/filename filename :prompt-sync.resolution/success true}))

      :prompt-sync.action/skip
      (do (log! :debug "  → Skipping")
          (p/resolved {:prompt-sync.resolution/action :skip :prompt-sync.resolution/filename filename :prompt-sync.resolution/success true}))

      (do (log! :debug "  → Cancelled")
          (p/resolved {:prompt-sync.resolution/action :cancelled :prompt-sync.resolution/filename filename :prompt-sync.resolution/success false})))))

(defn filter-missing-stable
  "Pure function to filter instructions missing in stable"
  [instructions]
  (filter #(= (:instruction/status %) :status/missing-in-stable) instructions))

(defn filter-missing-insiders
  "Pure function to filter instructions missing in insiders"
  [instructions]
  (filter #(= (:instruction/status %) :status/missing-in-insiders) instructions))

(defn filter-all-missing
  "Pure function to filter all missing instructions"
  [instructions]
  (filter #(#{:status/missing-in-stable :status/missing-in-insiders} (:instruction/status %)) instructions))

(defn get-bulk-operation-targets
  "Pure function to get target instructions for bulk operations"
  [all-instructions choice]
  (case choice
    :prompt-sync.action/sync-all-to-stable (filter-missing-stable all-instructions)
    :prompt-sync.action/sync-all-to-insiders (filter-missing-insiders all-instructions)
    :prompt-sync.action/sync-all-missing (filter-all-missing all-instructions)
    :prompt-sync.action/skip-all-missing (filter-all-missing all-instructions)
    []))

(defn process-bulk-instruction
  "Pure function to process single instruction in bulk operation"
  [instruction targets choice]
  (when (some #(= (:instruction/filename %) (:instruction/filename instruction)) targets)
    (case choice
      :prompt-sync.action/sync-all-to-stable [:action/sync-to-stable (:instruction/filename instruction)]
      :prompt-sync.action/sync-all-to-insiders [:action/sync-to-insiders (:instruction/filename instruction)]
      :prompt-sync.action/sync-all-missing
      (case (:instruction/status instruction)
        :status/missing-in-stable [:action/sync-to-stable (:instruction/filename instruction)]
        :status/missing-in-insiders [:action/sync-to-insiders (:instruction/filename instruction)]
        nil)
      :prompt-sync.action/skip-all-missing [:action/skip (:instruction/filename instruction)]
      nil)))

(defn update-instruction-status-bulk
  "Pure function to update instruction status after bulk operation"
  [instruction targets choice]
  (if (some #(= (:instruction/filename %) (:instruction/filename instruction)) targets)
    (let [resolution (case choice
                       :prompt-sync.action/sync-all-to-stable :resolution/sync-to-stable
                       :prompt-sync.action/sync-all-to-insiders :resolution/sync-to-insiders
                       :prompt-sync.action/sync-all-missing
                       (case (:instruction/status instruction)
                         :status/missing-in-stable :resolution/sync-to-stable
                         :status/missing-in-insiders :resolution/sync-to-insiders)
                       :prompt-sync.action/skip-all-missing :resolution/skipped)]
      (assoc instruction
             :instruction/status :status/resolved
             :instruction/resolution resolution
             :instruction/action-needed :none))
    instruction))

(defn apply-bulk-operation!+
  "Applies bulk resolution operation to multiple instructions"
  [all-instructions choice dirs]
  (let [targets (get-bulk-operation-targets all-instructions choice)]
    (if (empty? targets)
      (p/resolved all-instructions)
      (p/let [;; Execute all file operations in parallel
              _ (p/all (map (fn [instruction]
                              (when-let [action-data (process-bulk-instruction instruction targets choice)]
                                (let [[action _filename] action-data]
                                  (resolve-instruction!+ instruction action dirs))))
                            targets))]
        ;; Update all instruction statuses
        (map (fn [instruction]
               (update-instruction-status-bulk instruction targets choice))
             all-instructions)))))

(defn handle-bulk-operations!+
  "Handles status item selection by showing bulk operation menu and applying chosen action"
  [all-instructions dirs]
  (p/let [choice (show-status-resolution-menu!+ all-instructions)]
    (if choice
      (p/let [updated-instructions (apply-bulk-operation!+ all-instructions choice dirs)]
        {:bulk-operation-applied true
         :choice choice
         :updated-instructions updated-instructions})
      {:bulk-operation-applied false})))

(defn record-resolution
  "Pure function for updating instruction status after conflict resolution"
  [all-instructions resolved-filename resolution-type]
  (map (fn [instruction]
         (if (= (:instruction/filename instruction) resolved-filename)
           (assoc instruction
                  :instruction/status :status/resolved
                  :instruction/resolution resolution-type
                  :instruction/action-needed :none)
           instruction))
       all-instructions))

(defn resolve-conflict!+
  "Handles single conflict resolution with UI interaction"
  [selected-instruction all-instructions dirs]
  (p/let [choice (show-resolution-menu!+ selected-instruction)]
    (if choice
      (p/let [_ (resolve-instruction!+ selected-instruction choice dirs)
              resolution-type (case choice
                                :prompt-sync.action/choose-stable :resolution/choose-stable
                                :prompt-sync.action/choose-insiders :resolution/choose-insiders
                                :prompt-sync.action/sync-to-stable :resolution/sync-to-stable
                                :prompt-sync.action/sync-to-insiders :resolution/sync-to-insiders
                                :prompt-sync.action/skip :resolution/skipped)
              updated-instructions (record-resolution all-instructions
                                                                               (:instruction/filename selected-instruction)
                                                                               resolution-type)]
        updated-instructions)
      (p/resolved :cancelled))))

(defn main-menu-loop!+
  "Show instructions picker offering conflict resolution actions for conflicts
   Keep showing the instructions menu until the user cancels"
  ([instructions dirs] (main-menu-loop!+ instructions dirs nil))
  ([instructions dirs last-active-item]
   (def instructions instructions) ; excellent for interactive debugging
   (p/loop [current-instructions instructions
            last-active last-active-item]
     (p/let [selected-instruction (show-instructions-picker!+ current-instructions last-active)]
       (if selected-instruction
         (cond
           (:bulk-operation-request selected-instruction)
           ;; Handle bulk operations from status item
           (p/let [bulk-result (handle-bulk-operations!+ (:all-instructions selected-instruction) dirs)]
             (if (:bulk-operation-applied bulk-result)
               (p/recur (:updated-instructions bulk-result) nil) ; Updated instructions, no last active
               (p/recur current-instructions last-active))) ; No changes, keep last active

           (:bulk-action-request selected-instruction)
           ;; Handle direct bulk actions from embedded buttons
           (p/let [action-keyword (keyword (str "prompt-sync.action/" (:action selected-instruction)))
                   updated-instructions (apply-bulk-operation!+ (:all-instructions selected-instruction) action-keyword dirs)
                   ;; Preserve the item that had the button clicked, if available
                   button-item (when-let [filename (:button-item-filename selected-instruction)]
                                 (first (filter #(= (:instruction/filename %) filename) updated-instructions)))]
             (p/recur updated-instructions button-item)) ; Preserve the button item as last active

           :else
           ;; Handle single instruction resolution
           (p/let [updated-instructions (resolve-conflict!+ selected-instruction current-instructions dirs)]
             (if (= updated-instructions :cancelled)
               (p/recur current-instructions selected-instruction) ; Keep the selected item as the last active
               (p/recur updated-instructions selected-instruction)))) ; Pass along the selected item for memory
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
           _ (def compared compared)]

     (log! :debug (str "Found " (count compared) " instructions"))
     (main-menu-loop!+ compared dirs))))

(defn generate-stable-content
  "Generates stable content for different file types"
  [file-type index]
  (case file-type
    ".instruction.md" (str "# Stable Instruction " index "\nThese are stable coding guidelines.\n\n- Always use stable APIs\n- Avoid experimental features")
    ".prompt.md" (str "# Stable Prompt " index "\nYou are a stable assistant.\n\n## Rules\n- Be conservative\n- Follow stable guidelines")
    ".chatmode.md" (str "# Stable Chat Mode " index "\nconversational: true\ntemperature: 0.3\n\n## Description\nStable conversation mode")))

(defn generate-insiders-content
  "Generates insiders content for different file types"
  [file-type index]
  (case file-type
    ".instruction.md" (str "# Insiders Instruction " index "\nThese are experimental coding guidelines.\n\n- Try new APIs\n- Embrace experimental features")
    ".prompt.md" (str "# Insiders Prompt " index "\nYou are an experimental assistant.\n\n## Rules\n- Be innovative\n- Try new approaches")
    ".chatmode.md" (str "# Insiders Chat Mode " index "\nconversational: true\ntemperature: 0.8\n\n## Description\nExperimental conversation mode")))

(defn generate-test-files
  "Generates test files based on status counts map."
  [{:test-files/keys [identical conflicts stable-only insiders-only]}]
  (let [file-types [".instruction.md" ".prompt.md" ".chatmode.md"]]
    (concat
     (for [i (range identical)]
       (let [file-type (nth file-types (mod i (count file-types)))
             content (str "# Identical " (inc i) "\nThis file is the same in both")]
         {:prompt-sync.file/filename (str "identical" (inc i) file-type)
          :prompt-sync.file/stable-content content
          :prompt-sync.file/insiders-content content}))

     (for [i (range conflicts)]
       (let [file-type (nth file-types (mod i (count file-types)))]
         {:prompt-sync.file/filename (str "conflict" (inc i) file-type)
          :prompt-sync.file/stable-content (generate-stable-content file-type (inc i))
          :prompt-sync.file/insiders-content (generate-insiders-content file-type (inc i))}))

     (for [i (range stable-only)]
       (let [file-type (nth file-types (mod i (count file-types)))]
         {:prompt-sync.file/filename (str "stable-only" (inc i) file-type)
          :prompt-sync.file/stable-content (str "# Stable Only " (inc i) "\nThis file only exists in stable")
          :prompt-sync.file/location :stable-only}))

     (for [i (range insiders-only)]
       (let [file-type (nth file-types (mod i (count file-types)))]
         {:prompt-sync.file/filename (str "insiders-only" (inc i) file-type)
          :prompt-sync.file/insiders-content (str "# Insiders Only " (inc i) "\nThis file only exists in insiders")
          :prompt-sync.file/location :insiders-only})))))

(def test-files (generate-test-files {:test-files/identical 3
                                      :test-files/conflicts 4
                                      :test-files/stable-only 2
                                      :test-files/insiders-only 1}))

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
         (mapcat identity)  ; Flatten the pairs into a single collection
         (filter some?)     ; Remove nil values
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

;; Entry point for script execution. IMPORTANT: AI Agent should run `main-test`, unless instructed to run `main`)
(defn ^:export main []
  (p/catch
   (sync-prompts!+ {:prompt-sync/test-mode? false})
   (fn [error]
     (vscode/window.showErrorMessage (str "Sync error: " (.-message error)))
     (js/console.error "Prompt sync error:" error))))

;; Entry-point for testing. IMPORTANT: AI Agent should this one during testing
(defn ^:export main-test
  "Entry point for test mode - uses /tmp directories.
   Optionally accepts file-config map: {:status/identical N :conflicts N :stable-only N :insiders-only N}"
  []
  (-> (p/let [_ (cleanup-test-environment!+)
              test-dirs (create-test-environment!+)
              _ (populate-test-files!+ test-dirs test-files)]
        (sync-prompts!+ {:prompt-sync/test-mode? true}))
      (.catch (fn [error]
                (vscode/window.showErrorMessage (str "Test sync error: " (.-message error)))
                (js/console.error "Test prompt sync error:" error)))))

(when (= (joyride/invoked-script) joyride/*file*) ; Auto-run when script is invoked
  #_(main-test)
  (main))