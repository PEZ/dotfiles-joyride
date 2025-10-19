;; AGENTS, please read this preamble before working with the namespace:
;; - Use interactive programming
;; - Work using TDD in the repl
;; - Always prefer your structural editing tools

(ns lm-dispatch.agent-orchestrator
  "Orchestrates autonomous conversations with instruction selection support"
  (:require
   [agents.instructions-selector :as selector]
   [lm-dispatch.agent-core :as agent-core]
   [lm-dispatch.instructions-util :as instr-util]
   [lm-dispatch.logging :as logging]
   [lm-dispatch.monitor :as monitor]
   [lm-dispatch.state :as state]
   [promesa.core :as p]))

;; To run all tests:
#_(do (require 'run-all-tests :reload) (run-all-tests/run!+))

(defn autonomous-conversation!+
  "Start an autonomous AI conversation toward a goal with flexible configuration.

  This is the main orchestration function that optionally handles instruction selection
  before delegating to the core agent conversation engine.

  Options:
    :model-id - LM model ID (default: gpt-4o-mini)
    :max-turns - Maximum conversation turns (default: 10)
    :tool-ids - Vector of tool IDs to enable (default: [])
    :progress-callback - Function called with progress updates (default: no-op)
    :allow-unsafe-tools? - Allow file write operations (default: false)
    :caller - String identifying who started the conversation
    :title - Display title for the conversation
    :instructions - Instructions string to prepend to goal (default: 'Go, go, go!')
    :use-instruction-selection? - Enable automatic instruction file selection (default: false)
    :context-file-paths - Vector of additional file paths to include as context

  Returns: Promise of result map with :history, :reason, :final-response"
  ([goal]
   (autonomous-conversation!+ goal {}))

  ([goal {:keys [model-id max-turns tool-ids progress-callback allow-unsafe-tools? caller title
                 instructions use-instruction-selection? context-file-paths]
          :or {model-id "gpt-4o-mini"
               tool-ids []
               max-turns 10
               allow-unsafe-tools? false
               instructions "Go, go, go!"
               use-instruction-selection? false}}]

   (p/let [;; Step 1: Optionally select and prepare instructions
           final-instructions (if use-instruction-selection?
                                (p/let [;; Collect available instruction descriptions
                                        descriptions (instr-util/collect-all-instruction-descriptions!+)

                                        ;; Select relevant instructions
                                        selected-paths (selector/select-instructions!+
                                                        {:goal goal
                                                         :file-descriptions descriptions
                                                         :context-content nil
                                                         :caller (or title caller "Instruction Selector")})

                                        ;; Prepare instructions from selected paths + context files
                                        prepared (instr-util/prepare-instructions-from-selected-paths!+
                                                  {:agent.conversation/selected-paths selected-paths
                                                   :agent.conversation/context-files context-file-paths})]
                                  prepared)
                                ;; Otherwise use provided instructions or default
                                (if (seq context-file-paths)
                                  (p/let [context-content (instr-util/concatenate-instruction-files!+ context-file-paths)]
                                    (if (seq instructions)
                                      (str instructions "\n\n# === Context Files ===\n\n" context-content)
                                      context-content))
                                  instructions))

           ;; Step 2: Start monitoring the conversation
           conv-id (monitor/start-monitoring-conversation!+
                    (cond-> {:agent.conversation/goal goal
                             :agent.conversation/model-id model-id
                             :agent.conversation/max-turns max-turns
                             :agent.conversation/caller caller
                             :agent.conversation/title title}))

           ;; Step 3: Run the core agent conversation
           progress-callback (or progress-callback #())
           result (agent-core/agentic-conversation!+
                   {:model-id model-id
                    :goal goal
                    :instructions final-instructions
                    :max-turns max-turns
                    :tool-ids tool-ids
                    :allow-unsafe-tools? allow-unsafe-tools?
                    :caller caller
                    :conv-id conv-id
                    :progress-callback progress-callback})]

     ;; Step 4: Log final summary
     (if (:error? result)
       (do
         (logging/log-to-channel! conv-id (str "❌ Model error: " (:error-message result)))
         result)
       (let [conv (state/get-conversation conv-id)
             final-status (keyword (:agent.conversation/status conv))
             actual-turns (count (filter #(= (:role %) :assistant) (:history result)))
             summary (str "🎯 Agentic task "
                          (case final-status
                            :task-complete "COMPLETED successfully!"
                            :max-turns-reached "reached max turns"
                            :cancelled "was CANCELLED"
                            :agent-finished "finished"
                            :error "encountered an ERROR"
                            "ended unexpectedly")
                          " (" actual-turns " turns, " (count (:history result)) " conversation steps)")]
         (logging/log-to-channel! conv-id summary)
         result)))))
