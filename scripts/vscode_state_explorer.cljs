(ns vscode-state-explorer
  (:require [joyride.core :as joyride]
            [clojure.string :as string]
            ["child_process" :as cp]
            ["vscode" :as vscode]
            ["fs" :as fs]
            ["os" :as os]
            ["path" :as path]))

(defonce !db (atom {:explorer/last-selected-key nil
                    :explorer/state-db-path nil}))

(defn- vscode-app-folder
  "Returns the folder name in Application Support for the running VS Code variant"
  []
  (string/replace vscode/env.appName #"^Visual Studio ", ""))

(defn vscode-state-dir []
  (path/join (os/homedir) "Library" "Application Support" (vscode-app-folder) "User" "workspaceStorage"))

(defn- exec-sqlite3
  "Execute a sqlite3 command synchronously. Returns stdout string."
  [db-path sql]
  (-> (cp/execSync (str "sqlite3 " (pr-str db-path) " " (pr-str sql))
                   #js {:maxBuffer (* 10 1024 1024)})
      str))

(defn list-keys
  "List all keys in the state db, optionally filtered by SQL LIKE pattern"
  ([db-path] (list-keys db-path nil))
  ([db-path pattern]
   (let [sql (if pattern
               (str "SELECT key FROM ItemTable WHERE key LIKE " (pr-str pattern) " ORDER BY key")
               "SELECT key FROM ItemTable ORDER BY key")]
     (->> (exec-sqlite3 db-path sql)
          string/split-lines
          (remove string/blank?)))))

(defn get-value
  "Get a value from the state db, parsed as JSON (string keys)"
  [db-path key]
  (let [result (exec-sqlite3 db-path
                             (str "SELECT value FROM ItemTable WHERE key = " (pr-str key)))]
    (when (seq (string/trim result))
      (js/JSON.parse result))))

(defn get-stats
  "Get overview stats: total keys, total size, and largest entries"
  [db-path]
  (let [keys-list (list-keys db-path)
        sizes-raw (exec-sqlite3 db-path
                                "SELECT key, length(value) as size FROM ItemTable ORDER BY size DESC")
        sizes (->> (string/split-lines sizes-raw)
                   (remove string/blank?)
                   (map #(let [[k s] (string/split % #"\|")]
                           {:state/key k :state/size (js/parseInt s 10)})))]
    {:state/total-keys (count keys-list)
     :state/total-size (reduce + (map :state/size sizes))
     :state/largest (take 10 sizes)}))

(defn backup-keys
  "Backup keys matching pattern to a map of {key value}"
  [db-path pattern]
  (->> (list-keys db-path pattern)
       (map (fn [k] [k (get-value db-path k)]))
       (into {})
       clj->js))

(defn set-value!
  "Set a value in the state db (value should be a JS data structure)"
  [db-path key value]
  (let [json-str (js/JSON.stringify value)
        escaped-json (string/replace json-str "'" "''")]
    (exec-sqlite3 db-path
                  (str "INSERT OR REPLACE INTO ItemTable (key, value) VALUES ("
                       (pr-str key) ", '" escaped-json "')"))))

(defn delete-key!
  "Delete a key from the state db"
  [db-path key]
  (exec-sqlite3 db-path (str "DELETE FROM ItemTable WHERE key = " (pr-str key))))

(defn delete-keys-matching!
  "Delete all keys matching a LIKE pattern. Returns deleted info."
  [db-path pattern]
  (let [keys-to-delete (list-keys db-path pattern)]
    (exec-sqlite3 db-path (str "DELETE FROM ItemTable WHERE key LIKE " (pr-str pattern)))
    {:state/deleted-count (count keys-to-delete)
     :state/deleted-keys keys-to-delete}))

(defn- get-keys-with-sizes [db-path]
  (let [result (exec-sqlite3 db-path
                             "SELECT key, length(value) as size FROM ItemTable ORDER BY key")]
    (->> (string/split-lines result)
         (remove string/blank?)
         (map #(let [[k s] (string/split % #"\|")]
                 {:state/key k :state/size (js/parseInt s 10)})))))

(defn- get-value-preview [db-path key max-len]
  (let [result (exec-sqlite3 db-path
                             (str "SELECT substr(value, 1, " max-len ") FROM ItemTable WHERE key = " (pr-str key)))]
    (-> result string/trim (string/replace #"\n" " "))))

(defn- format-size [bytes]
  (cond
    (> bytes (* 1024 1024)) (str (js/Math.round (/ bytes 1024 1024)) " MB")
    (> bytes 1024) (str (js/Math.round (/ bytes 1024)) " KB")
    :else (str bytes " bytes")))

(defn- backup-timestamp []
  (let [d (js/Date.)]
    (str (.getFullYear d)
         (-> (.getMonth d) inc (.toString) (.padStart 2 "0"))
         (-> (.getDate d) (.toString) (.padStart 2 "0"))
         "-"
         (-> (.getHours d) (.toString) (.padStart 2 "0"))
         (-> (.getMinutes d) (.toString) (.padStart 2 "0"))
         (-> (.getSeconds d) (.toString) (.padStart 2 "0")))))

(defn- backup-db! [db-path]
  (let [dir (path/dirname db-path)
        base (path/basename db-path ".vscdb")
        backup-path (path/join dir (str base "-" (backup-timestamp) ".vscdb"))]
    (fs/copyFileSync db-path backup-path)
    (vscode/window.showInformationMessage (str "Backed up to: " (path/basename backup-path)))
    backup-path))

(defn- get-db-info [db-path]
  (when db-path
    (try
      (let [stats (fs/statSync db-path)
            key-count (count (list-keys db-path))]
        {:db/key-count key-count
         :db/file-size (.-size stats)})
      (catch :default _e nil))))

(defn- make-picker-item [db-path {:state/keys [key size]}]
  (let [size-str (if (> size 1024)
                   (str (js/Math.round (/ size 1024)) " KB")
                   (str size " bytes"))
        preview (get-value-preview db-path key 300)]
    #js {:label (str "$(key) " key)
         :description size-str
         :detail preview
         :stateKey key
         :buttons #js [#js {:iconPath (vscode/ThemeIcon. "eye")
                            :tooltip "Preview JSON"}
                       #js {:iconPath (vscode/ThemeIcon. "edit")
                            :tooltip "Edit JSON"}
                       #js {:iconPath (vscode/ThemeIcon. "trash")
                            :tooltip "Delete key"}]}))

(defn- handle-save-confirmation [db-path key saved-doc]
  (-> (vscode/window.showWarningMessage
       (str "Update key '" key "' in state db?")
       #js {:modal true}
       "Update")
      (.then
       (fn [answer]
         (when (= answer "Update")
           (try
             (let [new-content (.getText saved-doc)
                   new-value (js/JSON.parse new-content)]
               (set-value! db-path key new-value)
               (vscode/window.showInformationMessage (str "Updated: " key)))
             (catch :default e
               (vscode/window.showErrorMessage
                (str "Invalid JSON: " (.-message e))))))))))

(defn- setup-edit-watchers! [db-path key temp-path]
  (println "setup-edit-watchers!")
  (let [watchers (atom nil)
        save-disposable
        (vscode/workspace.onDidSaveTextDocument
         (fn [saved-doc]
           (when (= (.-fsPath (.-uri saved-doc)) temp-path)
             (handle-save-confirmation db-path key saved-doc))))]
    (reset! watchers {:save-disposable save-disposable})
    (swap! !db assoc-in [:explorer/edit-watchers temp-path]
           {:save-disposable save-disposable
            :db-path db-path
            :key key})))

(defn- edit-value! [db-path key]
  (let [safe-name (str (string/replace key #"[/:]" "_") ".json")
        temp-path (path/join (os/tmpdir) "vscode-state-edit" safe-name)
        existing-watcher (get-in @!db [:explorer/edit-watchers temp-path])]
    (if existing-watcher
      ;; Already have a watcher, just open the existing file
      (-> (vscode/workspace.openTextDocument temp-path)
          (.then #(vscode/window.showTextDocument % #js {:preview false})))
      ;; Create new file and watcher
      (let [value (get-value db-path key)
            json-str (js/JSON.stringify (clj->js value) nil 2)]
        (fs/mkdirSync (path/dirname temp-path) #js {:recursive true})
        (fs/writeFileSync temp-path json-str)
        (-> (vscode/workspace.openTextDocument temp-path)
            (.then
             (fn [doc]
               (vscode/window.showTextDocument doc #js {:preview false})
               (#'setup-edit-watchers! db-path key temp-path))))))))

(defn- preview-value! [db-path key]
  (let [value (get-value db-path key)
        json-str (js/JSON.stringify (clj->js value) nil 2)
        safe-name (-> key (string/replace #"[/\\:]" "_") (str ".json"))
        temp-path (path/join (os/tmpdir) "vscode-state-preview" safe-name)]
    (fs/mkdirSync (path/dirname temp-path) #js {:recursive true})
    (fs/writeFileSync temp-path json-str)
    (-> (vscode/workspace.openTextDocument temp-path)
        (.then #(vscode/window.showTextDocument % #js {:preview true
                                                       :preserveFocus false})))))

(defn show-state-explorer! [db-path]
  (let [picker (vscode/window.createQuickPick)
        keys-data (get-keys-with-sizes db-path)
        items (->> keys-data
                   (map #(make-picker-item db-path %))
                   into-array)
        last-key (:explorer/last-selected-key @!db)]
    (doto picker
      (-> .-items (set! items))
      (-> .-title (set! (str "State Explorer: " (path/basename (path/dirname db-path)))))
      (-> .-placeholder (set! "Search keys and values..."))
      (-> .-matchOnDescription (set! true))
      (-> .-matchOnDetail (set! true))
      (-> .-ignoreFocusOut (set! true)))
    (when last-key
      (when-let [active-item (->> items
                                  (filter #(= (.-stateKey %) last-key))
                                  first)]
        (set! (.-activeItems picker) #js [active-item])))
    (.onDidTriggerItemButton picker
                             (fn [e]
                               (let [key (.-stateKey (.-item e))
                                     icon-id (-> e .-button .-iconPath .-id)]
                                 (swap! !db assoc :explorer/last-selected-key key)
                                 (case icon-id
                                   "eye" (preview-value! db-path key)
                                   "edit" (do (.hide picker)
                                              (edit-value! db-path key))
                                   "trash" (-> (vscode/window.showWarningMessage
                                                 (str "Delete key '" key "'?")
                                                 #js {:modal true}
                                                 "Delete")
                                                (.then (fn [answer]
                                                         (when (= answer "Delete")
                                                           (delete-key! db-path key)
                                                           (.hide picker)
                                                           (show-state-explorer! db-path)))))
                                   nil))))
    (.onDidAccept picker
                  (fn []
                    (when-let [selected (first (.-selectedItems picker))]
                      (let [key (.-stateKey selected)]
                        (swap! !db assoc :explorer/last-selected-key key)
                        (.hide picker)
                        (preview-value! db-path key)))))
    (.onDidHide picker #(.dispose picker))
    (.show picker)
    picker))

(defn pick-state-db! []
  (-> (vscode/window.showOpenDialog
       #js {:canSelectFiles true
            :canSelectFolders false
            :canSelectMany false
            :defaultUri (vscode/Uri.file (vscode-state-dir))
            :filters #js {"VS Code State DB" #js ["vscdb"]}
            :title "Select VS Code State Database"})
      (.then (fn [uris]
               (when (seq uris)
                 (let [db-path (.-fsPath (first uris))]
                   (swap! !db assoc :explorer/state-db-path db-path)
                   db-path))))))

(defn show-main-menu! []
  (let [db-path (:explorer/state-db-path @!db)
        db-info (get-db-info db-path)
        has-db? (and db-path db-info)
        items (cond-> [#js {:label "$(folder-opened) Select State DB"
                            :action :select-db
                            :buttons #js [#js {:iconPath (vscode/ThemeIcon. "folder-library")
                                               :tooltip "Reveal in Finder"}]}]
                has-db?
                (conj #js {:label "$(copy) Backup DB"
                           :action :backup-db})
                has-db?
                (conj #js {:label "$(list-tree) Explore State DB"
                           :description (str (:db/key-count db-info) " keys, "
                                             (format-size (:db/file-size db-info)))
                           :detail db-path
                           :action :explore}))
        picker (vscode/window.createQuickPick)]
    (doto picker
      (-> .-items (set! (into-array items)))
      (-> .-title (set! "VS Code State Explorer"))
      (-> .-placeholder (set! "Choose an action..."))
      (-> .-matchOnDetail (set! true))
      (-> .-ignoreFocusOut (set! true)))
    (.onDidTriggerItemButton picker
                             (fn [_e]
                               (let [reveal-path (if has-db?
                                                   db-path
                                                   (vscode-state-dir))]
                                 (vscode/commands.executeCommand "revealFileInOS"
                                                                 (vscode/Uri.file reveal-path)))))
    (.onDidAccept picker
                  (fn []
                    (when-let [selected (first (.-selectedItems picker))]
                      (.hide picker)
                      (case (.-action selected)
                        :select-db (-> (pick-state-db!)
                                       (.then #(when % (show-main-menu!))))
                        :backup-db (do (backup-db! db-path)
                                       (show-main-menu!))
                        :explore (show-state-explorer! db-path)
                        nil))))
    (.onDidHide picker #(.dispose picker))
    (.show picker)
    picker))

(defn main []
  (show-main-menu!))

(when (= (joyride/invoked-script) joyride/*file*)
  (main))

(comment
  ;; VS Code state.vscdb schema: single table ItemTable (key TEXT, value BLOB as JSON)

  (show-main-menu!)

  ;; Or use a specific test file directly
  (def test-db-path "/Users/pez/.config/joyride/test-files/state.vscdb")
  (show-state-explorer! test-db-path)

  (get-stats test-db-path)
  ;; => {:state/total-keys 116, :state/total-size 353214,
  ;;     :state/largest [{:state/key "betterthantomorrow.calva", :state/size 100241} ...]}

  (take 10 (list-keys test-db-path))

  ;; List keys matching pattern (SQL LIKE syntax: % = wildcard)
  (list-keys test-db-path "memento/%")

  (set-value! test-db-path "foo-key1" #js {:foo 42})
  (set-value! test-db-path "foo-key2" #js {:foo "bar"})

  (list-keys test-db-path "foo%")

  (get-value test-db-path "foo-key1")

  ;; Backup matching keys
  (def chat-backup (backup-keys test-db-path "foo%"))
  (keys chat-backup)

  (delete-key! test-db-path "foo-key2")

  (delete-keys-matching! test-db-path "old-extension%")

  @!db

  :rcf)

