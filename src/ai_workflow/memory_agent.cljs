(ns ai-workflow.memory-agent
  "Autonomous memory recording agent using agentic workflow"
  (:require
   [clojure.string :as string]
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
  (workspace-instructions-path)
  )

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
   "- **Use absolute paths** - FILE_PATH must be absolute like `{SEARCH_DIRECTORY}/clojure-memory.instructions.md`\n"
   "- **Be concise** - Memory entries should be scannable and actionable\n"
   "- **Extract patterns** - Generalize from specific instances\n"
   "- Work systematically. Research first, then craft the complete solution.\n\n"

   "## Action steps"

   (if-not domain
     (str "\n\n### 1. Determine the memory domain\n"
          "1. Search all `{SEARCH-DIRECTORY}/*.instructions.md` files for lines starting with `description: `\n"
          "2. Consider if any of these descriptions matches the lesson.\n"
          "   - If so: pick the best match\n"
          "   - Else: decide on a domain and domain slug for this memory\n\n"
          "### 2. Read up on existing domain knowledge")
     "\n\n### 1. Read up on existing domain knowledge")

   "\n1. Read these files:\n"
   "   - **General instructions**; `{SEARCH_DIRECTORY}/copilot.instructions.md`\n"
   "   - **General memories**; `{SEARCH_DIRECTORY}/memory.instructions.md`\n"
   (if domain
     (str "   - **Domain instructions**: `{SEARCH_DIRECTORY}/" domain ".instructions.md`\n"
          "   - **Domain memory**; `{SEARCH_DIRECTORY}/" domain "-memory.instructions.md`\n")
     (str "   - **Domain instructions**: `{SEARCH_DIRECTORY}/<domain>.instructions.md`\n"
          "   - **Domain memory**; `{SEARCH_DIRECTORY}/<domain>-memory.instructions.md`\n"))

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

   "\n- IF a memory file already exist:\n"
   "  - Add the memory to the existing file content where it fits\n"
   "  - If the `applyTo:` frontmatter of the file needs updating, do so. It should be a single quoted, comma separated, list of glob patterns.\n"
   "  - Your deliverable is an EDN structure:\n\n"
   "    ```clojure\n"
   "    {:domain                                          ; string\n"
   "     :file-path path                                  ; string, absolute path to memory file\n"
   "     :file-content complete-merged-file-content-here  ; string\n"
   "     }\n"
   "    ```\n"
   "  - Your task is complete!\n"
   "- ELSE IF no existing memory file:\n"
   "  - Your deliverable is an EDN structure:\n\n"
   "    ```clojure\n"
   "    {:domain                                  ; string\n"
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

(def autonomization-instructions
  "You are transforming a prompt to be fully autonomous. Make these specific changes:

1. Find any text that says 'request human input' - replace it with 'make best judgment based on available information'
2. Find any text that says 'ask the user' - replace it with 'make best judgment based on available information'
3. Keep ALL other text exactly as it appears in the original
4. Return the COMPLETE transformed prompt with all sections intact

Prompt to transform:")

(defn parse-agent-response
  "Parse agent's response to extract file path and content.

  Expected format:
  FILE_PATH: /absolute/path/to/file.md
  ---FILE_CONTENT---
  {content here}
  ---END_CONTENT---

  Returns: {:file-path string :content string} or nil if parsing fails"
  [response-text]
  (when-let [path-match (re-find #"FILE_PATH:\s*(.+?)\n" response-text)]
    (let [file-path (string/trim (second path-match))
          content-match (re-find #"---FILE_CONTENT---\n([\s\S]+?)---END_CONTENT---" response-text)]
      (when content-match
        {:file-path file-path
         :content (second content-match)}))))

(defn determine-search-directory
  "Determine search directory from scope.

  Args:
    scope - :global, :workspace, or nil (defaults to :global)

  Returns: Absolute path to search directory

  Throws: Error if scope is :workspace but no workspace is available"
  [scope]
  (case scope
    :global (user-data-instructions-path)
    :workspace (workspace-instructions-path)
    (user-data-instructions-path)))



(defn build-goal-prompt
  "Build the complete goal prompt for the memory agent.

  Combines the prompt template, search directory, and lesson summary into the final goal string.
  This function encapsulates steps 1-3 of record-memory!+ for testability.

  Args:
    config - Map with keys:
      :ma/summary - String describing the lesson learned (required)
      :ma/domain - Optional string for domain hint (e.g., 'clojure', 'git-workflow')
      :ma/search-dir - Absolute path to search directory (required)

  Returns:
    Complete goal prompt string ready for the autonomous agent"
  [{:ma/keys [summary domain search-dir]}]
  (-> (remember-prompt {:ma/domain domain})
      (string/replace "{SEARCH_DIRECTORY}" search-dir)
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
            encoded-content (.encode encoder content)]
      (p/let [_ (vscode/workspace.fs.writeFile uri encoded-content)]
        (vscode/window.showInformationMessage (str "✅ Memory recorded: " file-path))
        {:success true :file-path file-path}))
    (fn [error]
      {:error (str "Failed to write file: " (.-message error))
       :file-path file-path})))

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
    Promise of {:success true :file-path string :agent-result map} or {:error string}"
  [{:keys [summary domain scope model-id max-turns progress-callback]
    :or {scope :global
         model-id agent-model
         max-turns 10
         progress-callback #(println "📝" %)}}]
  (p/let [;; Step 1: Determine search directory from scope
          search-dir (determine-search-directory scope)

          ;; Steps 2-3: Build complete goal prompt
          goal (build-goal-prompt {:ma/summary summary
                                   :ma/domain domain
                                   :ma/search-dir search-dir})

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

          ;; Step 7: Parse agent's decision
          parsed (parse-agent-response final-text)]

    (if parsed
      ;; Step 8: Validate content before writing
      (p/let [existing-content (read-existing-file!+ (:file-path parsed))
              validation (validate-file-content (:content parsed) existing-content)]
        (if (:valid? validation)
          ;; Step 9: Execute file write operation
          (p/let [write-result (write-memory-file!+ (:file-path parsed) (:content parsed))]
            (if (:success write-result)
              {:success true
               :file-path (:file-path write-result)
               :agent-result agent-result}
              {:error (:error write-result)
               :agent-result agent-result}))
          ;; Validation failed
          {:error (:reason validation)
           :agent-result agent-result
           :existing-content existing-content
           :new-content (:content parsed)}))
      ;; Parsing failed
      {:error "Failed to parse agent response. Agent did not return expected format."
       :agent-result agent-result
       :response-text final-text})))

(comment
  ;; Basic usage - global memory with domain hint
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
