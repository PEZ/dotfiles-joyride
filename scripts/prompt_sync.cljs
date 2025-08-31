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
  ([{:prompt-sync/keys [test-mode?] :or {test-mode? false}}]
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
         :conflict {:prompt-sync.conflict/filename filename
                    :prompt-sync.conflict/type type}}))

(defn show-diff-preview!+
  "Opens VS Code diff editor for conflict preview"
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
                              (show-diff-preview!+ (.-conflict first-item)))))

      (js/Promise.
       (fn [resolve _reject]
         (.onDidAccept picker
                       (fn []
                         (let [selected (first (.-selectedItems picker))]
                           (.hide picker)
                           (resolve (when selected (.-conflict selected))))))
         (.onDidHide picker
                     (fn []
                       (resolve nil)))
         (.show picker))))))

(defn show-resolution-menu!+
  "Shows resolution options menu"
  [{:prompt-sync.conflict/keys [filename]}]
  (let [actions [{:label "Choose Stable"
                  :iconPath (vscode/ThemeIcon. "arrow-left")
                  :description "Copy stable version to insiders"
                  :action :prompt-sync.action/choose-stable}
                 {:label "Choose Insiders"
                  :iconPath (vscode/ThemeIcon. "arrow-right")
                  :description "Copy insiders version to stable"
                  :action :prompt-sync.action/choose-insiders}
                 {:label "Skip"
                  :iconPath (vscode/ThemeIcon. "close")
                  :description "Leave both files as-is"
                  :action :prompt-sync.action/skip}]]
    (-> (vscode/window.showQuickPick
         (clj->js actions)
         #js {:placeHolder (str "How to resolve: " filename)})
        (.then (fn [choice]
                 (when choice
                   (keyword (.-action choice))))))))

(defn resolve-conflict!+
  "Executes the chosen resolution action"
  [conflict choice]
  (let [{:prompt-sync.conflict/keys [stable-file insiders-file]} conflict]
    (case choice
      :prompt-sync.action/choose-stable
      (copy-file!+ {:prompt-sync/source-uri (:prompt-sync.file/uri stable-file)
                    :prompt-sync/target-uri (:prompt-sync.file/uri insiders-file)})

      :prompt-sync.action/choose-insiders
      (copy-file!+ {:prompt-sync/source-uri (:prompt-sync.file/uri insiders-file)
                    :prompt-sync/target-uri (:prompt-sync.file/uri stable-file)})

      :prompt-sync.action/skip
      (p/resolved :skipped)

      (p/resolved :cancelled))))

(defn sync-prompts!+
  "Main entry point - orchestrates the entire sync process"
  ([] (sync-prompts!+ {}))
  ([{:prompt-sync/keys [test-mode?] :or {test-mode? false}}]
   (p/let [dirs (get-user-prompts-dirs {:prompt-sync/test-mode? test-mode?})
           {:prompt-sync/keys [stable-dir insiders-dir test-mode?]} dirs

           _ (if test-mode?
               (vscode/window.showInformationMessage "🧪 TEST MODE: Using /tmp directories")
               (vscode/window.showInformationMessage "Starting prompt sync..."))

           sync-result (compare-directories!+ {:prompt-sync/stable-dir stable-dir
                                               :prompt-sync/insiders-dir insiders-dir})

           {:prompt-sync.result/keys [conflicts]} sync-result

           ;; Copy missing files automatically
           copy-summary (copy-missing-files!+ sync-result dirs)

           _ (vscode/window.showInformationMessage
              (str "Auto-copied: " (:copied-from-stable copy-summary) " from stable, "
                   (:copied-from-insiders copy-summary) " from insiders"))]

     ;; Handle conflicts in a loop
     (loop [remaining-conflicts conflicts]
       (if (empty? remaining-conflicts)
         (vscode/window.showInformationMessage "Prompt sync completed!")
         (p/let [selected-conflict (show-conflict-picker!+ {:prompt-sync.result/conflicts remaining-conflicts})]
           (if selected-conflict
             (p/let [choice (show-resolution-menu!+ selected-conflict)
                     _ (when choice (resolve-conflict!+ selected-conflict choice))
                     resolved-conflicts (if choice #{selected-conflict} #{})]
               (recur (remove resolved-conflicts remaining-conflicts)))
             ;; User cancelled
             (vscode/window.showInformationMessage "Prompt sync cancelled"))))))))

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