;; AGENTS, please read this preamble before working with the namespace:
;; - Use interactive programming
;; - Work using TDD in the repl
#_(do (require 'run-all-tests :reload) (run-all-tests/run!+))
;; - Always prefer your structural editing tools

(ns lm-dispatch.instructions-util
  "Utilities for working with instruction files"
  (:require
   ["path" :as path]
   ["vscode" :as vscode]
   [clojure.string :as string]
   [joyride.core :as joy]
   [promesa.core :as p]))

(defn user-data-instructions-path
  "Get path to global user data instructions directory.

  Args:
    relative-path - Optional relative path to append

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
  "Get path to workspace instructions directory.

  Args:
    relative-path - Optional relative path to append

  Returns: Absolute path string or throws if no workspace"
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

(defn extract-domain-from-filename
  "Extract domain from instruction filename.

  Pattern: {domain}-{suffix}.instructions.md or {domain}.instructions.md
  Takes everything before the last component before .instructions.md

  Examples:
    'clojure-memory.instructions.md' → 'clojure'
    'shadow-cljs-memory.instructions.md' → 'shadow-cljs'
    'joyride.instructions.md' → 'joyride'
    'memory.instructions.md' → nil (reserved word)

  Args:
    filename - Filename string

  Returns: Domain string or nil"
  [filename]
  (when-let [[_ domain-part] (re-find #"^(.+?)(?:-[^-]+)?\.instructions\.md$" filename)]
    (when-not (= domain-part "memory")
      domain-part)))

(defn list-instruction-files!+
  "List all .instructions.md files in the target directory.

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

(defn read-file-content!+
  "Read file content from disk.

  Args:
    file-path - Absolute path to file

  Returns: Promise of file content string, or nil if file doesn't exist"
  [file-path]
  (p/catch
   (p/let [uri (vscode/Uri.file file-path)
           content-bytes (vscode/workspace.fs.readFile uri)
           decoder (js/TextDecoder. "utf-8")]
     (.decode decoder content-bytes))
   (fn [_error]
     nil)))

(defn extract-description-from-content
  "Extract description from file content frontmatter.

  Args:
    content - File content string

  Returns: Description string or nil if not found"
  [content]
  (when content
    (second (re-find #"description:\s*'([^']*)'" content))))

(defn build-file-descriptions-map!+
  "Build a map of file descriptions from instruction files with domain info.

  Args:
    dir-path - Absolute path to directory

  Returns: Promise of vector of {:file string :description string :domain string} maps"
  [search-dir]
  (p/let [files (list-instruction-files!+ search-dir)
          file-data (p/all
                     (for [filename files]
                       (p/let [file-path (path/join search-dir filename)
                               content (read-file-content!+ file-path)
                               description (extract-description-from-content content)
                               domain (extract-domain-from-filename filename)]
                         {:file file-path
                          :filename filename
                          :description description
                          :domain domain})))]
    (vec file-data)))

(defn concatenate-instruction-files!+
  "Slurp instruction files and concatenate with separators.

  Args:
    file-paths - Vector of absolute file paths

  Returns: Promise of concatenated string with '# From: filename' separators"
  [file-paths]
  (if (empty? file-paths)
    (p/resolved "")
    (p/let [contents (p/all (for [file-path file-paths]
                              (p/let [content (read-file-content!+ file-path)
                                      filename (path/basename file-path)]
                                {:filename filename
                                 :content (or content "")})))]
      (string/join
       "\n\n"
       (for [{:keys [filename content]} contents]
         (str "# From: " filename "\n\n" content))))))

(defn collect-all-instruction-descriptions!+
  "Collect instruction file descriptions from both workspace and global areas.

  Returns: Promise of vector of {:file :filename :description :domain} maps"
  []
  (p/let [;; Try workspace instructions (might not exist)
          workspace-descriptions (p/catch
                                  (p/let [ws-path (workspace-instructions-path)]
                                    (build-file-descriptions-map!+ ws-path))
                                  (fn [_] []))
          ;; Get global user instructions
          user-descriptions (build-file-descriptions-map!+
                             (user-data-instructions-path))]
    ;; Combine both, workspace first
    (vec (concat workspace-descriptions user-descriptions))))

(defn prepare-instructions-from-selected-paths!+
  "Concatenate selected instruction files with context files.

  This function assumes instruction selection has already happened upstream.
  Designed to be called after select-instructions!+ or when paths are known.

  Args:
    conversation-data - Map with namespaced keys:
      :agent.conversation/selected-paths - Vector of selected instruction file paths
      :agent.conversation/context-files - Vector of context file paths

  Returns: Promise of concatenated instructions string"
  [{:agent.conversation/keys [instructions-paths context-files]}]
  (p/let [;; Slurp selected instruction files
          selected-content (concatenate-instruction-files!+ (or instructions-paths []))

          ;; Slurp context files
          context-content (concatenate-instruction-files!+ (or context-files []))

          ;; Concatenate with separator if both exist
          final-instructions (cond
                               (and (seq selected-content) (seq context-content))
                               (str selected-content
                                    "\n\n"
                                    "# === Context Files ===\n\n"
                                    context-content)

                               (seq context-content)
                               context-content

                               :else
                               selected-content)]
    final-instructions))

(comment
  ;; Test path utilities
  (user-data-instructions-path)
  (workspace-instructions-path)

  ;; Test domain extraction
  (extract-domain-from-filename "clojure-memory.instructions.md")
  (extract-domain-from-filename "joyride.instructions.md")
  (extract-domain-from-filename "memory.instructions.md")

  ;; Test file listing
  (p/let [files (list-instruction-files!+ (user-data-instructions-path))]
    (def user-files files)
    (count files))

  user-files

  ;; Test building descriptions map
  (p/let [descriptions (build-file-descriptions-map!+ (user-data-instructions-path))]
    (def user-descriptions descriptions)
    (count descriptions))

  user-descriptions

  ;; Test collecting all descriptions
  (p/let [descriptions (collect-all-instruction-descriptions!+)]
    (def all-descriptions descriptions)
    (count descriptions))

  all-descriptions

  ;; Test assembling instructions - string input
  (p/let [result (assemble-instructions!+ "Go, go, go!" nil nil)]
    result)

  ;; Test assembling instructions - vector input
  (p/let [files [(user-data-instructions-path "clojure.instructions.md")]
          result (assemble-instructions!+ files nil nil)]
    result)

  ;; Test assembling with context files
  (p/let [instructions "Custom instructions"
          context-files [(user-data-instructions-path "memory.instructions.md")]
          result (assemble-instructions!+ instructions nil context-files)]
    result)

  ;; Test assembling vector + context
  (p/let [instructions [(user-data-instructions-path "clojure.instructions.md")]
          context-files [(user-data-instructions-path "clojure-memory.instructions.md")]
          result (assemble-instructions!+ instructions nil context-files)]
    result)

  ;; Test with editor context
  (p/let [editor-ctx {:editor-context/file-path "/Users/pez/test.cljs"
                      :editor-context/selection-start-line 10
                      :editor-context/selection-end-line 15
                      :editor-context/selected-text "(def foo 42)"
                      :editor-context/full-file-content "(ns test)\n(def foo 42)"}
          result (assemble-instructions!+ "Custom goal" editor-ctx nil)]
    result)

  :rcf)

(defn format-editor-context
  "Format editor context into XML-ish markup matching Copilot's pattern.

  Args:
    editor-context - Map with keys:
      :editor-context/file-path - Absolute path to file
      :editor-context/selection-start-line - 0-indexed start line
      :editor-context/selection-end-line - 0-indexed end line
      :editor-context/selected-text - The selected text (if any)
      :editor-context/full-file-content - Complete file content

  Returns: Formatted string with XML-ish markup, or empty string if no context"
  [{:editor-context/keys [file-path selection-start-line selection-end-line
                          selected-text full-file-content]}]
  (when (and file-path full-file-content)
    (let [filename (path/basename file-path)
          has-selection? (and selected-text
                              (not (string/blank? selected-text))
                              selection-start-line
                              selection-end-line)]
      (str
       "<editorContext>\n"
       "The user's current file is " file-path ". "
       (when has-selection?
         (str "The current selection is from line " selection-start-line
              " to line " selection-end-line "."))
       "\n</editorContext>\n\n"

       (when has-selection?
         (str "<attachment id=\"file:" filename "\">\n"
              "User's active selection:\n"
              "Excerpt from " filename ", lines " selection-start-line
              " to " selection-end-line ":\n"
              "```clojure\n"
              selected-text "\n"
              "```\n"
              "</attachment>\n\n"))

       "<attachment filePath=\"" file-path "\">\n"
       "User's active file for additional context:\n"
       full-file-content "\n"
       "</attachment>"))))

(defn assemble-instructions!+
  "Assemble instructions from string or vector, with optional editor context and context files.

  The assembly order is:
  1. Instructions (string or concatenated file paths)
  2. Editor context (if provided)
  3. Context files (if provided)

  Args:
    instructions - Either a string or vector of file paths
    editor-context - Optional map with editor state (see format-editor-context for structure)
    context-file-paths - Optional vector of context file paths

  Returns: Promise of assembled instructions string"
  [instructions editor-context context-file-paths]
  (p/let [;; Handle instructions based on type
          instructions-content (cond
                                 (string? instructions)
                                 (p/resolved instructions)

                                 (vector? instructions)
                                 (concatenate-instruction-files!+ instructions)

                                 :else
                                 (p/resolved ""))

          ;; Format editor context if provided
          editor-context-content (or (format-editor-context editor-context) "")

          ;; Always process context files
          context-content (concatenate-instruction-files!+ (or context-file-paths []))

          ;; Concatenate all parts with separators
          final-instructions (str
                              instructions-content
                              (when (seq editor-context-content)
                                (str "\n\n"
                                     "# === Editor Context ===\n\n"
                                     editor-context-content))
                              (when (seq context-content)
                                (str "\n\n"
                                     "# === Context Files ===\n\n"
                                     context-content)))]
    final-instructions))
