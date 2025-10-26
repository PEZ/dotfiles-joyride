;; AGENTS, please read this preamble before working with the namespace:
;; - Use interactive programming
;; - Work using TDD in the repl, existing tests: src/test/lm_dispatch/instructions_selector_test.cljs
#_(do (require 'run-all-tests :reload) (run-all-tests/run!+))
;; - Always prefer your structural editing tools

(ns agents.instructions-selector
  "Agent for selecting relevant instruction files based on task goals"
  (:require
   [cljs.pprint]
   [clojure.string :as string]
   [lm-dispatch.agent-core :as agent-core]
   [lm-dispatch.instructions-util :as instr-util]
   [lm-dispatch.logging :as logging]
   [lm-dispatch.monitor :as monitor]
   [promesa.core :as p]))



;(def selector-model-id "grok-code-fast-1")
(def selector-model-id "claude-haiku-4.5")
(def selector-max-turns 10)

(defn build-selection-prompt
  "Build prompt for instructions selector agent.

  Args:
    goal - The task goal to match instructions against
    file-descriptions - Vector of {:file :filename :description :domain} maps
    context-content - Optional string with slurped context-files content
    tool-ids - Optional vector of tool IDs available for the task

  Returns: Prompt string"
  [{:keys [goal file-descriptions context-content]}]
  (str
   "# Instruction File Selection Task\n\n"
   "You are an expert at analyzing task requirements and selecting the most relevant instruction files.\n\n"

   "## Your Goal\n\n"
   "Select and prioritize instruction files that will help accomplish this task:\n\n"
   "<TASK-GOAL>\n" goal "\n</TASK-GOAL>\n\n"

   (when context-content
     (str "## Additional Context\n\n"
          "The following context files have been provided for this task:\n\n"
          context-content "\n\n"))

   "## Available Instruction Files\n\n"
   "Here are all available instruction files with their descriptions:\n\n"
   "```clojure\n"
   (with-out-str (cljs.pprint/pprint file-descriptions))
   "```\n\n"

   "## Your Task\n\n"
   "0. Do not use tools. You have no tools."
   "1. **Use the descriptions as your PRIMARY selection criteria** - They clearly state what each file covers\n"
   "2. **Use the context you are provided:\n"
   "   - What kind of project is this? (language, framework, domain)\n"
   "   - What files and structures are present?\n"
   "   - Are there specific patterns or conventions in use?\n"
   "3. **Select relevant instruction files** based on:\n"
   "   - Description match with the task goal\n"
   "   - Domain relevance (extracted from filename)\n"
   "   - Project context from your exploration\n"
   "4. **Sort your selection** with most important files LAST (they'll be read last)\n"
   "5. **Return ONLY absolute file paths**, one per line, no other text\n\n"

   "## Selection Guidelines\n\n"
   "- Always include files with `copilot.instructions.md` in their name\n"
   "- Instructions from the workspace are extra important\n"
   "- Consider the `applyTo` to be an important clue.\n"
   "- Include testing/quality instructions if the task involves code changes\n"
   "- **Domain file pairing** - Domain instructions and their memories are ALWAYS paired:\n"
   "  - Domain files: `<domain>.instructions.md` (e.g., `clojure.instructions.md`)\n"
   "  - Domain memory files: `<domain>-memory.instructions.md` (e.g., `clojure-memory.instructions.md`)\n"
   "  - If you select `<domain>.instructions.md`, ALSO select `<domain>-memory.instructions.md`\n"
   "  - If you select `<domain>-memory.instructions.md`, ALSO select `<domain>.instructions.md`\n"
   "  - They contain complementary information and should always be used together\n"
   "- An empty selection is valid if no instructions match\n"
   "- All relevant files are listed in **Available Instruction Files**. Rely on descriptions + applyTo from that listing), instead of trying to list files.\n\n"
   "- DO NOT read instruction files during selection (rely on descriptions + applyTo from **Available Instruction Files**)\n\n"
   "## Output Format\n\n"
   "Your deliverable is the absolute paths of the selected files, one per line, in prioroty order - most important LAST.\n\n"
   "The list should be wrapped in `---BEGIN RESULTS---`/`---END RESULTS---` markers:\n\n"

   "```\n"
   "---BEGIN RESULTS---"
   "/absolute/path/to/first-file.instructions.md\n"
   "/absolute/path/to/second-file.instructions.md\n"
   "/absolute/path/to/most-important-file.instructions.md\n"
   "---END RESULTS---"
   "```\n\n"
   "Inlcude ~~~GOAL-ACHIEVED~~~ in your response containing the results."))

(defn parse-selection-result
  "Returns vector of absolute file paths extracted from agent's `final-response`,
   or empty vector if none selected."
  [final-response]
  (when final-response
    (let [text (:text final-response)
          ;; Extract paths from response - look for lines that look like absolute paths
          lines (string/split-lines (or text ""))
          paths (filter #(and (string/starts-with? % "/")
                              (string/ends-with? % ".instructions.md"))
                        lines)]
      (vec paths))))

(defn select-instructions!+
  "Select relevant instruction files based on task goal.

  Uses an autonomous agent with workspace exploration tools to intelligently
  select and prioritize instruction files based on descriptions and project context.

  Args:
    goal - The task goal to match instructions against
    context-content - Optional string with slurped context-files content
    tool-ids - Optional vector of tool IDs available for the task
    caller - Optional caller identification
    :editor-context/file-path - Optional: Current editor file path
    :editor-context/selection-start-line - Optional: Selection start line (0-based)
    :editor-context/selection-end-line - Optional: Selection end line (0-based)

  Returns: Promise of vector of selected file paths (absolute), sorted with most important last"
  [{:keys [goal context-content model-id max-turns tool-ids caller]
    :or {model-id selector-model-id
         max-turns selector-max-turns
         tool-ids []}
    :as conversation-data}]
  (p/let [file-descriptions (instr-util/collect-all-instruction-descriptions!+)
          selection-goal (build-selection-prompt {:goal goal
                                                  :file-descriptions file-descriptions
                                                  :context-content context-content})
          ;; Register conversation manually since we're using core directly
          conv-id (monitor/start-monitoring-conversation!+
                   {:agent.conversation/goal selection-goal
                    :agent.conversation/model-id model-id
                    :agent.conversation/max-turns max-turns
                    :agent.conversation/caller (or caller (:caller agent-core/default-conversation-data))
                    :agent.conversation/title "Selecting Instructions"})
          _ (logging/log-to-channel! conv-id "🔍 Starting instruction file selection")
          _ (when (seq tool-ids)
              (logging/log-to-channel! conv-id (str "📋 Task tools: " (string/join ", " tool-ids))))
          _ (logging/log-to-channel! conv-id (str "📚 Analyzing " (count file-descriptions) " available instruction files"))
          result (agent-core/agentic-conversation!+
                  (merge conversation-data
                         {:goal selection-goal
                          :conv-id conv-id}))
          selected-paths (parse-selection-result (:final-response result))
          _ (if (seq selected-paths)
              (do
                (logging/log-to-channel! conv-id (str "✅ Selected " (count selected-paths) " instruction file(s):"))
                (doseq [[idx p] (map-indexed vector selected-paths)]
                  (logging/log-to-channel! conv-id (str "    " (inc idx) ". " p))))
              (logging/log-to-channel! conv-id "ℹ️ No instruction files selected"))]
    selected-paths))



(comment
  ;; Example 1: Test instruction selection for a Clojure TDD task
  (p/let [selected (select-instructions!+
                    {:goal "Add a new Clojure function using TDD, minding the rules of this project"
                     :context-content nil
                     :caller-tool-ids ["clojure_evaluate_code" "clojure_symbol_info" "clojuredocs_info"
                                       "clojure_repl_output_log" "clojure_balance_brackets" "replace_top_level_form"
                                       "insert_top_level_form" "clojure_create_file" "clojure_append_code"
                                       "joyride_evaluate_code" "copilot_findFiles" "copilot_readFile"]
                     :caller "rcf-test"})]
    (def test-selection selected)
    selected)

  (count test-selection)

  ;; Example 2: Test with context content
  (p/let [context "Working on a Joyride script for VS Code automation"
          selected (select-instructions!+
                    {:goal "Create a new Joyride command"
                     :context-content context
                     :tool-ids ["copilot_readFile" "copilot_findFiles"]})]
    (def joyride-selection selected)
    selected)

  ;; Example 3: Test parsing selection results
  (parse-selection-result {:text "/path/to/file1.instructions.md\n/path/to/file2.instructions.md"})

  ;; Test with empty result
  (parse-selection-result {:text ""})

  ;; Test with nil
  (parse-selection-result nil)

  :rcf)
