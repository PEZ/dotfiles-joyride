(ns agents.agent-util
  "Shared utilities for agent implementations"
  (:require
   ["path" :as path]
   ["vscode" :as vscode]
   [cljs.pprint]
   [clojure.string :as string]
   [joyride.core :as joy]
   [promesa.core :as p]))

;; Path helpers

(defn user-data-instructions-path
  "Returns absolute path to user-level instructions directory,
   optionally appending `relative-path` to prompts directory."
  ([] (user-data-instructions-path nil))
  ([relative-path]
   (let [global-storage-path (-> (joy/extension-context)
                                 .-globalStorageUri
                                 .-fsPath)
         user-path (path/join global-storage-path ".." "..")]
     (if relative-path
       (path/join user-path "prompts" relative-path)
       (path/join user-path "prompts")))))

(defn workspace-instructions-path
  "Returns absolute path to workspace-level instructions directory,
   optionally appending `relative-path` to .github/instructions directory,
   or throws if no workspace is available."
  ([] (workspace-instructions-path nil))
  ([relative-path]
   (let [workspace-path (some-> vscode/workspace.workspaceFolders
                                first
                                .-uri
                                .-fsPath)]
     (if workspace-path
       (if relative-path
         (path/join workspace-path ".github" "instructions" relative-path)
         (path/join workspace-path ".github" "instructions"))
       (throw (js/Error. "No workspace available"))))))

;; File operations

(defn read-existing-file!+
  "Returns promise of file content string from `file-path`,
   or `nil` if file doesn't exist."
  [file-path]
  (p/catch
   (p/let [uri (vscode/Uri.file file-path)
           content-bytes (vscode/workspace.fs.readFile uri)
           decoder (js/TextDecoder.)]
     (.decode decoder content-bytes))
   (fn [_error]
     nil)))

(defn list-instruction-files!+
  "Returns promise of vector of .instructions.md filenames in `dir-path`."
  [dir-path]
  (p/catch
   (p/let [uri (vscode/Uri.file dir-path)
           files (vscode/workspace.fs.readDirectory uri)]
     (->> files
          (filter #(string/ends-with? (first %) ".instructions.md"))
          (map first)
          vec))
   (fn [_error]
     [])))

(defn extract-description-from-content
  "Returns description string from YAML frontmatter in `content`,
   or `nil` if not found."
  [content]
  (when content
    (second (re-find #"description:\s*'([^']*)'" content))))

(defn build-file-descriptions-map!+
  "Returns promise of vector of maps with `:file` and `:description` keys
   for instruction files in `search-dir`. For workspace scope, includes
   `copilot-instructions.md` from parent `.github/` directory."
  [search-dir]
  (p/let [files (list-instruction-files!+ search-dir)
          file-data (p/all
                     (for [filename files]
                       (p/let [file-path (path/join search-dir filename)
                               content (read-existing-file!+ file-path)
                               description (extract-description-from-content content)]
                         {:file file-path
                          :description description})))

          ;; Special case: include copilot-instructions.md from parent .github/ dir
          is-workspace? (string/includes? search-dir ".github/instructions")
          copilot-data (when is-workspace?
                         (p/let [parent-dir (path/dirname search-dir)
                                 copilot-path (path/join parent-dir "copilot-instructions.md")
                                 content (read-existing-file!+ copilot-path)
                                 description (when content
                                               (extract-description-from-content content))]
                           (when content
                             {:file copilot-path
                              :description description})))]

    ;; Combine results, filtering out nil copilot-data if not found
    (vec (filter some? (concat file-data [copilot-data])))))

(defn format-description-listing
  "Returns formatted text listing for `descriptions` (vector of maps),
   or empty string if no descriptions."
  [descriptions]
  (when (seq descriptions)
    (str "```clojure\n"
         (with-out-str (cljs.pprint/pprint descriptions))
         "\n```\n")))

;; Utilities

(defn normalize-scope
  "Returns keyword (`:workspace` or `:global`) from `scope` input.

   Accepts keywords (`:workspace`, `:global`), strings (\"workspace\", \"ws\",
   \"global\", \"user\"), or defaults to `:global` for nil/other values."
  [scope]
  (cond
    (keyword? scope) scope
    (= scope "workspace") :workspace
    (= scope "ws") :workspace
    (= scope "global") :global
    (= scope "user") :global
    :else :global))

(defn file-path->uri-string
  "Returns URI string for `file-path` (filesystem path or URI string)."
  [file-path]
  (if (string/starts-with? file-path "file://")
    file-path
    (.toString (vscode/Uri.file file-path))))

;; Agent result extraction

(defn find-message-with-marker
  "Returns first message content (searching backwards in `agent-messages`) containing `marker`,
   or `nil` if not found."
  [agent-messages marker]
  (some (fn [msg]
          (let [content (:content msg)]
            (when (and content (string/includes? content marker))
              content)))
        (reverse agent-messages)))

(defn extract-marked-content
  "Returns map with `:content` (extracted string between `begin-marker` and
   `end-marker` from `agent-result` history), or `:extraction-failed` with
   `:debug-info` if markers not found.

   Searches agent messages backwards for content wrapped in markers.
   Reusable pattern for extracting structured output (EDN, reports, etc.)
   from autonomous agent conversations."
  [agent-result begin-marker end-marker]
  (let [all-messages (get agent-result :history [])
        agent-messages (filter #(= :assistant (:role %)) all-messages)

        ;; Find message containing end marker (searching backwards)
        message-with-content (find-message-with-marker agent-messages end-marker)

        ;; Extract content between markers
        pattern (re-pattern (str "(?s)" begin-marker "\\s*(.*?)\\s*" end-marker))
        match (when message-with-content (re-find pattern message-with-content))
        extracted (when match (string/trim (second match)))]

    (if extracted
      {:content extracted}
      {:extraction-failed true
       :debug-info {:total-messages (count all-messages)
                    :assistant-messages (count agent-messages)
                    :has-end-marker? (boolean message-with-content)
                    :has-begin-marker? (when message-with-content
                                         (string/includes? message-with-content begin-marker))}})))
