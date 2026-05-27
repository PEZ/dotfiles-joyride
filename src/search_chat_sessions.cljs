(ns search-chat-sessions
  (:require ["vscode" :as vscode]
            ["fs" :as fs]
            ["path" :as path]
            ["os" :as os]
            [clojure.string :as str]))

(def storage-root
  (path/join (os/homedir) "Library/Application Support/Code/User/workspaceStorage"))

(defn find-current-ws-hash []
  (let [ws-file (some-> vscode/workspace.workspaceFile .-fsPath)]
    (->> (fs/readdirSync storage-root)
         (some (fn [hash]
                 (let [wj (path/join storage-root hash "workspace.json")]
                   (when (fs/existsSync wj)
                     (try
                       (let [data (js/JSON.parse (str (fs/readFileSync wj "utf8")))
                             p (.-workspace data)
                             decoded (when p (-> p js/decodeURIComponent (.replace "file://" "")))]
                         (when (= decoded ws-file) hash))
                       (catch js/Error _ nil)))))))))

(defn workspace-label [ws-hash]
  (let [wj (path/join storage-root ws-hash "workspace.json")]
    (when (fs/existsSync wj)
      (try
        (let [data (js/JSON.parse (str (fs/readFileSync wj "utf8")))
              p (or (.-workspace data) (.-folder data))]
          (when p
            (-> p js/decodeURIComponent (.replace "file://" "") path/basename)))
        (catch js/Error _ nil)))))

(defn time-ago [ms]
  (let [diff (- (js/Date.now) ms)
        min-f (/ diff 60000)
        hr-f  (/ min-f 60)
        day-f (/ hr-f 24)]
    (cond
      (< min-f 2)   "just now"
      (< min-f 60)  (str (.round js/Math min-f) "m ago")
      (< hr-f 24)   (str (.round js/Math hr-f) "h ago")
      :else          (str (.round js/Math day-f) "d ago"))))

(defn extract-response-text [resp]
  (->> (range (.-length resp))
       (keep (fn [i]
               (let [item (aget resp i)]
                 (when (and (nil? (.-kind item)) (.-value item))
                   (.-value item)))))
       (str/join " ")))

(defn extract-req-texts [req]
  (let [user-msg (some-> req .-message .-text)
        resp (.-response req)
        resp-text (when (and resp (js/Array.isArray resp))
                    (extract-response-text resp))]
    (filterv identity [user-msg resp-text])))

(defn extract-generated-title
  "Find last generatedTitle from response items in requests array"
  [reqs]
  (last
    (->> (range (.-length reqs))
         (mapcat (fn [i]
                   (let [resp (.-response (aget reqs i))]
                     (when (and resp (js/Array.isArray resp))
                       (->> (range (.-length resp))
                            (keep (fn [j]
                                    (.-generatedTitle (aget resp j))))))))))))

(defn parse-jsonl-first-line
  "Parse just the first line of a .jsonl file using buffered read"
  [fp]
  (let [fd (fs/openSync fp "r")
        buf (js/Buffer.alloc 65536)
        n (fs/readSync fd buf 0 65536)
        _ (fs/closeSync fd)
        chunk (.toString buf "utf8" 0 n)
        nl (.indexOf chunk "\n")
        first-line (if (pos? nl) (subs chunk 0 nl) chunk)
        parsed (js/JSON.parse first-line)]
    (.-v parsed)))

(defn parse-json-file
  "Parse a .json session file (old format, no v wrapper)"
  [fp]
  (js/JSON.parse (str (fs/readFileSync fp "utf8"))))

(defn session-from-data
  "Build a session map from parsed session data (works for both formats)"
  [data ws-hash fp]
  (let [sid (.-sessionId data)
        reqs (.-requests data)
        custom-title (.-customTitle data)
        title (or (when (seq custom-title) custom-title)
                  (extract-generated-title reqs)
                  "")
        turn-count (.-length reqs)
        ;; Timestamp: lastMessageDate (old format) or last request timestamp or file mtime
        last-msg-date (.-lastMessageDate data)
        last-req-ts (when (pos? turn-count)
                      (.-timestamp (aget reqs (dec turn-count))))
        file-mtime (-> (fs/statSync fp) .-mtimeMs)
        last-active (max (or last-msg-date 0) (or last-req-ts 0) file-mtime)
        ;; Content from initial requests only (no patch parsing)
        all-text (->> (range turn-count)
                      (mapcat #(extract-req-texts (aget reqs %)))
                      (str/join " | "))]
    (when sid
      {:id sid :ws-hash ws-hash :title title
       :turn-count turn-count :last-active last-active
       :content all-text
       :search-text (str/lower-case (str title " " all-text))})))

(defn scan-workspace-sessions [ws-hash]
  (let [sd (path/join storage-root ws-hash "chatSessions")]
    (when (fs/existsSync sd)
      (->> (fs/readdirSync sd)
           (keep (fn [f]
                   (try
                     (let [fp (path/join sd f)]
                       (cond
                         (.endsWith f ".jsonl")
                         (session-from-data (parse-jsonl-first-line fp) ws-hash fp)

                         (.endsWith f ".json")
                         (session-from-data (parse-json-file fp) ws-hash fp)

                         :else nil))
                     (catch js/Error _ nil))))
           vec))))

(defn scan-all-sessions [cur-hash]
  (let [ws-sessions (->> (fs/readdirSync storage-root)
                         (mapcat #(or (scan-workspace-sessions %) []))
                         vec)
        label-cache (atom {})
        with-labels (mapv (fn [s]
                            (let [h (:ws-hash s)
                                  lbl (or (get @label-cache h)
                                          (let [l (or (workspace-label h) h)]
                                            (swap! label-cache assoc h l)
                                            l))]
                              (assoc s :ws-label lbl)))
                          ws-sessions)
        cur (sort-by #(- (:last-active %)) (filter #(= (:ws-hash %) cur-hash) with-labels))
        other (sort-by #(- (:last-active %)) (filter #(not= (:ws-hash %) cur-hash) with-labels))]
    (vec (concat cur other))))

(defn session-display-title [s]
  (if (seq (:title s))
    (:title s)
    (let [first-msg (-> (:content s) (str/split #" \| ") first)]
      (if (and first-msg (seq first-msg))
        (str (subs first-msg 0 (min 60 (count first-msg))) "…")
        (:id s)))))

(defn excerpt-around [text query max-chars]
  (let [ltext (str/lower-case text)
        lquery (str/lower-case query)
        idx (.indexOf ltext lquery)]
    (if (neg? idx)
      (subs text 0 (min max-chars (count text)))
      (let [start (max 0 (- idx 40))
            end (min (count text) (+ idx (count query) 40))]
        (str (when (pos? start) "…")
             (subs text start end)
             (when (< end (count text)) "…"))))))

(defn session->item [s query cur-hash]
  (let [cur? (= (:ws-hash s) cur-hash)
        icon (if cur? "$(history)" "$(remote-explorer)")
        title (session-display-title s)
        lbl (str icon " " title)
        time-str (str (:turn-count s) " turns · " (time-ago (:last-active s)))
        desc (if cur? "This workspace" (:ws-label s))
        excerpt (when (seq query) (excerpt-around (:content s) query 120))
        detail (if excerpt (str time-str " · " excerpt) time-str)
        match-start (when excerpt
                      (let [idx (.indexOf (str/lower-case detail) (str/lower-case query))]
                        (when (>= idx 0) idx)))
        item #js {:label lbl
                  :description desc
                  :detail detail
                  :alwaysShow true
                  :session_id (:id s)
                  :ws_hash (:ws-hash s)}]
    (when match-start
      (aset item "detailHighlights"
            #js [#js {:start match-start :end (+ match-start (count query))}]))
    item))

(defn open-cross-workspace [ws-hash]
  (let [wj (path/join storage-root ws-hash "workspace.json")
        data (js/JSON.parse (str (fs/readFileSync wj "utf8")))
        p (or (.-workspace data) (.-folder data))
        decoded (-> p js/decodeURIComponent (.replace "file://" ""))
        uri (vscode/Uri.parse (str "file://" decoded))]
    (if (.-workspace data)
      (vscode/commands.executeCommand "vscode.openWorkspace" uri)
      (vscode/commands.executeCommand "vscode.openFolder" uri #js {:forceNewWindow true}))))

(defn show-session-search []
  (let [cur-hash (find-current-ws-hash)
        all-sessions (scan-all-sessions cur-hash)
        qp (vscode/window.createQuickPick)]
    (set! (.-placeholder qp) "Search all chat sessions...")
    (set! (.-items qp) (into-array (mapv #(session->item % "" cur-hash) all-sessions)))
    (.onDidChangeValue qp
      (fn [q]
        (let [filtered (if (seq q)
                         (filter #(str/includes? (:search-text %) (str/lower-case q))
                                 all-sessions)
                         all-sessions)]
          (set! (.-items qp) (into-array (mapv #(session->item % q cur-hash) filtered))))))
    (.onDidAccept qp
      (fn []
        (when-let [sel (first (.-selectedItems qp))]
          (.hide qp)
          (if (= (.-ws_hash sel) cur-hash)
            (let [b64 (.toString (js/Buffer.from (.-session_id sel)) "base64")
                  uri (vscode/Uri.parse (str "vscode-chat-session://local/" b64))]
              (vscode/commands.executeCommand "vscode.open" uri))
            (open-cross-workspace (.-ws_hash sel))))))
    (.onDidHide qp #(.dispose qp))
    (.show qp)))

#_(show-session-search)


(comment
  ;; == JSONL-based session search (workspace-scoped, EDN-cached) ==
  ;; Phase 1: index-sessions! — parse JSONL files, write EDN cache
  ;; Phase 2: show-session-search — read EDN, QuickPick with content search

  ;; -- Dependencies --
  (require '["vscode" :as vscode]
           '["fs" :as fs]
           '["path" :as path]
           '["os" :as os]
           '[clojure.string :as str]
           '[clojure.edn :as edn])

  ;; -- find-ws-hash: works for multi-root + single-folder workspaces --
  (defn find-ws-hash []
    (let [storage-root (path/join (os/homedir)
                                  "Library/Application Support/Code/User/workspaceStorage")
          ws-file (some-> vscode/workspace.workspaceFile .-fsPath)
          ws-folder (when-not ws-file
                      (some-> vscode/workspace.workspaceFolders
                              (aget 0) .-uri .-fsPath))]
      (when (or ws-file ws-folder)
        (->> (fs/readdirSync storage-root)
             (some (fn [hash]
                     (let [wj (path/join storage-root hash "workspace.json")]
                       (when (fs/existsSync wj)
                         (try
                           (let [data (js/JSON.parse (str (fs/readFileSync wj "utf8")))
                                 ws-val (.-workspace data)
                                 fld-val (.-folder data)
                                 decode #(some-> % js/decodeURIComponent (.replace "file://" ""))]
                             (cond
                               (and ws-file ws-val)
                               (when (= (decode ws-val) ws-file) hash)

                               (and ws-folder fld-val)
                               (when (= (decode fld-val) ws-folder) hash)))
                           (catch js/Error _ nil))))))))))

  ;; -- parse-session-jsonl: extract all content from a JSONL session file --
  (defn parse-session-jsonl [fp]
    (let [content (fs/readFileSync fp "utf8")
          lines (.split content "\n")
          first-line (js/JSON.parse (first lines))
          base (.-v first-line)
          sid (.-sessionId base)
          reqs (.-requests base)
          base-req (when (and reqs (pos? (.-length reqs))) (aget reqs 0))
          user-msgs (atom (if base-req
                            [(some-> base-req .-message .-text)]
                            []))
          resp-texts (atom [])
          title (atom (or (.-customTitle base) nil))
          last-ts (atom (or (.-lastMessageDate base)
                            (some-> base-req .-timestamp)
                            (-> (fs/statSync fp) .-mtimeMs)))]
      (when-let [resp (some-> base-req .-response)]
        (when (js/Array.isArray resp)
          (doseq [i (range (.-length resp))]
            (let [item (aget resp i)]
              (when (and (nil? (.-kind item)) (.-value item))
                (swap! resp-texts conj (.-value item)))))))
      (doseq [line (rest lines)
              :when (seq line)]
        (let [p (js/JSON.parse line)
              k (.-k p)
              kind (.-kind p)
              v (.-v p)]
          (when (and (= kind 2) k (= (.-length k) 1) (= (aget k 0) "requests")
                     (js/Array.isArray v))
            (doseq [i (range (.-length v))]
              (let [req (aget v i)]
                (when-let [msg (some-> req .-message .-text)]
                  (swap! user-msgs conj msg))
                (when-let [ts (.-timestamp req)]
                  (swap! last-ts max ts)))))
          (when (and (= kind 2) k (= (.-length k) 3)
                     (= (aget k 0) "requests") (= (aget k 2) "response")
                     (js/Array.isArray v))
            (doseq [i (range (.-length v))]
              (let [item (aget v i)]
                (when (and (nil? (.-kind item)) (.-value item))
                  (swap! resp-texts conj (.-value item))))))
          (when (and (= kind 1) k (= (.-length k) 1) (= (aget k 0) "generatedTitle"))
            (reset! title v))
          (when (and (= kind 1) k (= (.-length k) 1) (= (aget k 0) "customTitle"))
            (reset! title v))))
      {:id sid
       :title (or @title "")
       :user-messages (filterv some? @user-msgs)
       :response-texts @resp-texts
       :last-active @last-ts}))

  ;; -- do-index: parse all JSONL files, write EDN --
  (defn do-index [ws-hash]
    (let [sd (path/join (os/homedir)
                        "Library/Application Support/Code/User/workspaceStorage"
                        ws-hash "chatSessions")]
      (when (fs/existsSync sd)
        (let [files (filterv #(.endsWith % ".jsonl") (fs/readdirSync sd))
              sessions (vec (keep (fn [f]
                                    (try
                                      (parse-session-jsonl (path/join sd f))
                                      (catch js/Error _e nil)))
                                  files))
              edn-path (path/join sd ".session-index.edn")]
          (fs/writeFileSync edn-path (pr-str sessions) "utf8")
          sessions))))

  ;; -- index-sessions!: index with progress dialog --
  (defn index-sessions! []
    (let [ws-hash (find-ws-hash)]
      (if-not ws-hash
        (vscode/window.showWarningMessage "Could not determine workspace hash")
        (vscode/window.withProgress
         #js {:location vscode/ProgressLocation.Notification
              :title "Indexing chat sessions..."
              :cancellable false}
         (fn [progress _token]
           (js/Promise.
            (fn [resolve _reject]
              (.report progress #js {:message "Parsing JSONL files..."})
              (js/setTimeout
               (fn []
                 (let [sessions (do-index ws-hash)]
                   (if sessions
                     (do (.report progress #js {:increment 100 :message "Done!"})
                         (-> (vscode/window.showInformationMessage
                              (str "Indexed " (count sessions) " sessions"))
                             (.then (fn [_] (resolve sessions)))))
                     (do (vscode/window.showWarningMessage "No chat sessions found")
                         (resolve nil)))))
               0))))))))

  ;; -- Helpers --
  (defn read-index []
    (let [ws-hash (find-ws-hash)
          edn-path (path/join (os/homedir)
                              "Library/Application Support/Code/User/workspaceStorage"
                              ws-hash "chatSessions" ".session-index.edn")]
      (when (fs/existsSync edn-path)
        (edn/read-string (fs/readFileSync edn-path "utf8")))))

  (defn make-searchable [sessions]
    (mapv (fn [s]
            (assoc s :search-text
                   (str/lower-case
                    (str (:title s) " "
                         (str/join " " (:user-messages s)) " "
                         (str/join " " (:response-texts s))))))
          sessions))

  (defn time-ago [ms]
    (let [diff (- (js/Date.now) ms)
          min-f (/ diff 60000)
          hr-f (/ min-f 60)
          day-f (/ hr-f 24)]
      (cond
        (< min-f 2) "just now"
        (< min-f 60) (str (.round js/Math min-f) "m ago")
        (< hr-f 24) (str (.round js/Math hr-f) "h ago")
        :else (str (.round js/Math day-f) "d ago"))))

  (defn excerpt-around [text query max-chars]
    (let [ltext (str/lower-case text)
          lquery (str/lower-case query)
          idx (.indexOf ltext lquery)]
      (if (neg? idx)
        (subs text 0 (min max-chars (count text)))
        (let [start (max 0 (- idx 40))
              end (min (count text) (+ idx (count query) 40))]
          (str (when (pos? start) "…")
               (subs text start end)
               (when (< end (count text)) "…"))))))

  (defn session-display-title [s]
    (if (seq (:title s))
      (:title s)
      (let [first-msg (first (:user-messages s))]
        (if (and first-msg (seq first-msg))
          (str (subs first-msg 0 (min 60 (count first-msg))) "…")
          (:id s)))))

  (defn session->item [s query]
    (let [title (session-display-title s)
          turn-count (count (:user-messages s))
          lbl (str "$(history) " title)
          time-str (str turn-count " turns · " (time-ago (:last-active s)))
          all-text (str (str/join " | " (:user-messages s))
                        " | "
                        (str/join " | " (:response-texts s)))
          excerpt (when (seq query) (excerpt-around all-text query 120))
          detail (if excerpt (str time-str " · " excerpt) time-str)]
      #js {:label lbl
           :description ""
           :detail detail
           :alwaysShow true
           :session_id (:id s)}))

  ;; -- show-session-search: QuickPick with content filtering --
  (defn show-session-search []
    (let [index (read-index)]
      (if-not index
        (-> (vscode/window.showWarningMessage
             "No session index found. Index first?" "Index Now")
            (.then (fn [choice]
                     (when (= choice "Index Now")
                       (-> (index-sessions!)
                           (.then (fn [_] (show-session-search))))))))
        (let [sessions (make-searchable
                        (sort-by #(- (:last-active %)) index))
              qp (vscode/window.createQuickPick)]
          (set! (.-placeholder qp) "Search chat session content...")
          (set! (.-items qp)
                (into-array (mapv #(session->item % "") sessions)))
          (.onDidChangeValue qp
            (fn [q]
              (let [lq (str/lower-case q)
                    filtered (if (seq q)
                               (filterv #(str/includes? (:search-text %) lq)
                                        sessions)
                               sessions)]
                (set! (.-items qp)
                      (into-array (mapv #(session->item % q) filtered))))))
          (.onDidAccept qp
            (fn []
              (when-let [sel (first (.-selectedItems qp))]
                (.hide qp)
                (let [b64 (.toString (js/Buffer.from (.-session_id sel)) "base64")
                      uri (vscode/Uri.parse
                           (str "vscode-chat-session://local/" b64))]
                  (vscode/commands.executeCommand "vscode.open" uri)))))
          (.onDidHide qp #(.dispose qp))
          (.show qp)))))

  ;; -- Try it --
  (index-sessions!)
  (show-session-search)

  :rcf)
