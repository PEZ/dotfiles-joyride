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
  "Returns absolute path to global user data instructions directory,
   optionally appending `relative-path`."
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
  "Returns absolute path to workspace instructions directory,
   optionally appending `relative-path`, or throws if no workspace is available."
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
  "Returns domain string extracted from instruction `filename`,
   or `nil` for reserved words like 'memory'.

   Pattern: {domain}-{suffix}.instructions.md or {domain}.instructions.md
   Takes everything before the last component before .instructions.md

   Examples:
     'clojure-memory.instructions.md' → 'clojure'
     'shadow-cljs-memory.instructions.md' → 'shadow-cljs'
     'joyride.instructions.md' → 'joyride'
     'memory.instructions.md' → nil (reserved word)"
  [filename]
  (when-let [[_ domain-part] (re-find #"^(.+?)(?:-[^-]+)?\.instructions\.md$" filename)]
    (when-not (= domain-part "memory")
      domain-part)))

(defn- resolve-file-uri
  "Returns vscode/Uri object for `file-path` (absolute or relative),
   or throws if relative path provided without workspace."
  [file-path]
  (if (path/isAbsolute file-path)
    (vscode/Uri.file file-path)
    (let [workspace-root (some-> vscode/workspace.workspaceFolders
                                 first
                                 .-uri)]
      (if workspace-root
        (vscode/Uri.joinPath workspace-root file-path)
        (throw (js/Error. (str "Cannot resolve relative path without workspace: " file-path)))))))

(defn list-instruction-files!+
  "Returns promise of vector of .instructions.md filenames in `dir-path`."
  [dir-path]
  (p/catch
   (p/let [uri (resolve-file-uri dir-path)
           files (vscode/workspace.fs.readDirectory uri)]
     (->> files
          (filter #(string/ends-with? (first %) ".instructions.md"))
          (map first)
          vec))
   (fn [_error]
     [])))

(defn read-file-content!+
  "Returns promise of file content string from `file-path`,
   or `nil` if file doesn't exist."
  [file-path]
  (p/catch
   (p/let [uri (resolve-file-uri file-path)
           content-bytes (vscode/workspace.fs.readFile uri)
           decoder (js/TextDecoder. "utf-8")]
     (.decode decoder content-bytes))
   (fn [_error]
     nil)))

(defn extract-description-from-content
  "Returns description string from `content` frontmatter,
   or `nil` if not found."
  [content]
  (when content
    (second (re-find #"description:\s*'([^']*)'" content))))

(defn format-instruction-file
  "Returns `content` wrapped in XML-ish <attachment> markup with `file-path`."
  [file-path content]
  (str "<attachment filePath=\"" file-path "\">\n"
       content "\n"
       "</attachment>"))

(defn enrich-editor-context!+
  "Returns promise of enriched `editor-context` map with file content from VS Code,
   or `nil` if no `:editor-context/file-path` provided.

   Takes flat keys `:editor-context/file-path` (absolute path, required),
   `:editor-context/selection-start-line`, and `:editor-context/selection-end-line`
   (both 0-indexed, optional)."
  [editor-context]
  (when-let [file-path (:editor-context/file-path editor-context)]
    (p/let [uri (vscode/Uri.file file-path)
            doc (vscode/workspace.openTextDocument uri)
            full-content (.getText doc)
            selection-start (:editor-context/selection-start-line editor-context)
            selection-end (:editor-context/selection-end-line editor-context)
            selected-text (when (and selection-start selection-end)
                            (let [start-pos (vscode/Position. selection-start 0)
                                  end-line selection-end
                                  end-char (.-length (.lineAt doc end-line))
                                  end-pos (vscode/Position. end-line end-char)
                                  range (vscode/Range. start-pos end-pos)]
                              (.getText doc range)))]
      {:editor-context/file-path file-path
       :editor-context/selection-start-line selection-start
       :editor-context/selection-end-line selection-end
       :editor-context/selected-text selected-text
       :editor-context/full-file-content full-content})))

(defn build-file-descriptions-map!+
  "Returns promise of vector of maps with `:file`, `:description`, and `:domain`
   keys for all instruction files in `search-dir`."
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
  "Returns promise of concatenated string with each file from `file-paths`
   wrapped in <attachment> XML-ish tags."
  [file-paths]
  (if (empty? file-paths)
    (p/resolved "")
    (p/let [attachments (p/all (for [file-path file-paths]
                                 (p/let [content (read-file-content!+ file-path)]
                                   (when content
                                     (format-instruction-file file-path content)))))]
      (string/join "\n" (filter some? attachments)))))

(defn collect-all-instruction-descriptions!+
  "Returns promise of vector of maps with `:file`, `:filename`, `:description`,
   and `:domain` keys from both workspace and global instruction areas."
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
  "Returns promise of concatenated instructions string with <attachment> wrappers,
   combining `:agent.conversation/instructions-paths` and
   `:agent.conversation/context-files` from `conversation-data`.

   Designed to be called after select-instructions!+ or when paths are known."
  [{:agent.conversation/keys [instructions-paths context-files]}]
  (p/let [;; Slurp selected instruction files (wrapped in <attachment> tags)
          selected-content (concatenate-instruction-files!+ (or instructions-paths []))

          ;; Slurp context files (wrapped in <attachment> tags)
          context-content (concatenate-instruction-files!+ (or context-files []))

          ;; Concatenate all <attachment> blocks
          final-instructions (cond
                               (and (seq selected-content) (seq context-content))
                               (str selected-content "\n" context-content)

                               (seq context-content)
                               context-content

                               :else
                               selected-content)]
    final-instructions))

(defn format-editor-context
  "Returns formatted string with XML-ish markup matching Copilot's pattern,
   or empty string if no context in `editor-context`.

   Uses `:editor-context/file-path`, `:editor-context/selection-start-line`,
   `:editor-context/selection-end-line`, `:editor-context/selected-text`,
   and `:editor-context/full-file-content`."
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
  "Returns promise of assembled instructions string combining `instructions`
   (string or vector of file paths), `context-file-paths`, and `editor-context`.

   Assembly order matches Copilot's pattern:
   1. Instructions (string or <attachment> wrapped file paths)
   2. Context files (each wrapped in <attachment>)
   3. Editor context (enriched internally from VS Code)

   All instruction and context files wrapped in <attachment filePath=...> XML-ish markup."
  [instructions editor-context context-file-paths]
  (p/let [;; Filter out editor context file from context-file-paths to avoid duplication
          editor-file-path (:editor-context/file-path editor-context)
          filtered-context-paths (vec (remove #(= % editor-file-path) (or context-file-paths [])))

          ;; Handle instructions based on type
          instructions-content (cond
                                 (string? instructions)
                                 (p/resolved instructions)

                                 (vector? instructions)
                                 (concatenate-instruction-files!+ instructions)

                                 :else
                                 (p/resolved ""))

          ;; Process context files - each wrapped in <attachment>
          context-content (concatenate-instruction-files!+ filtered-context-paths)

          ;; Enrich and format editor context if provided - comes last like Copilot
          enriched-editor-context (when editor-file-path
                                    (enrich-editor-context!+ editor-context))
          editor-context-content (or (format-editor-context enriched-editor-context) "")

          ;; Concatenate all parts - simple newline separation between <attachment> blocks
          parts (filter seq [instructions-content context-content editor-context-content])
          final-instructions (string/join "\n" parts)]
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

  ;; Test building descriptions map
  (p/let [descriptions (build-file-descriptions-map!+ (user-data-instructions-path))]
    (def user-descriptions descriptions)
    (count descriptions))

  ;; Test collecting all descriptions
  (p/let [descriptions (collect-all-instruction-descriptions!+)]
    (def all-descriptions descriptions)
    (count descriptions))

  ;; Test assembling instructions - string input
  (p/let [result (assemble-instructions!+ "Go, go, go!" nil nil)]
    result)

  ;; Test assembling instructions - vector input
  (p/let [files ["src/lm_dispatch/rcf-dummy-files/dummy.instructions.md"]
          result (assemble-instructions!+ files nil nil)]
    result)

  ;; Test assembling with context files
  (p/let [instructions "Custom instructions"
          context-files ["src/lm_dispatch/rcf-dummy-files/sample_code.foobar"]
          result (assemble-instructions!+ instructions nil context-files)]
    result)

  ;; Test assembling vector + context
  (p/let [instructions ["src/lm_dispatch/rcf-dummy-files/dummy.instructions.md"]
          context-files ["src/lm_dispatch/rcf-dummy-files/dummy-memory.instructions.md"]
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

  ;; Test complete assembly with all pieces - see the full output format
  (p/let [instructions ["src/lm_dispatch/rcf-dummy-files/dummy.instructions.md"]
          context-files ["src/lm_dispatch/rcf-dummy-files/sample_code.foobar"]
          editor-ctx {:editor-context/file-path "/Users/pez/.config/joyride/src/lm_dispatch/rcf-dummy-files/sample_code.foobar"
                      :editor-context/selection-start-line 5
                      :editor-context/selection-end-line 7
                      :editor-context/selected-text "import frobulator  // Line 5 - selection start\nimport bazinator   // Line 6\nimport widgetizer  // Line 7 - selecttion end"
                      :editor-context/full-file-content "// Sample FooBar code file for testing... Full file content here..."}
          result (assemble-instructions!+ instructions editor-ctx context-files)]
    (def assembled-output result)
    (println "\n=== ASSEMBLED OUTPUT ===\n")
    (println result)
    result)

  ;; Inspect the assembled output
  assembled-output

  :rcf)