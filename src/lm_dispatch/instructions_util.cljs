;; AGENTS, please read this preamble before working with the namespace:
;; - Use interactive programming
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

(comment
  (require '[promesa.core :as p])

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
    (println "User files:" files))

  ;; Test building descriptions map
  (p/let [descriptions (build-file-descriptions-map!+ (user-data-instructions-path))]
    (def user-descriptions descriptions)
    (cljs.pprint/pprint descriptions))

  :rcf)
