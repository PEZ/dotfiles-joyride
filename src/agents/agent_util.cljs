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
  "Get path to user-level instructions directory or file.

  Args:
    relative-path - Optional path relative to prompts directory

  Returns: Absolute path string"
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
  "Get path to workspace-level instructions directory or file.

  Args:
    relative-path - Optional path relative to .github/instructions directory

  Returns: Absolute path string

  Throws: Error if no workspace is available"
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
  "Read file content or return nil if file doesn't exist.

  Args:
    file-path - Absolute path to file

  Returns: Promise of file content string or nil"
  [file-path]
  (p/catch
   (p/let [uri (vscode/Uri.file file-path)
           content-bytes (vscode/workspace.fs.readFile uri)
           decoder (js/TextDecoder.)]
     (.decode decoder content-bytes))
   (fn [_error]
     nil)))

(defn list-instruction-files!+
  "List all .instructions.md files in directory.

  Args:
    dir-path - Absolute path to directory

  Returns: Promise of vector of filenames"
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
  "Extract description from file frontmatter.

  Args:
    content - File content string with YAML frontmatter

  Returns: Description string or nil"
  [content]
  (when content
    (second (re-find #"description:\s*'([^']*)'" content))))

(defn build-file-descriptions-map!+
  "Build map of file descriptions from instruction files.

  Args:
    search-dir - Absolute path to directory

  Returns: Promise of vector of {:file string :description string} maps"
  [search-dir]
  (p/let [files (list-instruction-files!+ search-dir)
          file-data (p/all
                     (for [filename files]
                       (p/let [file-path (path/join search-dir filename)
                               content (read-existing-file!+ file-path)
                               description (extract-description-from-content content)]
                         {:file file-path
                          :description description})))]
    (vec file-data)))

(defn format-description-listing
  "Format file descriptions into text listing for prompts.

  Args:
    descriptions - Vector of {:file string :description string} maps

  Returns: Formatted string or empty string if no descriptions"
  [descriptions]
  (when (seq descriptions)
    (str "```clojure\n"
         (with-out-str (cljs.pprint/pprint descriptions))
         "\n```\n")))

;; Utilities

(defn normalize-scope
  "Convert scope to keyword, handling both string and keyword input.

  Accepts:
  - Keywords: :workspace, :global
  - Strings: \"workspace\", \"ws\", \"global\", \"user\"
  - nil or anything else defaults to :global

  Returns: :workspace or :global keyword"
  [scope]
  (cond
    (keyword? scope) scope
    (= scope "workspace") :workspace
    (= scope "ws") :workspace
    (= scope "global") :global
    (= scope "user") :global
    :else :global))

(defn file-path->uri-string
  "Convert file path to URI string.

  Handles cases where input is already a URI string.

  Args:
    file-path - Either absolute filesystem path or URI string

  Returns: URI string"
  [file-path]
  (if (string/starts-with? file-path "file://")
    file-path
    (.toString (vscode/Uri.file file-path))))

;; Agent result extraction

(defn find-message-with-marker
  "Find first message (searching backwards) containing marker.
  
  Searches assistant messages in reverse order (most recent first) and returns
  the content of the first message containing the marker string.
  
  Args:
    agent-messages - Sequence of assistant message maps with :content key
    marker - String to search for in message content
    
  Returns:
    Message content string, or nil if not found"
  [agent-messages marker]
  (some (fn [msg]
          (let [content (:content msg)]
            (when (and content (string/includes? content marker))
              content)))
        (reverse agent-messages)))

(defn extract-marked-content
  "Extract content between markers from agent conversation history.

  Searches agent messages backwards for content wrapped in begin/end markers.
  This is a reusable pattern for extracting structured output (EDN, reports, etc.)
  from autonomous agent conversations.

  Args:
    agent-result - Agent result map with :history key
    begin-marker - String marking start of content (e.g., '---BEGIN REPORT---')
    end-marker - String marking end of content (e.g., '---END REPORT---')

  Returns:
    Map with:
    - :content - Extracted content string (if found)
    - :extraction-failed - True if markers not found
    - :debug-info - Minimal debug information (if extraction failed)"
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
