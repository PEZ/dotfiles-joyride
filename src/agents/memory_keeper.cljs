; AGENTS, please:
; - remember interactive programming
; - consider TDD in the repl, existing tests: src/test/agents/memory_keeper_test.cljs
; - prefer your structural editing tools

(ns agents.memory-keeper
  "Autonomous memory recording agent using the LM agent dispatch system"
  (:require
   ["path" :as path]
   ["vscode" :as vscode]
   [lm-dispatch.agent :as agent-dispatch]
   [cljs.pprint]
   [clojure.edn :as edn]
   [clojure.string :as string]
   [joyride.core :as joy]
   [promesa.core :as p]))

(def agent-model "grok-code-fast-1")
(def default-max-turns 15)

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

(defn remember-prompt
  "Creates the agent instructions, utilizing that if we know the domain, the prompt
   does not need have a path for figuring out the domain."
  [{:ma/keys [domain]}]
  (str
   "# Memory Recording Agent\n\n"
   "You are an expert prompt engineer and keeper of **"
   (when domain (str "the " domain " "))
   "domain-organized Memory Instructions** that persist across VS Code contexts. You know how to "
   (when-not domain "automatically bin learnings by domain, and ")
   "add to or create new memory files as needed.\n\n"

   (when domain
     (str "<DOMAIN>" domain "</DOMAIN>"))

   "## Session Lesson\n\n<SESSION-LESSON>\n{LESSON}\n</SESSION-LESSON>\n\n"

   "## Your Mission\n\n"
   "Transform the `SESSION-LESSON` into **domain-targeted, succinct, reusable knowledge**, "
   "that helps the AI agent to effectively find the best patterns and avoid common mistakes."

   "## Critical Rules\n\n"
   "- **Search thoroughly** - Use tools to find existing files\n"
   "- **Read before deciding** - Always use tools to read existing files to understand structure\n"
   "- **Integrate carefully** - Place new memories in logical sections\n"
   "- **Use absolute paths** - FILE_PATH must be absolute like `{SEARCH-DIRECTORY}/clojure-memory.instructions.md`\n"
   "- **Be concise** - Memory entries should be scannable and actionable\n"
   "- Work systematically. Research first, then craft the complete solution.\n\n"

   (when-not domain
     (str
      "## Available Memory Files\n\n"
      "The following instruction files exist:\n"
      "{DESCRIPTIONS}\n"
      "The domain is encoded in the basename, `<domain>.instructions.md` for the main domain file, and `<domain>-memory.instructions.md` for the domain memory file. Use the domain and descriptions to find which domain (if any) best matches the `SESSION-LESSON`"))

   "## Action steps"

   (if-not domain
     (str "\n\n### 1. Determine the memory domain\n"
          "1. Review the \"Available Memory Files\" section - it lists all existing memory files with their descriptions.\n"
          "1. Consider if any of these descriptions matches the lesson.\n"
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

   "\n   **Critical**: Always use absolute paths when reading files.\n"
   "1. **Analyze** the specific `SESSION-LESSON` learned from user input, as it fits with your knowledge about the domain.\n"
   "1. **Categorize** the learning:\n"
   "   - New memory\n"
   "   - Enhancement to existing memory\n"
   "1. **Re-author the lesson into a memory**, with focus on the good pattern\n"
   "   - Avoid creating redundancy\n"
   "   - Instead of comprehensive instructions, think about how to capture the lesson in a succinct and clear manner\n"
   "   - When including a code example, consider using code comments as part of your instruction's commentary. The memory heading, the prose and the code should cooperate in getting the message across succinctly and effectively.\n"
   "   - Instead of \"don't\"s, use positive reinforcement focusing on correct patterns\n"
   "   - If the negative pattern `Y` seems important, weave it in using language such as 'Because X, instead of Y, do X.\n\n"

   (if domain
     "### 2. Deliver results\n"
     "### 3. Deliver results\n")

      "Your deliverable is an EDN structure, wrapped in `---BEGIN RESULTS---`/`---END RESULTS---` markers:\n\n"

   "- IF a memory file already exist:\n"
   "  - Provide a new memory section to be appended to the file:\n"
   "    ```clojure\n"
   "    ---BEGIN RESULTS---\n"
   "    {:domain                                  ; string\n"
   "     :file-path path                          ; string, absolute path to memory file\n"
   "     :heading memory-heading                  ; string, will be used for a level 2 heading for the new section\n"
   "     :content memory-content-markdown         ; string, the new memory content\n"
   "     :applyTo [glob-patterns ...]             ; vector of strings, OPTIONAL - only if frontmatter needs updating\n"
   "     }\n"
   "    ---END RESULTS---\n"
   "    ```\n"
   "  - Your task is complete!\n"
   "- ELSE IF no existing memory file:\n"
   "  - Provide the data for an entirely new memory file:\n\n"
   "    ```clojure\n"
   "    ---BEGIN RESULTS---\n"
   "    {:new-file true                           ; boolean, REQUIRED for new files\n"
   "     :domain                                  ; string\n"
   "     :file-path path                          ; string, absolute path to memory file\n"
   "     :description domain-memory-description   ; string, the description should be about the domain, not the memory\n"
   "     :domain-tagline memory-domain-tagline    ; string, a version of the domain-memory-description that is crafted for AI agent consumption\n"
   "     :applyTo [glob-patterns ...]             ; vector of strings\n"
   "     :heading memory-heading                  ; string, E.g. `Clarity over brevity`, will be used for level 2 heading for this memory in the resulting markdown\n"
   "     :content memory-content-markdown         ; string, the memorization of the `SESSION-LESSON`, without any headings\n"
   "     }\n"
   "    ---END RESULTS---\n"
   "    ```\n"
   "  - Your task is complete!\n"))

(comment
  (remember-prompt {:ma/domain nil})
  (remember-prompt {:ma/domain "foo"})
  :rcf)

(defn trim-heading-from-content
  "Remove H2 heading from start of content if present.

  Prevents duplicate headings when agent includes a heading in content.

  Args:
    content - Memory content that may start with ## heading

  Returns: Content with any leading H2 heading trimmed"
  [content]
  (let [;; Match any H2 heading at start: ## followed by text and newlines
        pattern (js/RegExp. "^##\\s+[^\\n]+\\s*\\n+" "")
        trimmed (string/replace content pattern "")]
    (string/trim trimmed)))

(defn build-new-file-content
  "Build complete file content with frontmatter from new file response"
  [{:keys [description domain-tagline applyTo heading content]}]
  (let [trimmed-content (trim-heading-from-content content)]
    (str "---\n"
         "description: '" description "'\n"
         "applyTo: '" (string/join ", " applyTo) "'\n"
         "---\n\n"
         "# " domain-tagline "\n\n"
         "## " heading "\n\n"
         trimmed-content)))

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
  [{:ma/keys [summary domain search-dir description-listing]}]
  (-> (remember-prompt {:ma/domain domain})
      (string/replace "{SEARCH-DIRECTORY}" search-dir)
      (string/replace "{DESCRIPTIONS}" description-listing)
      (string/replace "{LESSON}" summary)))

(defn read-existing-file!+
  "Returns a promise of file content string, or nil if file doesn't exist"
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
  (p/then
   (p/catch
    (p/let [uri (vscode/Uri.file file-path)
            encoder (js/TextEncoder.)
            encoded-content (.encode encoder content)
            _ (vscode/workspace.fs.writeFile uri encoded-content)]
      {:success true :file-path file-path})
    (fn [error]
      {:error (str "Failed to write file: " (.-message error))
       :file-path file-path}))
   identity))

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

(defn clean-heading
  "Remove ## prefix from heading if present.

  Prevents duplicate ## when agent includes ## in heading field.

  Args:
    heading - H2 heading text that may start with ##

  Returns: Heading text without ## prefix"
  [heading]
  (-> heading
      (string/replace #"^##\s+" "")
      string/trim))



(defn append-memory-section
  "Append new memory section to existing file content with consistent spacing

  Args:
    existing-content - Current file content
    heading - H2 heading for new section (may include ## prefix which will be removed)
    content - Memory content markdown (may start with ## heading which will be trimmed)
    applyTo - Optional vector of glob patterns to update frontmatter

  Returns: Complete updated file content"
  [{:keys [existing-content heading content applyTo]}]
  (let [with-frontmatter (if applyTo
                           (update-frontmatter-applyTo existing-content applyTo)
                           existing-content)
        trimmed-existing (string/trimr with-frontmatter)
        cleaned-heading (clean-heading heading)
        trimmed-content (trim-heading-from-content content)]
    (str trimmed-existing
         "\n\n## " cleaned-heading
         "\n\n" trimmed-content)))

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

(defn find-edn-in-messages
  "Searches agent messages from last to first for EDN wrapped in markers.
  Args:
    agent-messages - Sequence of message maps with :role and :content keys
  Returns: Message content string from first message (searching backwards) containing END marker, or nil"
  [agent-messages]
  (some (fn [msg]
          (let [content (:content msg)]
            (when (and content (string/includes? content "---END RESULTS---"))
              content)))
        (reverse agent-messages)))

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
  "Format file descriptions into a text listing for the prompt.

  Args:
    descriptions - Vector of {:file string :description string} maps
    search-dir - Base directory path

  Returns: Formatted string or empty string if no descriptions"
  [descriptions]
  (when (seq descriptions)
    (str "```clojure\n"
         (with-out-str (cljs.pprint/pprint descriptions))
         "\n```\n")
    #_(string/join "\n"
                 (for [{:keys [file description]} descriptions]
                   (str "- " file ": " description)))))

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
  "Convert file path to URI string, handling cases where it's already a URI string.

  Args:
    file-path - Either an absolute filesystem path or a URI string

  Returns: URI string"
  [file-path]
  (if (string/starts-with? file-path "file://")
    file-path
    (.toString (vscode/Uri.file file-path))))

(defn record-memory!+
  "Records a memory using autonomous agent workflow with orchestrator pattern.

  Agent analyzes and decides, orchestrator writes files.

  Args:
    memory-data - Map with keys:
      :summary - String describing the lesson learned (required)
      :domain - Optional string for domain hint (e.g., 'clojure', 'git-workflow')
      :scope - Keyword or string: :global/:workspace or \"global\"/\"workspace\"/\"ws\" (default: :global)
      :model-id - Optional model override (default: 'grok-code-fast-1')
      :max-turns - Optional turn limit override (default: 10)
      :caller - Optional, but encouraged, who's recording the memory
      :progress-callback - Optional progress function

  Returns:
    Promise of result map:
    - Success: {:success true :file-path string}
    - Failure: {:success false :error string :error-type keyword :file-path string}
      Error types:
      - :write-failed - File write operation failed
      - :file-not-found - Tried to append to non-existent file
      - :parse-failed - Could not parse agent response"
  [{:keys [summary domain caller title scope model-id max-turns progress-callback]
    :or {scope :global
         model-id agent-model
         max-turns default-max-turns}}]
  (p/let [;; Step 1: Normalize scope to handle both strings and keywords
          normalized-scope (normalize-scope scope)
          ;; Step 2: Determine search directory from normalized scope
          search-dir (case normalized-scope
                       :global (user-data-instructions-path)
                       :workspace (workspace-instructions-path)
                       (user-data-instructions-path))

          ;; Step 1b: Build description listing for available files
          file-descriptions (build-file-descriptions-map!+ search-dir)
          description-listing (format-description-listing file-descriptions)

          ;; Steps 2-3: Build complete goal prompt with description listing
          goal (build-goal-prompt {:ma/summary summary
                                   :ma/domain domain
                                   :ma/search-dir search-dir
                                   :ma/description-listing description-listing})

          ;; Step 4: Define read-only tools for analysis
          tool-ids ["copilot_findFiles"
                    "copilot_readFile"
                    "copilot_findTextInFiles"]

          ;; Step 5: Call agent for analysis and content creation
          agent-result (agent-dispatch/autonomous-conversation!+
                        goal
                        {:model-id model-id
                         :max-turns max-turns
                         :tool-ids tool-ids
                         :caller caller
                         :title title
                         :progress-callback progress-callback})

          ;; Step 6: Search agent messages backwards for EDN structure
          ;; Agent may include EDN in any message, not just the last one
          all-messages (get-in agent-result [:history] [])
          agent-messages (filter #(= :assistant (:role %)) all-messages)
          message-with-edn (find-edn-in-messages agent-messages)

          ;; Step 7: Parse agent's decision (handles wrapped or direct EDN)
          {:keys [file-path] :as parsed} (when message-with-edn
                                           (extract-edn-from-response message-with-edn))
          file-uri-string (file-path->uri-string file-path)]

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
               :file-uri file-uri-string}
              {:success false
               :error (:error write-result)
               :error-type :write-failed
               :file-uri file-uri-string}))
          ;; File doesn't exist - create new file
          (if (:new-file parsed)
            (p/let [complete-content (build-new-file-content parsed)
                    write-result (write-memory-file!+ file-path complete-content)]
              (if (:success write-result)
                {:success true
                 :file-uri file-uri-string}
                {:success false
                 :error (:error write-result)
                 :error-type :write-failed
                 :file-uri file-uri-string}))
            {:success false
             :error "File does not exist but agent didn't provide new-file structure"
             :error-type :file-not-found
             :file-uri file-uri-string})))
      ;; Parsing failed
      {:success false
       :error "Failed to parse agent response. Agent did not return expected format."
       :error-type :parse-failed})))

(comment
    (p/let [;; Step 1: Normalize scope to handle both strings and keywords
            normalized-scope (normalize-scope "global")
            ;; Step 2: Determine search directory from normalized scope
            search-dir (case normalized-scope
                         :global (user-data-instructions-path)
                         :workspace (workspace-instructions-path)
                         (user-data-instructions-path))

            ;; Step 1b: Build description listing for available files
            _ (def normalize-scope normalize-scope)
            _ (def search-dir search-dir)
            file-descriptions (build-file-descriptions-map!+ search-dir)
            _ (def file-descriptions file-descriptions)
            description-listing (format-description-listing file-descriptions)

            ;; Steps 2-3: Build complete goal prompt with description listing
            goal (build-goal-prompt {:ma/summary "Use REPL for debugging"
                                     ;:ma/domain "clojure"
                                     :ma/search-dir (.toString search-dir)
                                     :ma/description-listing description-listing})]
      (println goal))
  )

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
   {:title "Thread it"
    :summary "Threading macros improve readability in data pipelines"
    :domain "clojure"
    :scope :workspace})

  ;; With custom options
  (record-memory!+
   {:title "Autostach FTW"
    :summary "Use --autostash flag with git rebase"
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

