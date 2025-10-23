;; AGENTS, please read this preamble before working with the namespace:
;; - Use interactive programming
;; - Work using TDD in the repl, existing tests: src/test/agents/memory_keeper_test.cljs
#_(do (require 'run-all-tests :reload) (run-all-tests/run!+))
;; - Always prefer your structural editing tools

(ns agents.memory-keeper
  "Autonomous memory recording agent using the LM agent dispatch system"
  (:require
   ["vscode" :as vscode]
   [agents.agent-util :as agent-util]
   [lm-dispatch.agent-orchestrator :as agent-orchestrator]
   [cljs.pprint]
   [clojure.edn :as edn]
   [clojure.string :as string]
   [promesa.core :as p]))

(def agent-model "grok-code-fast-1")
(def default-max-turns 15)
(def agent-tool-ids ["copilot_readFile"])

(defn remember-prompt
  "Returns agent instructions string for memory recording, optionally using
   known `:ma/domain` to skip domain discovery."
  [{:ma/keys [domain]}]
  (str
   "# Memory Recording Agent\n\n"
   "You are an expert prompt engineer and keeper of **"
   (when domain (str "the " domain " "))
   "domain-organized Memory Instructions** that persist across VS Code contexts. You know how to "
   (when-not domain "automatically bin learnings by domain, and ")
   "add to or create new memory files as needed.\n\n"

   (when domain
     (str "<DOMAIN>" domain "</DOMAIN>\n\n"))

   "## Session Lesson\n\n<SESSION-LESSON>\n{LESSON}\n</SESSION-LESSON>\n\n"

   "## Your Mission\n\n"
   "Transform the `SESSION-LESSON` into **domain-targeted, succinct, reusable knowledge**, "
   "that helps the AI agent to effectively find the best patterns and avoid common mistakes.\n\n"

   "## Critical Rules\n\n"
   "- **Integrate carefully** - Place new memories in logical sections\n"
   "- **Be concise** - Memory entries should be scannable and actionable\n"
   "- Work systematically. Research first, then craft the complete solution.\n\n"

   "## Available Memory Files\n\n"
   "The following instruction files exist:\n"
   "<AVAILABLE-MEMORY-FILES>\n"
   "{DESCRIPTIONS}\n"
   "</AVAILABLE-MEMORY-FILES>\n"
   (if domain
     "Consider that the user may have mistyped the domain. Use <AVAILABLE-MEMORY-FILES> to do a sanity check. The domain is encoded in the basename, `<domain>.instructions.md` for the main domain file, and `<domain>-memory.instructions.md` for the domain memory file. If you think the domain is mistyped, use the corrected domain instead of the provided one.\n\n"
     "The domain is encoded in the basename, `<domain>.instructions.md` for the main domain file, and `<domain>-memory.instructions.md` for the domain memory file. Use the domain and descriptions to find which domain (if any) best matches the `SESSION-LESSON`.\n\n")

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

   "Your deliverable is an EDN structure, without code fence, wrapped in `---BEGIN RESULTS---`/`---END RESULTS---` markers:\n\n"

   "- IF a memory file already exist:\n"
   "  - Provide a new memory section to be appended to the file:\n"
   "    ---BEGIN RESULTS---\n"
   "    {:domain                                  ; string\n"
   "     :file-path path                          ; string, absolute path to memory file\n"
   "     :heading memory-heading                  ; string, will be used for a level 2 heading for the new section\n"
   "     :content memory-content-markdown         ; string, the new memory content\n"
   "     :applyTo [glob-patterns ...]             ; vector of strings, OPTIONAL - only if frontmatter needs updating\n"
   "     }\n"
   "    ---END RESULTS---\n"
   "    ~~~GOAL-ACHIEVED~~~\n"
   "- ELSE IF no existing memory file:\n"
   "  - Provide the data for an entirely new memory file:\n\n"
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
   "    ~~~GOAL-ACHIEVED~~~\n"
   "- IF you find that the memory is already covered:\n"
   "  - Provide the data for an entirely new memory file:\n\n"
   "    ---BEGIN RESULTS---\n"
   "    {:memory-exists? true                     ; boolean\n"
   "     :message                                 ; string, say something about the coverage\n"
   "     :domain                                  ; string\n"
   "     :file-path path                          ; string, absolute path to the memory file where the memory already exists\n"
   "     }\n"
   "    ---END RESULTS---\n"
   "    ~~~GOAL-ACHIEVED~~~\n"

   ))

(comment
  (remember-prompt {:ma/domain nil})
  (remember-prompt {:ma/domain "foo"})
  :rcf)

(defn trim-heading-from-content
  "Returns `content` with any leading H2 heading removed to prevent duplicates."
  [content]
  (let [;; Match any H2 heading at start: ## followed by text and newlines
        pattern (js/RegExp. "^##\\s+[^\\n]+\\s*\\n+" "")
        trimmed (string/replace content pattern "")]
    (string/trim trimmed)))

(defn build-new-file-content
  "Returns complete file content string with frontmatter built from new file
   response map (`:description`, `:domain-tagline`, `:applyTo`, `:heading`, `:content`)."
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
  "Returns complete goal prompt string for memory agent by combining template,
   `:ma/search-dir`, and `:ma/summary` (required), with optional `:ma/domain` hint."
  [{:ma/keys [summary domain search-dir description-listing]}]
  (-> (remember-prompt {:ma/domain domain})
      (string/replace "{SEARCH-DIRECTORY}" search-dir)
      (string/replace "{DESCRIPTIONS}" description-listing)
      (string/replace "{LESSON}" summary)))



(defn write-memory-file!+
  "Returns promise of result map with `:success` true and `:file-path`,
   or `:error` string if write fails."
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
  "Returns updated `content` with applyTo field in frontmatter set to `new-applyTo`,
   or original content if no frontmatter present."
  [content new-applyTo]
  (if-let [[_ before _ after] (re-find #"(?s)(---\n.*?applyTo: )'([^']*)'(.*?---.*)" content)]
    (str before "'" (string/join ", " new-applyTo) "'" after)
    content))

(defn clean-heading
  "Returns `heading` text without ## prefix to prevent duplicate ##."
  [heading]
  (-> heading
      (string/replace #"^##\s+" "")
      string/trim))



(defn append-memory-section
  "Returns complete updated file content with new memory section appended.

   Takes `:existing-content`, `:heading` (may include ##), `:content` (may start
   with ## heading), and optional `:applyTo` vector for frontmatter update."
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

(defn extract-edn-from-agent-result
  "Returns parsed EDN map from `agent-result` using agent-util extraction
   (between ---BEGIN RESULTS---/---END RESULTS--- markers), or `nil` if
   extraction fails or result is not a map."
  [agent-result]
  (let [extraction (agent-util/extract-marked-content
                    agent-result
                    "---BEGIN RESULTS---"
                    "---END RESULTS---")]
    (when-not (:extraction-failed extraction)
      (try
        (let [parsed (edn/read-string (:content extraction))]
          (when (map? parsed)
            parsed))
        (catch js/Error _
          nil)))))

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
      :instructions - Instructions as string, vector of paths, or :instructions-selector
      :context-file-paths - Vector of additional instruction file paths to include as context
      :editor-context/file-path - Optional: Current editor file path
      :editor-context/selection-start-line - Optional: Selection start line (0-based)
      :editor-context/selection-end-line - Optional: Selection end line (0-based)

  Returns:
    Promise of result map:
    - Success: {:success true :file-path string}
    - Failure: {:success false :error string :error-type keyword :file-path string}
      Error types:
      - :write-failed - File write operation failed
      - :file-not-found - Tried to append to non-existent file
      - :parse-failed - Could not parse agent response"
  [{:keys [summary domain scope model-id max-turns tool-ids title]
    :or {scope :global
         model-id agent-model
         tool-ids agent-tool-ids
         max-turns default-max-turns
         title "Keeping a memory"}
    :as conversation-data}]
  (p/let [normalized-scope (agent-util/normalize-scope scope)
          search-dir (case normalized-scope
                       :global (agent-util/user-data-instructions-path)
                       :workspace (agent-util/workspace-instructions-path)
                       (agent-util/user-data-instructions-path))
          file-descriptions (agent-util/build-file-descriptions-map!+ search-dir)
          description-listing (agent-util/format-description-listing file-descriptions)
          goal (build-goal-prompt {:ma/summary summary
                                   :ma/domain domain
                                   :ma/search-dir search-dir
                                   :ma/description-listing description-listing})
          agent-result (agent-orchestrator/autonomous-conversation!+
                        goal
                        (merge conversation-data
                               {:model-id model-id
                                :tool-ids tool-ids
                                :max-turns max-turns
                                :title title}))
          {:keys [file-path] :as parsed} (extract-edn-from-agent-result agent-result)
          file-uri-string (agent-util/file-path->uri-string file-path)]

    (if parsed
      ;; Step 8:
      (if (:memory-exists? parsed)
        {:success true
         :memory-already-existed? true
         :message (:message parsed)
         :domain (:domaain parsed)
         :file-uri file-uri-string}
        ;; Check if file exists first (handles agent misidentifying new vs existing)
        (p/let [existing-content (agent-util/read-existing-file!+ file-path)]
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
               :file-uri file-uri-string}))))
      ;; Parsing failed
      {:success false
       :error "Failed to parse agent response. Agent did not return expected format."
       :error-type :parse-failed})))

(comment
  (p/let [;; Step 1: Normalize scope to handle both strings and keywords
          normalized-scope (agent-util/normalize-scope "global")
          ;; Step 2: Determine search directory from normalized scope
          search-dir (case normalized-scope
                       :global (agent-util/user-data-instructions-path)
                       :workspace (agent-util/workspace-instructions-path)
                       (agent-util/user-data-instructions-path))

          ;; Step 1b: Build description listing for available files
          _ (def normalized-scope normalized-scope)
          _ (def search-dir search-dir)
          file-descriptions (agent-util/build-file-descriptions-map!+ search-dir)
          _ (def file-descriptions file-descriptions)
          description-listing (agent-util/format-description-listing file-descriptions)

          ;; Steps 2-3: Build complete goal prompt with description listing
          goal (build-goal-prompt {:ma/summary "Use REPL for debugging"
                                     ;:ma/domain "clojure"
                                   :ma/search-dir (.toString search-dir)
                                   :ma/description-listing description-listing})]
    (println goal))
  :rcf)

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(comment
  ;; Example 1: Basic usage - global memory with domain hint
  (p/let [result (record-memory!+
                  {:summary "Use REPL evaluation of subexpressions instead of println for debugging"
                   :title "Remembering..."
                   :domain "foobartesting"})]
    (def basic-result result)
    result)

  ;; Example 2: Without domain hint (agent determines domain automatically)
  (p/let [result (record-memory!+
                  {:summary "Always verify API responses before assuming success"})]
    (def no-domain-result result)
    result)

  ;; Example 3: Workspace-scoped memory
  (p/let [result (record-memory!+
                  {:title "Thread it"
                   :summary "Threading macros improve readability in data pipelines"
                   :domain "foobar"
                   :scope :workspace})]
    (def workspace-result result)
    result)

  ;; Example 4: With instruction selector
  ;; Let the agent automatically select relevant instruction files
  (p/let [result (record-memory!+
                  {:summary "Prefer structural editing tools over string replacement when editing Clojure files"
                   :domain "foobartesting"
                   :instructions :instructions-selector
                   :caller "rcf-test"})]
    (def instruction-selection-result result)
    result)

  ;; Example 5: With specific instruction file vector
  ;; Provide explicit paths to instruction files
  (p/let [instruction-paths ["src/lm_dispatch/rcf-dummy-files/dummy.instructions.md"
                             "src/lm_dispatch/rcf-dummy-files/dummy-memory.instructions.md"]
          result (record-memory!+
                  {:summary "Use inline def for REPL debugging instead of println"
                   :domain "foobartesting"
                   ;:model-id "claude-haiku-4.5"
                   ;:instructions instruction-paths
                   :caller "rcf-vector-test"})]
    (def vector-result result)
    result)

  ;; Example 6: With specific context files
  ;; Context files are always appended after instructions
  (p/let [context-paths ["src/lm_dispatch/rcf-dummy-files/sample_code.foobar"]
          result (record-memory!+
                  {:summary "Use inline def for REPL debugging instead of println"
                   :domain "foobartesting"
                   :context-file-paths context-paths
                   :caller "rcf-context-test"})]
    (def context-result result)
    result)

  ;; Example 7: Combined instructions and context
  (p/let [result (record-memory!+
                  {:title "Autostash FTW"
                   :summary "Use --autostash flag with git rebase"
                   :domain "foobartesting"
                   :model-id "claude-haiku-4.5"
                   :max-turns 15
                   :instructions :instructions-selector
                   :context-file-paths ["/Users/pez/.config/joyride/scripts/philosophers_race.cljs"]})]
    (def combined-result result)
    result)

  ;; Example 8: Inspect result structure
  (p/let [result (record-memory!+ {:summary "Test result inspection"
                                   :domain "testing"})]
    {:success? (:success result)
     :file-uri (:file-uri result)
     :has-error? (some? (:error result))})

  :rcf)