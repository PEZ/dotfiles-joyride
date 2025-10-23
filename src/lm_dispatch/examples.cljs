(ns lm-dispatch.examples
  "Comprehensive examples demonstrating the autonomous agent architecture.

   This namespace contains Rich Comment Forms (RCFs) showing various workflows
   with the clean namespace separation:
   - agent-core: Pure conversation engine
   - agent-orchestrator: Orchestration with instruction selection
   - instructions-selector: Intelligent instruction file selection"
  (:require
   [lm-dispatch.agent-core :as agent-core]
   [lm-dispatch.agent-orchestrator :as orchestrator]
   [lm-dispatch.instructions-util :as instr-util]
   [promesa.core :as p]))

(comment
  ;; Example 1: Basic autonomous conversation using agent-core
  ;; Direct use of the core conversation engine without orchestration
  (p/let [result (agent-core/agentic-conversation!+
                  {:goal "Count how many .cljs files are in the src directory"
                   :model-id "gpt-4o-mini"
                   :max-turns 3
                   :tool-ids ["copilot_findFiles"]
                   :conv-id "example-1-basic"})]
    (def basic-result result)
    result)

  ;; Inspect the result structure
  basic-result
  (:reason basic-result) ; :task-complete, :max-turns-reached, :cancelled, or :error
  (count (:history basic-result)) ; Number of conversation steps
  (:final-response basic-result) ; Last AI message

  ;; Example 2: Orchestrated conversation (no instruction selection)
  ;; Uses the orchestrator but without automatic instruction selection
  (p/let [result (orchestrator/autonomous-conversation!+
                  "Find all test files and count the total assertions"
                  {:model-id "gpt-4o-mini"
                   :max-turns 5
                   :tool-ids ["copilot_findFiles" "copilot_readFile"]
                   :caller "example-2-orchestrator"
                   :title "Count test assertions"})]
    (def orchestrated-result result)
    result)

  orchestrated-result
  (:reason orchestrated-result)

  ;; Example 3: Using instruction utilities
  ;; Collect and explore available instruction files
  (p/let [descriptions (instr-util/collect-all-instruction-descriptions!+)]
    (def instruction-descriptions descriptions)
    (count descriptions))

  ;; Explore by domain
  (->> instruction-descriptions
       (map :domain)
       (remove nil?)
       distinct
       sort)

  ;; Find Clojure-related instructions
  (->> instruction-descriptions
       (filter #(= "clojure" (:domain %)))
       (mapv :filename))

  ;; Example 4: Get workspace vs user instruction files
  (p/let [workspace-files (instr-util/list-instruction-files!+
                           (instr-util/workspace-instructions-path))
          user-files (instr-util/list-instruction-files!+
                      (instr-util/user-data-instructions-path))]
    {:workspace (count workspace-files)
     :user (count user-files)
     :workspace-files workspace-files
     :user-files user-files})

  ;; Example 5: Orchestrated with instruction selection
  ;; Let the system automatically select relevant instructions
  (p/let [result (orchestrator/autonomous-conversation!+
                  "Review the agent-core namespace and suggest one improvement"
                  {:model-id "grok-code-fast-1"
                   :max-turns 5
                   :tool-ids ["copilot_readFile" "copilot_findFiles"]
                   :caller "example-5-with-selection"
                   :title "Review agent-core"
                   :context-file-paths ["/Users/pez/.config/joyride/src/lm_dispatch/agent_orchestrator.cljs"]
                   :use-instruction-selection? true})]
    (def review-result result)
    result)

  ;; Example 6: Using context-file-paths for specific instructions
  ;; Manually provide instruction files as context
  (p/let [;; Get Clojure instruction files
          all-instructions (instr-util/collect-all-instruction-descriptions!+)
          clojure-files (->> all-instructions
                             (filter #(= "clojure" (:domain %)))
                             (mapv :file))

          ;; Use them as context
          result (orchestrator/autonomous-conversation!+
                  "Suggest a simple function to add to lm-dispatch namespace"
                  {:model-id "gpt-4o-mini"
                   :max-turns 3
                   :tool-ids ["copilot_readFile"]
                   :caller "example-6-context"
                   :context-file-paths clojure-files})]
    (def context-result result)
    result)

  :rcf)