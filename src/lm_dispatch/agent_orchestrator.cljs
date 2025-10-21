;; AGENTS, please read this preamble before working with the namespace:
;; - Use interactive programming
;; - Work using TDD in the repl
#_(do (require 'run-all-tests :reload) (run-all-tests/run!+))
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

(defn validate-instructions!
  "Validate instructions parameter type.

  Args:
    instructions - Can be string, vector of paths, or :instructions-selector keyword

  Throws: Error if invalid type"
  [instructions]
  (when (and instructions
             (not (string? instructions))
             (not (vector? instructions))
             (not (= :instructions-selector instructions)))
    (throw (js/Error. (str "Invalid instructions type. Expected string, vector, or :instructions-selector, got: " (type instructions))))))

(defn validate-context-paths!
  "Validate context-file-paths parameter type.

  Args:
    context-file-paths - Must be nil or a vector

  Throws: Error if invalid type"
  [context-file-paths]
  (when (and context-file-paths
             (not (vector? context-file-paths)))
    (throw (js/Error. (str "Invalid context-file-paths type. Expected vector or nil, got: " (type context-file-paths))))))

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
    :instructions - Instructions as string, vector of paths, or :instructions-selector (default: 'Go, go, go!')
    :context-file-paths - Vector of additional file paths to include as context

  Returns: Promise of result map with :history, :reason, :final-response"
  ([goal]
   (autonomous-conversation!+ goal {}))

  ([goal {:keys [model-id max-turns tool-ids progress-callback allow-unsafe-tools? caller title
                 instructions context-file-paths]
          :or {model-id "gpt-4o-mini"
               tool-ids []
               max-turns 10
               allow-unsafe-tools? false
               instructions "Go, go, go!"}}]

   ;; Validate inputs
   (validate-instructions! instructions)
   (validate-context-paths! context-file-paths)

   (p/let [conv-id (monitor/start-monitoring-conversation!+
                    (cond-> {:agent.conversation/goal goal
                             :agent.conversation/model-id model-id
                             :agent.conversation/max-turns max-turns
                             :agent.conversation/caller caller
                             :agent.conversation/title title}))

           ;; Handle instruction selection if needed
           final-instructions (if (= :instructions-selector instructions)
                                (do
                                  (logging/log-to-channel! conv-id "🔍 Using instruction selector for this conversation")
                                  (p/let [context-content (instr-util/concatenate-instruction-files!+ context-file-paths)
                                          selected-paths (selector/select-instructions!+
                                                          {:goal goal
                                                           :context-content context-content
                                                           :tool-ids tool-ids
                                                           :caller (or title caller "Instruction Selector")})]
                                    (when (seq selected-paths)
                                      (logging/log-to-channel! conv-id
                                                               (str "📝 Selected " (count selected-paths)
                                                                    " instruction file(s)")))
                                    selected-paths))
                                ;; Otherwise pass through as-is (string or vector)
                                instructions)

           progress-callback (or progress-callback #())
           result (agent-core/agentic-conversation!+
                   {:model-id model-id
                    :goal goal
                    :instructions final-instructions
                    :context-file-paths context-file-paths
                    :max-turns max-turns
                    :tool-ids tool-ids
                    :allow-unsafe-tools? allow-unsafe-tools?
                    :caller caller
                    :conv-id conv-id
                    :progress-callback progress-callback})]

     ;; Log final summary
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





(comment
  ;; Example 1: Basic usage with string instructions (default)
  (p/let [result (autonomous-conversation!+
                  "Count all .clj files in the workspace"
                  {:model-id "gpt-4o-mini"
                   :tool-ids ["copilot_findFiles"]
                   :max-turns 5})]
    result)

  ;; Example 2: Using instruction selector
  (p/let [result (autonomous-conversation!+
                  "Add a new Clojure function using TDD"
                  {:instructions :instructions-selector
                   :tool-ids ["copilot_readFile" "copilot_writeFile"]
                   :max-turns 10})]
    result)

  ;; Example 3: Using vector of instruction file paths
  (p/let [result (autonomous-conversation!+
                  "Create a new feature"
                  {:instructions [(instr-util/user-data-instructions-path "clojure.instructions.md")
                                  (instr-util/user-data-instructions-path "clojure-memory.instructions.md")]
                   :tool-ids ["copilot_readFile" "copilot_writeFile"]
                   :max-turns 10})]
    result)

  ;; Example 4: With context files
  (p/let [result (autonomous-conversation!+
                  "Update the memory system"
                  {:instructions "Go, go, go!"
                   :context-file-paths [(instr-util/user-data-instructions-path "memory.instructions.md")]
                   :tool-ids ["copilot_readFile" "copilot_writeFile"]
                   :max-turns 15})]
    result)

  ;; Example 5: Validation - these should throw
  (try
    (autonomous-conversation!+
     "Test"
     {:instructions 123})  ; Invalid type
    (catch js/Error e
      (.-message e)))

  (try
    (autonomous-conversation!+
     "Test"
     {:context-file-paths "not-a-vector"})  ; Invalid type
    (catch js/Error e
      (.-message e)))

  :rcf)
