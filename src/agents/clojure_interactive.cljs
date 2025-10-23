;; AGENTS, please read this preamble before working with the namespace:
;; - Use interactive programming
;; - Work using TDD in the repl, tests in: src/test/agents/clojure_interactive_test.cljs
#_(do (require 'run-all-tests :reload) (run-all-tests/run!+))
;; - Always prefer your structural editing tools

(ns agents.clojure-interactive
  "Interactive Clojure agent for REPL-first TDD workflows"
  (:require
   [agents.agent-util :as agent-util]
   [lm-dispatch.agent-orchestrator :as agent-orchestrator]
   [promesa.core :as p]))

;; Configuration defaults
(def default-model "claude-sonnet-4.5")
(def default-max-turns 40)
(def default-instructions :instructions-selector)

;; Tool suite - complete Clojure REPL, Backseat Driver, and Joyride toolset
(def default-tool-ids
  ["clojure_evaluate_code"
   "clojure_symbol_info"
   "clojuredocs_info"
   "clojure_repl_output_log"
   "clojure_balance_brackets"
   "replace_top_level_form"
   "insert_top_level_form"
   "clojure_create_file"
   "clojure_append_code"
   "joyride_evaluate_code"
   "copilot_searchCodebase"
   "copilot_findFiles"
   "copilot_findTextInFiles"
   "copilot_readFile"
   "copilot_listDirectory"
   "copilot_getErrors"
   "copilot_readProjectStructure"
   "copilot_getChangedFiles"
   "copilot_getDocInfo"
   "copilot_githubRepo"])

(defn interactive-programming-goal-prompt
  "Build the interactive programming goal prompt template.

  Instructs the agent to:
  1. Develop solution using REPL-first TDD
  2. Produce structured report with:
     - Achievement summary
     - Complete REPL state reconstruction
     - Verification instructions for Copilot
     - User verification handoff (with STOP instruction)
     - Structural editing guide for Copilot

  This agent shoudld modify files - it should develop the changes
  in the repl, and produce a report to the caller for further
  feedback from the user.

  Args:
    task - String describing the task/goal

  Returns:
    Complete goal prompt string"
  [task]
  (str
   "# Interactive Clojure Programming Task\n\n"
   "<TASK>\n" task "\n</TASK>\n\n"

   "## Your Mission\n\n"
   "Develop the solution for <TASK> using **REPL-first TDD workflow**:\n"
   "1. Start in the REPL - develop and test solutions interactively\n"
   "2. Work incrementally - test each building block as you develop\n"
   "3. Use `clojure.test` - define tests in the REPL and develop to make them pass\n"
   "4. Verify thoroughly - test with multiple cases in the REPL\n"
   "5. **Produce report and STOP** - deliver structured report for Copilot, do NOT modify files\n\n"

   "## Important Tool Usage Note\n\n"
   "You have access to structural editing tools in your tool list. **DO NOT USE THEM**. "
   "They are visible only so you can read their documentation when writing Section 5 of your report. "
   "**This agent does NOT modify files** - that is Copilot's responsibility after user approval.\n\n"

   "## Your Deliverable: Structured Report for Copilot\n\n"
   "Produce a report with these five sections:\n\n"

   "### 1. Achievement Summary\n"
   "- Clear description of what has been implemented\n"
   "- Key design decisions and architectural choices\n"
   "- Any deviations from requirements with rationale\n\n"

   "### 2. REPL State Reconstruction\n"
   "Complete sequence of REPL evaluations to recreate working state:\n"
   "- All namespace requires\n"
   "- All function definitions\n"
   "- All test definitions\n"
   "- All data setup\n"
   "- Ordered top-to-bottom so evaluations restore state after REPL restart\n\n"

   "### 3. Verification Instructions for Copilot\n"
   "- Specific REPL expressions Copilot should evaluate to verify functionality\n"
   "- Expected results for each verification step\n"
   "- Edge cases or boundary conditions to check\n\n"

   "### 4. User Verification Handoff\n"
   "- Instructions for Copilot to present achievements to user in clear summary\n"
   "- Specific prompts for user to test the implementation\n"
   "- **EXPLICIT INSTRUCTION**: 'STOP and wait for user feedback before proceeding'\n"
   "- Clear statement: 'Do not proceed with file commits until user confirms the implementation works correctly'\n\n"

   "### 5. Structural Editing Guide for Copilot\n"
   "**IMPORTANT: This section is FOR COPILOT to use AFTER user approval. You (the interactive agent) do NOT execute these steps.**\n\n"
   "Remind Copilot of key principles:\n"
   "- Plan all edits before executing (list which forms in which files)\n"
   "- Work bottom-to-top within files to avoid line number shifts\n"
   "- Use replace_top_level_form, insert_top_level_form, clojure_create_file, clojure_append_code as appropriate\n"
   "- Read tool documentation if uncertain about usage\n"
   "- Reference the complete REPL state (Section 2) needed after edits\n"
   "- That's it, no need to mention what files to edit or anything like that\n\n"

   "Wrap your deliverable in markers:\n"
   "---BEGIN INTERACTIVE REPORT---\n"
   "[Your five-section report here]\n"
   "---END INTERACTIVE REPORT---\n"
   "~~~GOAL-ACHIEVED~~~\n"))

(defn interactively-do!+
  "Execute interactive programming task using autonomous agent.

  Configures agent with REPL-first TDD workflow and full Clojure/Joyride toolset.
  Agent develops solution in REPL, then produces structured report for Copilot.

  Args:
    conversation-data - Map with keys:
      :task - String describing the programming task (required)
      :model-id - Optional model override (default: claude-haiku-4.5)
      :max-turns - Optional turn limit override (default: 30)
      :tool-ids - Optional tool suite override (default: full suite)
      :instructions - Optional instructions override (default: :instructions-selector)
      :title - Optional progress title (default: 'Interactive Programming')
      :progress-callback - Optional progress function
      :goal - Optional complete goal prompt override (defaults to built prompt)
      Additional keys passed through to agent-orchestrator

  Returns:
    Promise of result map:
    - :model-id, :reason, etc. from agent execution
    - :report-raw - Raw extracted report string (if found)
    - :report - Parsed report map (if found)
    - :extraction-failed - True if report markers not found
    - :debug-info - Debugging information (if extraction failed)

    Note: The full :history is NOT included to prevent massive data return"
  [{:keys [task model-id max-turns tool-ids instructions title goal]
    :or {model-id default-model
         max-turns default-max-turns
         tool-ids default-tool-ids
         instructions default-instructions
         title "Interactive Programming"}
    :as conversation-data}]
  (p/let [;; Build goal prompt if not provided
          final-goal (or goal (interactive-programming-goal-prompt task))

          ;; Call autonomous agent
          agent-result (agent-orchestrator/autonomous-conversation!+
                        final-goal
                        (merge conversation-data
                               {:model-id model-id
                                :tool-ids tool-ids
                                :max-turns max-turns
                                :instructions instructions
                                :title title}))

          ;; Extract report using agent-util
          extraction-result (agent-util/extract-marked-content
                             agent-result
                             "---BEGIN INTERACTIVE REPORT---"
                             "---END INTERACTIVE REPORT---")

          ;; Transform to expected format
          report-result (if-let [content (:content extraction-result)]
                          {:report-raw content
                           :report {:raw content}}
                          extraction-result)]

    ;; Return minimal result with report data, NOT the full history
    (merge
     ;; Essential agent metadata only
     (select-keys agent-result [:model-id :reason :error? :error-message])
     ;; Report extraction results
     report-result)))



(comment
  ;; Test the prompt generation
  (interactive-programming-goal-prompt "Implement a function to calculate fibonacci numbers")

  :rcf)
