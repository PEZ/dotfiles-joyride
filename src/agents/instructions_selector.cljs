; AGENTS, please:
; - remember interactive programming
; - prefer your structural editing tools

(ns agents.instructions-selector
  "Agent for selecting relevant instruction files based on task goals"
  (:require
   [cljs.pprint]
   [clojure.string :as string]
   [lm-dispatch.agent :as agent]
   [lm-dispatch.instructions-util :as instr-util]
   [promesa.core :as p]))

(def selector-model-id "grok-code-fast-1")
(def selector-max-turns 10)
(def selector-tool-ids ["copilot_findFiles"
                        "copilot_readFile"
                        "copilot_listDirectory"
                        "copilot_searchCodebase"])

(defn build-selection-prompt
  "Build prompt for instructions selector agent.

  Args:
    goal - The task goal to match instructions against
    file-descriptions - Vector of {:file :filename :description :domain} maps
    context-content - Optional string with slurped context-files content

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
   "1. **Use the descriptions as your PRIMARY selection criteria** - They clearly state what each file covers\n"
   "2. **Use tools to explore the workspace/project** to understand context:\n"
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
   "- Prefer domain-specific over general instructions when relevant\n"
   "- Include testing/quality instructions if the task involves code changes\n"
   "- An empty selection is valid if no instructions match\n"
   "- DO NOT read instruction files during selection (rely on descriptions + tools)\n\n"
   "## Output Format\n\n"
   "Return ONLY the selected file paths, one per line, in priority order (most important LAST):\n\n"
   "```\n"
   "/absolute/path/to/first-file.instructions.md\n"
   "/absolute/path/to/second-file.instructions.md\n"
   "/absolute/path/to/most-important-file.instructions.md\n"
   "```\n\n"
   "If no files are relevant, return an empty response.\n\n"
   "~~~GOAL-ACHIEVED~~~ when you've completed your selection."))

(defn parse-selection-result
  "Parse agent's final response to extract selected file paths.

  Args:
    final-response - The agent's final response text

  Returns: Vector of absolute file paths, empty if none selected"
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
    file-descriptions - Vector of {:file :filename :description :domain} maps
    context-content - Optional string with slurped context-files content

  Returns: Promise of vector of selected file paths (absolute), sorted with most important last"
  [{:keys [goal file-descriptions context-content]}]
  (p/let [selection-goal (build-selection-prompt {:goal goal
                                                  :file-descriptions file-descriptions
                                                  :context-content context-content})
          result (agent/autonomous-conversation!+
                  selection-goal
                  {:model-id selector-model-id
                   :max-turns selector-max-turns
                   :tool-ids selector-tool-ids
                   :caller "instructions-selector"
                   :title "Selecting Instructions"})
          selected-paths (parse-selection-result (:final-response result))]
    selected-paths))

(comment
  (require '[promesa.core :as p])
  (require '[lm-dispatch.instructions-util :as instr-util])

  ;; Test with user descriptions
  (p/let [descriptions (instr-util/build-file-descriptions-map!+ (instr-util/user-data-instructions-path))
          selected (select-instructions!+ {:goal "Add a new Clojure function using TDD"}
                                          :file-descriptions descriptions
                                          :context-content nil)]
    (def test-selection selected)
    (cljs.pprint/pprint selected))

  ;; Test parsing
  (parse-selection-result {:text "/path/to/file1.instructions.md\n/path/to/file2.instructions.md"})

  :rcf)
