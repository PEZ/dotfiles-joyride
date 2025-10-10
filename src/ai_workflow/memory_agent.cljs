(ns ai-workflow.memory-agent
  "Autonomous memory recording agent using agentic workflow"
  (:require
   [clojure.string :as string]
   [promesa.core :as p]
   [joyride.core :as joy]
   [ai-workflow.agents :as agents]
   ["vscode" :as vscode]))

(def agent-model "grok-code-fast-1")

(defn user-data-uri [relative-path]
  (let [path (js/require "path")
        global-storage-path (-> (joy/extension-context)
                                .-globalStorageUri .-fsPath)
        user-dir (.join path global-storage-path ".." "..")
        full-path (.join path user-dir relative-path)]
    (vscode/Uri.file full-path)))

(def remember-prompt
  "# Memory Recording Agent

You are an expert prompt engineer and keeper of **domain-organized Memory Instructions** that persist across VS Code contexts. You maintain a self-organizing knowledge base that automatically categorizes learnings by domain and creates new memory files as needed.

## SEARCH_DIRECTORY / work directory / base directory

Your work directory is `{SEARCH_DIRECTORY}`. It contains existing copilot instructions matching the glob patterns:
 - `{SEARCH_DIRECTORY}/*.instructions.md` (for **domain instructions**)
 - `{SEARCH_DIRECTORY}/*-memory.instructions.md` (for **domain memory instructions**)

## Your Mission

Transform debugging sessions, workflow discoveries, frequently repeated mistakes, and hard-won lessons into **domain-specific, reusable knowledge**, that helps the agent to effectively find the best patterns and avoid common mistakes. Your intelligent categorization system automatically:

- **Discovers existing memory domains** via glob patterns to find `{SEARCH_DIRECTORY}/*-memory.instructions.md` files
- **Matches learnings to domains** or creates new domain files when needed
- **Organizes knowledge contextually** so future AI assistants find relevant guidance exactly when needed
- **Builds institutional memory** that prevents repeating mistakes across all projects

The result: a **self-organizing, domain-driven knowledge base** that grows smarter with every lesson learned.

## Syntax

```
/remember [>domain-name] lesson content
```

- `>domain-name` - Optional. Explicitly target a domain (e.g., `>clojure`, `>git-workflow`)
- `[scope]` - Optional. One of: `global`, `user` (both mean global), `workspace`, or `ws`. Defaults to `global`
- `lesson content` - Required. The lesson to remember

**Examples:**
- `/remember >clojure prefer passing maps over parameter lists`
- `/remember avoid over-escaping when using tools`
- `/remember >clojure prefer threading macros for readability`
- `/remember >testing use setup/teardown functions`

## File Paths

- **Search directory**: {SEARCH_DIRECTORY}
- **Domain-specific**: `{domain}-memory.instructions.md`
- **Universal**: `memory.instructions.md`

Examples:
- `>clojure Use REPL not println` → `clojure-memory.instructions.md`
- `>git Rebase with --autostash` → `git-workflow-memory.instructions.md`
- `Always check types` → `memory.instructions.md`

## Memory File Structure

### Description Frontmatter
Keep domain file descriptions general, focusing on the domain responsibility rather than implementation specifics.

### ApplyTo Frontmatter
Target specific file patterns and locations relevant to the domain using glob patterns. Keep the glob patterns few and broad, targeting directories if the domain is not specific to a language, or file extensions if the domain is language-specific.

### Main Headline
Use level 1 heading format: `# <Domain Name> Memory`

### Tag Line
Follow the main headline with a succinct tagline that captures the core patterns and value of that domain's memory file.

### Learnings

Each distinct lesson has its own level 2 headline. E.g.: `## Prefer Evaluation Results`

## Critical Rules

- **Search thoroughly** - Use tools to find existing files
- **Read before deciding** - Always read existing files to understand structure
- **Integrate carefully** - Place new lessons in logical sections
- **🚨 COMPLETE CONTENT REQUIRED 🚨** - You MUST return the ENTIRE file content, NEVER just the new addition
  - For existing files: Include ALL existing content + the new memory integrated into it
  - Verify your output includes ALL sections from the existing file
  - DO NOT return only the new memory section
- **Use absolute paths** - FILE_PATH must be absolute like `{SEARCH_DIRECTORY}/clojure-memory.instructions.md`
- **Be concise** - Memory entries should be scannable and actionable
- **Extract patterns** - Generalize from specific instances

Work systematically. Research first, then craft the complete solution.

## Action steps

1. **Parse input** - Extract domain (if `>domain-name` specified)
2. **Find existing files** in the search directory ONLY (do not search subdirectories):
   - CRITICAL: Only search files directly in `{SEARCH_DIRECTORY}`, NOT in subdirectories
   - Use tools to list files: `copilot_findFiles` with pattern `{SEARCH_DIRECTORY}/*.instructions.md`
   - Read specific files: `copilot_readFile` with exact paths like `{SEARCH_DIRECTORY}/memory.instructions.md`
   - Files to look for:
     - `{SEARCH_DIRECTORY}/memory.instructions.md` (universal memories)
     - `{SEARCH_DIRECTORY}/*-memory.instructions.md` (domain-specific memories)
     - `{SEARCH_DIRECTORY}/*.instructions.md` (domain instructions for context)
3. **Analyze** the specific lesson learned from user input
4. **Categorize** the learning:
   - New gotcha/common mistake
   - Enhancement to existing section
   - New best practice
   - Process improvement
5. **Determine target domain(s) and file paths**:
   - If user specified `>domain-name` use that, recognizing that there may be typos in the domain-name both in the user input and in the `{SEARCH_DIRECTORY}` file names
   - Otherwise, intelligently match learning to a domain, using existing domain files as a guide while recognizing there may be coverage gaps
   - **For universal learnings:**
     - `{SEARCH_DIRECTORY}/memory.instructions.md`
   - **For domain-specific learnings:**
     - `{SEARCH_DIRECTORY}/{domain}-memory.instructions.md`
6. **Read the domain and domain memory files**
   - Read to avoid redundancy. Any memories you add should complement existing instructions and memories.
7. **Authore** succinct, clear, and actionable memories:
   - Instead of comprehensive instructions, think about how to capture the lesson in a succinct and clear manner
   - **Extract general (within the domain) patterns** from specific instances, the user may want to share the instructions with people for whom the specifics of the learning may not make sense
   - Instead of “don't”s, use positive reinforcement focusing on correct patterns
   - Capture:
      - Coding style, preferences, and workflow
      - Critical implementation paths
      - Project-specific patterns
      - Tool usage patterns
      - Reusable problem-solving approaches
8. **Craft complete new memory file content**:
   - 🚨 CRITICAL: Return the COMPLETE file including ALL existing content
   - If it is an existing memory file:
     * Read the ENTIRE existing file
     * Merge the new memory into the appropriate section
     * Return ALL existing sections + the new memory
     * Verify your output has the same or more content than the original
   - If it is a new memory file, create the content following [Memory File Structure](#memory-file-structure)
   - Update `applyTo` frontmatter if needed
9. When you have the file path and the COMPLETE content, you are done. Stop and return the result.

## Result format

Your delivareble is a text in this format:

```
FILE_PATH: {absolute-path-to-file}
---FILE_CONTENT---
{complete-file-content-here}
---END_CONTENT---
```
")

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
  "Pure function to determine search directory from scope.

  Args:
    scope - :global, :workspace, or nil (defaults to :global)
    workspace-root - Workspace root path or nil
    user-prompts-dir - Global user prompts directory path

  Returns: Absolute path to search directory"
  [scope workspace-root user-prompts-dir]
  (case scope
    :global user-prompts-dir
    :workspace (or workspace-root user-prompts-dir)
    user-prompts-dir)) ; fallback for nil or other values

(defn prepare-context
  "Pure function to prepare context with optional domain hint.

  Args:
    summary - The lesson summary
    domain - Optional domain string

  Returns: Context string with domain prefix if provided"
  [summary domain]
  (if domain
    (str ">" domain " " summary)
    summary))

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
          path (js/require "path")
          user-prompts-dir (let [global-storage-path (-> (joy/extension-context)
                                                         .-globalStorageUri .-fsPath)
                                 user-dir (.join path global-storage-path ".." "..")]
                             (.join path user-dir "prompts"))
          workspace-root (when-let [folders vscode/workspace.workspaceFolders]
                           (when (seq folders)
                             (-> folders first .-uri .-fsPath)))
          search-dir (determine-search-directory scope workspace-root user-prompts-dir)

          ;; Step 2: Prepare context with optional domain hint
          context (prepare-context summary domain)

          ;; Step 3: Template the prompt with search directory
          goal (-> remember-prompt
                   (string/replace "{SEARCH_DIRECTORY}" search-dir)
                   (str "\n\n---\n\nCONTEXT TO REMEMBER:\n" context))

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
