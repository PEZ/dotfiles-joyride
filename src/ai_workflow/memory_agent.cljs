(ns ai-workflow.memory-agent
  "Autonomous memory recording agent using agentic workflow"
  (:require
   [clojure.string :as string]
   [clojure.edn :as edn]
   [promesa.core :as p]
   [joyride.core :as joy]
   [ai-workflow.agents :as agents]
   ["vscode" :as vscode]
   ["path" :as path]))

(def agent-model "grok-code-fast-1")

(defn user-data-instructions-path
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

(comment
  (vscode/Uri.file (user-data-instructions-path "**"))
  (user-data-instructions-path)
  (workspace-instructions-path "**")
  (workspace-instructions-path))

(defn remember-prompt [{:ma/keys [domain]}]
  (str
   "# Memory Recording Agent\n\n"
   "You are an expert prompt engineer and keeper of **"
   (when domain (str "the " domain " "))
   "domain-organized Memory Instructions** that persist across VS Code contexts. You know how to "
   (when-not domain "automatically bin learnings by domain, and ")
   "add to or create new memory files as needed.\n\n"
   "**Critical**: Always use absolute paths when globbing, searching and reading files.\n\n"

   (when domain
     (str "\n\n<DOMAIN>" domain "</DOMAIN>"))

   "\n\n## Session Lesson\n\n<SESSION-LESSON>\n{LESSON}\n</SESSION-LESSON>\n\n"

   "## Your Mission\n\n"
   "Transform the `SESSION-LESSON` into **domain-targeted, succinct, reusable knowledge**, "
   "that helps the AI agent to effectively find the best patterns and avoid common mistakes."

   "## Critical Rules\n\n"
   "- **Search thoroughly** - Use tools to find existing files\n"
   "- **Read before deciding** - Always use tools to read existing files to understand structure\n"
   "- **Integrate carefully** - Place new memories in logical sections\n"
   "- **Use absolute paths** - FILE_PATH must be absolute like `{SEARCH-DIRECTORY}/clojure-memory.instructions.md`\n"
   "- **Be concise** - Memory entries should be scannable and actionable\n"
   "- **Extract patterns** - Generalize from specific instances\n"
   "- Work systematically. Research first, then craft the complete solution.\n\n"

   "## Action steps"

   (if-not domain
     (str "\n\n### 1. Determine the memory domain\n"
          "1. Review the \"Available Memory Files\" section below - it lists all existing memory files with their descriptions.\n"
          "2. Consider if any of these descriptions matches the lesson.\n"
          "   - If so: pick the best match and read that file\n"
          "   - Else: decide on a domain and domain slug for this new memory\n\n"
          "### 2. Read up on existing domain knowledge")
     "\n\n### 1. Read up on existing domain knowledge")

   "\n1. Read these files:\n"
   "   - **General instructions**; `{SEARCH-DIRECTORY}/copilot.instructions.md`\n"
   "   - **General memories**; `{SEARCH-DIRECTORY}/memory.instructions.md`\n"
   (if domain
     (str "   - **Domain instructions**: `{SEARCH-DIRECTORY}/" domain ".instructions.md`\n"
          "   - **Domain memory**; `{SEARCH-DIRECTORY}/" domain "-memory.instructions.md`\n")
     (str "   - **Domain instructions**: `{SEARCH-DIRECTORY}/<domain>.instructions.md`\n"
          "   - **Domain memory**; `{SEARCH-DIRECTORY}/<domain>-memory.instructions.md`\n"))

   "\n   **Critical**: Always use absolute paths when globbing, searching and reading files.\n"
   "3. **Analyze** the specific `SESSION-LESSON` learned from user input, as it fits with your knowledge about the domain.\n"
   "4. **Categorize** the learning:\n"
   "   - New memory\n"
   "   - Enhancement to existing memory\n"
   "5. **Re-author the lesson into a memory**, with focus on the good pattern\n"
   "   - Avoid creating redundancy\n"
   "   - Instead of comprehensive instructions, think about how to capture the lesson in a succinct and clear manner\n"
   "   - **Extract general patterns** from specific instances\n"
   "   - Instead of \"don't\"s, use positive reinforcement focusing on correct patterns\n\n"

   (if domain
     "### 2. Deliver results"
     "### 3. Deliver results")

   "\n\n**IMPORTANT**: Wrap your EDN deliverable in markers:\n\n"
   "```\n---BEGIN RESULTS---\n{your EDN structure here}\n---END RESULTS---\n```\n\n"

   "- IF a memory file already exist:\n"
   "  - Provide a new memory section to append to the file\n"
   "  - Your deliverable is an EDN structure:\n\n"
   "    ```clojure\n"
   "    {:domain                                  ; string\n"
   "     :file-path path                          ; string, absolute path to memory file\n"
   "     :heading memory-heading                  ; string, H2 heading for the new section\n"
   "     :content memory-content-markdown         ; string, the new memory content\n"
   "     :applyTo [glob-patterns ...]             ; vector of strings, OPTIONAL - only if frontmatter needs updating\n"
   "     }\n"
   "    ```\n"
   "  - Your task is complete!\n"
   "- ELSE IF no existing memory file:\n"
   "  - Your deliverable is an EDN structure:\n\n"
   "    ```clojure\n"
   "    {:new-file true                           ; boolean, REQUIRED for new files\n"
   "     :domain                                  ; string\n"
   "     :file-path path                          ; string, absolute path to memory file\n"
   "     :description domain-memory-description   ; string, keep the description general, focusing on the domain responsibility rather than implementation specifics\n"
   "     :domain-tagline memory-domain-tagline    ; string, a version of the domain-memory-description that is crafted for AI agent consumption\n"
   "     :applyTo [glob-patterns ...]             ; vector of strings\n"
   "     :heading memory-heading                  ; string, E.g. `Clarity over brevity`\n"
   "     :content memory-content-markdown         ; string, the memorization of the `SESSION-LESSON`\n"
   "     }\n"
   "    ```\n"
   "  - Your task is complete!\n"))

(comment
  (remember-prompt {:ma/domain nil})
  (remember-prompt {:ma/domain "foo"})
  :rcf)

(defn async-iterator-seq
  "Consumes an async generator/iterator and returns a promise of all values.
  Works with any JavaScript object that implements Symbol.asyncIterator.

  Example:
    (p/let [values (async-iterator-seq some-async-generator)]
      (prn values)) ; => [val1 val2 val3 ...]"
  [generator]
  (let [iter (.call (aget generator js/Symbol.asyncIterator) generator)
        results (atom [])]
    (p/loop []
      (p/let [v (.next iter)]
        (if (.-done v)
          @results
          (do (swap! results conj (.-value v))
              (p/recur)))))))

(defn consume-lm-response
  "Consumes a Language Model response stream and returns the complete text.

  Example:
    (p/let [response (make-lm-request model messages)
            text (consume-lm-response response)]
      (prn text))"
  [response]
  (p/let [chunks (async-iterator-seq (.-text response))]
    (apply str chunks)))

(defn build-new-file-content
  "Build complete file content with frontmatter from new file response"
  [{:keys [description domain-tagline applyTo heading content]}]
  (str "---\n"
       "description: '" description "'\n"
       "applyTo: '" (string/join ", " applyTo) "'\n"
       "---\n\n"
       "# " domain-tagline "\n\n"
       "## " heading "\n\n"
       content))

(defn build-goal-prompt
  "Build the complete goal prompt for the memory agent.

  Combines the prompt template, search directory, and lesson summary into the final goal string.

  Args:
    config - Map with keys:
      :ma/summary - String describing the lesson learned (required)
      :ma/domain - Optional string for domain hint (e.g., 'clojure', 'git-workflow')
      :ma/search-dir - Absolute path to search directory (required)

  Returns:
    Complete goal prompt string ready for the autonomous agent"
  [{:ma/keys [summary domain search-dir]}]
  (-> (remember-prompt {:ma/domain domain})
      (string/replace "{SEARCH-DIRECTORY}" search-dir)
      (string/replace "{LESSON}" summary)))

(defn validate-file-content
  "Validates that new content is substantially complete compared to existing.

  Args:
    new-content - New content from agent
    existing-content - Existing file content (or nil for new files)

  Returns: {:valid? true} or {:valid? false :reason string}"
  [new-content existing-content]
  (if (or (nil? existing-content) (string/blank? existing-content))
    ;; New file - always valid
    {:valid? true}
    ;; Existing file - validate completeness
    (let [new-lines (count (string/split-lines new-content))
          existing-lines (count (string/split-lines existing-content))
          ratio (if (pos? existing-lines) (/ new-lines existing-lines) 1.0)]
      (cond
        ;; New content is significantly shorter - likely incomplete
        (< ratio 0.75)
        {:valid? false
         :reason (str "⚠️  VALIDATION FAILED: New content (" new-lines " lines) is "
                      "significantly shorter than existing (" existing-lines " lines). "
                      "Agent likely returned incomplete content instead of merging.")}

        ;; Content looks valid
        :else
        {:valid? true}))))

(defn read-existing-file!+
  "Read existing file content if it exists.

  Returns: Promise of file content string, or nil if file doesn't exist"
  [file-path]
  (p/catch
   (p/let [uri (vscode/Uri.file file-path)
           content-bytes (vscode/workspace.fs.readFile uri)
           decoder (js/TextDecoder.)]
     (.decode decoder content-bytes))
   (fn [_error]
     ;; File doesn't exist
     nil)))

(defn write-memory-file!+
  "Write memory file using workspace.fs API.

  Args:
    file-path - Absolute path to the file
    content - Complete file content

  Returns: Promise of {:success true :file-path string} or {:error string}"
  [file-path content]
  (p/catch
   (p/let [uri (vscode/Uri.file file-path)
           encoder (js/TextEncoder.)
           encoded-content (.encode encoder content)
           _ (vscode/workspace.fs.writeFile uri encoded-content)]
     ;; Show message without blocking return
     (vscode/window.showInformationMessage (str "✅ Memory recorded: " file-path))
     {:success true :file-path file-path})
   (fn [error]
     {:error (str "Failed to write file: " (.-message error))
      :file-path file-path})))

(defn update-frontmatter-applyTo
  "Update applyTo field in frontmatter if present.

  Args:
    content - File content with frontmatter
    new-applyTo - Vector of new glob patterns

  Returns: Updated content with new applyTo, or original if no frontmatter"
  [content new-applyTo]
  (if-let [[_ before _ after] (re-find #"(?s)(---\n.*?applyTo: )'([^']*)'(.*?---.*)" content)]
    (str before "'" (string/join ", " new-applyTo) "'" after)
    content))

(defn append-memory-section
  "Append new memory section to existing file content.

  Args:
    existing-content - Current file content
    heading - H2 heading for new section
    content - Memory content markdown
    applyTo - Optional vector of glob patterns to update frontmatter

  Returns: Complete updated file content"
  [{:keys [existing-content heading content applyTo]}]
  (let [with-frontmatter (if applyTo
                           (update-frontmatter-applyTo existing-content applyTo)
                           existing-content)]
    (str with-frontmatter
         "\n\n## " heading
         "\n\n" content)))

(defn extract-edn-from-response
  "Extracts EDN structure from agent response, handling wrapped format or direct EDN.

  Args:
    response - Agent response string, may contain BEGIN/END markers or direct EDN

  Returns: Parsed EDN map or nil if parsing fails or result is not a map"
  [response]
  (let [marker-match (re-find #"(?s)---BEGIN RESULTS---\s*(.*?)\s*---END RESULTS---" response)
        edn-string (if marker-match
                     (string/trim (second marker-match))
                     (string/trim response))]
    (try
      (let [parsed (edn/read-string edn-string)]
        (when (map? parsed)
          parsed))
      (catch js/Error _
        nil))))

(defn extract-description-from-content
  "Extract description from file content frontmatter.

  Args:
    content - File content string

  Returns: Description string or nil if not found"
  [content]
  (when content
    (second (re-find #"description:\s*'([^']*)'" content))))

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

(defn build-file-descriptions-map!+
  "Build a map of file descriptions from instruction files.

  Args:
    dir-path - Absolute path to directory

  Returns: Promise of vector of {:file string :description string} maps"
  [dir-path]
  (p/let [files (list-instruction-files!+ dir-path)
          ;; Read each file and extract description
          file-data (p/all
                     (for [filename files]
                       (p/let [content (read-existing-file!+ (str dir-path "/" filename))
                               description (extract-description-from-content content)]
                         {:file filename
                          :description description})))]
    ;; Filter out files without descriptions
    (filterv :description file-data)))

(defn format-description-listing
  "Format file descriptions into a text listing for the prompt.

  Args:
    descriptions - Vector of {:file string :description string} maps
    search-dir - Base directory path

  Returns: Formatted string or empty string if no descriptions"
  [descriptions search-dir]
  (if (seq descriptions)
    (str "\n\n## Available Memory Files\n\n"
         "The following memory instruction files exist. Match the lesson to the most appropriate domain:\n\n"
         (string/join "\n"
                     (for [{:keys [file description]} descriptions]
                       (str "- `" file "` - " description)))
         "\n\n**To read a file**: Use `copilot_readFile` with absolute path like `"
         search-dir "/" (:file (first descriptions)) "`\n")
    ""))

(defn record-memory!+
  "Records a memory using autonomous agent workflow with orchestrator pattern.

  Agent analyzes and decides, orchestrator writes files.

  Args:
    memory-data - Map with keys:
      :summary - String describing the lesson learned (required)
      :domain - Optional string for domain hint (e.g., 'clojure', 'git-workflow')
      :scope - Keyword :global or :workspace (default: :global)
      :model-id - Optional model override (default: 'grok-code-fast-1')
      :max-turns - Optional turn limit override (default: 10)
      :progress-callback - Optional progress function

  Returns:
    Promise of result map:
    - Success: {:success true :file-path string}
    - Failure: {:success false :error string :error-type keyword :file-path string}
      Error types:
      - :write-failed - File write operation failed
      - :file-not-found - Tried to append to non-existent file
      - :parse-failed - Could not parse agent response"
  [{:keys [summary domain scope model-id max-turns progress-callback]
    :or {scope :global
         model-id agent-model
         max-turns 10
         progress-callback #(println "📝" %)}}]
  (p/let [;; Step 1: Determine search directory from scope
          search-dir (case scope
                       :global (user-data-instructions-path)
                       :workspace (workspace-instructions-path)
                       (user-data-instructions-path))

          ;; Step 1b: Build description listing for available files
          file-descriptions (build-file-descriptions-map!+ search-dir)
          description-listing (format-description-listing file-descriptions search-dir)

          ;; Steps 2-3: Build complete goal prompt with description listing
          goal (str (build-goal-prompt {:ma/summary summary
                                        :ma/domain domain
                                        :ma/search-dir search-dir})
                    description-listing)

          ;; Step 4: Define read-only tools for analysis
          tool-ids ["copilot_findFiles"
                    "copilot_readFile"
                    "copilot_findTextInFiles"]

          ;; Step 5: Call agent for analysis and content creation
          agent-result (agents/autonomous-conversation!+
                        goal
                        {:model-id model-id
                         :max-turns max-turns
                         :tool-ids tool-ids
                         :progress-callback progress-callback})

          ;; Step 6: Extract final response text
          final-text (or (get-in agent-result [:final-response :text])
                         "")

          ;; Step 7: Parse agent's decision (handles wrapped or direct EDN)
          {:keys [file-path] :as parsed} (extract-edn-from-response final-text)]

    (if parsed
      ;; Step 8: Check if file exists first (handles agent misidentifying new vs existing)
      (p/let [existing-content (read-existing-file!+ file-path)]
        (if existing-content
          ;; File exists - always append (even if agent said :new-file true)
          ;; Don't trust agent's applyTo when appending to existing unread file
          (p/let [updated-content (append-memory-section
                                   {:existing-content existing-content
                                    :heading (:heading parsed)
                                    :content (:content parsed)
                                    :applyTo nil}) ; Ignore applyTo - agent hasn't read existing frontmatter
                  write-result (write-memory-file!+ file-path updated-content)]
            (if (:success write-result)
              {:success true
               :file-path file-path}
              {:success false
               :error (:error write-result)
               :error-type :write-failed
               :file-path file-path}))
          ;; File doesn't exist - create new file
          (if (:new-file parsed)
            (p/let [complete-content (build-new-file-content parsed)
                    write-result (write-memory-file!+ file-path complete-content)]
              (if (:success write-result)
                {:success true
                 :file-path file-path}
                {:success false
                 :error (:error write-result)
                 :error-type :write-failed
                 :file-path file-path}))
            {:success false
             :error "File does not exist but agent didn't provide new-file structure"
             :error-type :file-not-found
             :file-path file-path})))
      ;; Parsing failed
      {:success false
       :error "Failed to parse agent response. Agent did not return expected format."
       :error-type :parse-failed})))

(comment
  ;; Basic usage - global memory with domain hint
  (record-memory!+
   {:summary "Use REPL evaluation of subexpressions instead of println for debugging"})

  (record-memory!+
   {:summary "Use REPL evaluation of subexpressions instead of println for debugging"
    :domain "clojure"})

  (record-memory!+
   {:summary "Use REPL evaluation of subexpressions instead of println for debugging"
    :domain "clojure"
    :model-id "claude-sonnet-4"})

  ;; Without domain hint (universal memory)
  (record-memory!+
   {:summary "Always verify API responses before assuming success"})

  ;; Workspace-scoped memory
  (record-memory!+
   {:summary "Threading macros improve readability in data pipelines"
    :domain "clojure"
    :scope :workspace})

  ;; With custom options
  (record-memory!+
   {:summary "Use --autostash flag with git rebase"
    :domain "git-workflow"
    :model-id "claude-sonnet-4"
    :max-turns 15
    :progress-callback vscode/window.showInformationMessage})

  ;; Get result and inspect
  (p/let [result (record-memory!+ {:summary "Some lesson..."
                                   :domain "testing"})]
    (println "Success:" (:success result))
    (println "File path:" (:file-path result)))

  :rcf)

