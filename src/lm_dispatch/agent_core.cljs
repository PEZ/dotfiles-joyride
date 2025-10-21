;; AGENTS, please read this preamble before working with the namespace:
;; - Use interactive programming
;; - Work using TDD in the repl, existing tests: src/test/lm_dispatch/agent_test.cljs
#_(do (require 'run-all-tests :reload-all) (run-all-tests/run!+))
;; - Always prefer your structural editing tools

(ns lm-dispatch.agent-core
  "Pure autonomous AI conversation engine - no instruction selection dependencies"
  (:require
   ["vscode" :as vscode]
   [clojure.string :as string]
   [lm-dispatch.state :as state]
   [lm-dispatch.logging :as logging]
   [lm-dispatch.monitor :as monitor]
   [lm-dispatch.util :as util]
   [lm-dispatch.instructions-util :as instr-util]
   [promesa.core :as p]))

(def default-conversation-data
  {:model-id "grok-code-fast-1"
   :max-turns 10
   :progress-callback #()
   :title "Untitled"
   :caller "Unknown"
   :tool-ids []
   :allow-unsafe-tools? false})

(def agentic-system-prompt
  "You are an autonomous AI agent with the ability to take initiative and drive conversations toward goals.

AGENTIC BEHAVIOR RULES:
1. When given a goal, break it down into steps and execute them
2. Use available tools proactively to gather information or take actions
3. After each tool use, analyze the results and decide your next action
4. If a tool returns unexpected results or fails, ADAPT your approach - don't repeat the same action
5. Continue working toward the goal without asking for help
6. Provide progress updates as you work
7. Never stop and ask the human anything
8. Take creative initiative to solve problems

LEARNING FROM FAILURES:
- If tool results are not what you expected, try a different approach
- Don't repeat the exact same tool call if it didn't work the first time
- Explain what you learned and how you're adapting your strategy
- Consider the tool results as feedback to guide your next steps

CONVERSATION FLOW:
- Receive goal from human
- Plan your approach
- Execute tools and actions
- Analyze results and continue OR adapt if results weren't as expected
- Report progress and findings
- Suggest next steps or completion

Be proactive, creative, and goal-oriented. Drive the conversation forward!")

(defn build-agentic-messages
  "Build message history for agentic conversation with actionable tool feedback.

  The goal is kept separate from history and injected as the first message on every turn,
  ensuring the LM always has context about what it's trying to accomplish. The history
  vector contains only assistant responses and tool results.

  Args:
    history - Vector of conversation entries with :role :assistant or :tool-results
    instructions - String with additional instructions to prepend to goal
    goal - String describing the task (immutable, never stored in history)

  Returns:
    Vector of messages formatted for LM API with goal first, followed by conversation"
  [history instructions goal]
  (let [goal-message {:role :user
                      :content (str instructions
                                    "\n\n"
                                    "<GOAL>\n" goal "\n</GOAL>"
                                    "\n\nPlease work autonomously toward the `GOAL`. "
                                    "Take initiative, use tools as needed, and continue "
                                    "until the goal is achieved.")}]
    (if (empty? history)
      ;; Turn 1: Just the initial goal
      [goal-message]
      ;; Subsequent turns: Goal message + conversation history
      (cons goal-message
            (mapcat (fn [entry]
                      (case (:role entry)
                        :assistant [{:role :assistant :content (:content entry)}]
                        :tool-results
                        (map (fn [result]
                               {:role :user
                                :content (str "TOOL RESULT: " result
                                              "\n\nAnalyze this result and continue toward the goal. "
                                              "If the goal is achieved, state completion with the marker: ~~~GOAL-ACHIEVED~~~\n\n"
                                              "If not, say ~~~CONTINUING~~~, and adapt your approach based on the results.")})
                             (:processed-results entry))
                        [])) ; skip other roles
                    history)))))

(defn agent-indicates-completion?
  "Check if AI agent indicates the task is complete.

  Uses a two-phase approach:
  1. Check for completion indicators
  2. Exclude negations (not, hasn't, isn't, etc.)"
  [ai-text]
  (when ai-text
    (and (re-find #"(?i)(~~~GOAL-ACHIEVED~~~|task.*(complete|done|finished)|goal.*(achieved|reached|accomplished)|mission.*(complete|success)|successfully (completed|finished))" ai-text)
         (not (re-find #"(?i)(not|n't|hasn't|haven't|isn't|aren't).{0,10}(complete|done|finished|achieved|reached|accomplished)" ai-text)))))

(defn- agent-indicates-continuation? [ai-text]
  (when ai-text
    (re-find #"(?i)(~~~CONTINUING~~~|next.*(step|action)|i'll|i.will|let.me|continu|proceed)" ai-text)))

(defn add-assistant-response
  "Add AI assistant response to conversation history.

  Note: History contains only assistant responses and tool results, not the goal.
  The goal is kept separate and injected into messages by build-agentic-messages."
  [history ai-text tool-calls turn-count]
  (conj history
        {:role :assistant
         :content ai-text
         :tool-calls tool-calls
         :turn turn-count}))

(defn add-tool-results
  "Add tool execution results to conversation history"
  [history tool-results turn-count]
  (conj history
        {:role :tool-results
         :results tool-results
         :processed-results tool-results
         :turn turn-count}))

(defn determine-conversation-outcome
  "Determine if conversation should continue and why"
  [ai-text tool-calls turn-count max-turns]
  (cond
    (>= turn-count max-turns)
    {:continue? false :reason :max-turns-reached}

    (seq tool-calls)
    {:continue? true :reason :tools-executing}

    (agent-indicates-completion? ai-text)
    {:continue? false :reason :task-complete}

    (and ai-text (agent-indicates-continuation? ai-text))
    {:continue? true :reason :agent-continuing}

    :else
    {:continue? false :reason :agent-finished}))

(defn format-completion-result
  "Format the final conversation result"
  [history reason final-response]
  {:history history
   :reason reason
   :final-response final-response})

(defn execute-conversation-turn
  "Execute a single conversation turn - handles request/response cycle"
  [{:keys [model-id goal instructions history turn-count tools-args]}]
  (p/catch
   (p/let [messages (build-agentic-messages history instructions goal)
           response (util/send-prompt-request!+
                     {:model-id model-id
                      :system-prompt agentic-system-prompt
                      :messages messages
                      :options tools-args})
           ;; Extract cancellation token from tools-args and pass it to stream iteration
           cancellation-token (when tools-args (.-cancellationToken (clj->js tools-args)))
           result (util/collect-response-with-tools!+ response cancellation-token)]
     (assoc result :turn turn-count))
   (fn [error]
     {:message (.-message error)
      :turn turn-count})))

(defn execute-tools-if-present!+
  "Execute tool calls if present, return updated history"
  [history tool-calls turn-count conv-id]
  (if (seq tool-calls)
    (do
      (logging/log-to-channel! conv-id (str "🔧 AI Agent executing " (count tool-calls) " tool(s)"))
      (p/let [logger (partial monitor/log-and-update!+ conv-id nil)
              tool-results (util/execute-tool-calls!+ tool-calls logger)]
        (logging/log-to-channel! conv-id (str "✅ Tools executed, processed results: " tool-results))
        (add-tool-results history tool-results turn-count)))
    history))

(defn continue-conversation-loop
  "Main conversation loop.

  The goal parameter is passed separately through all turns and combined with history
  by build-agentic-messages. This keeps the immutable goal separate from the growing
  conversation history."
  [{:keys [model-id goal instructions max-turns progress-callback tools-args conv-id]
    :as conversation-data}
   history turn-count last-response]
  (progress-callback (str "Turn " turn-count "/" max-turns))
  (p/let [;; Count tokens for this turn's messages
          messages (build-agentic-messages history instructions goal)
          turn-tokens (util/count-message-tokens!+ model-id messages)
          current-total (:agent.conversation/total-tokens (state/get-conversation conv-id) 0)
          new-total (+ current-total turn-tokens)]

    (logging/log-to-channel! conv-id (str "📊 Turn " turn-count "/" max-turns " - Starting with " turn-tokens " tokens (total: " new-total " tokens)"))
    (state/update-conversation! conv-id {:agent.conversation/current-turn turn-count
                                         :agent.conversation/status :working
                                         :agent.conversation/total-tokens new-total})
    (monitor/update-agent-monitor-flare!+)
    (if (> turn-count max-turns)
      (format-completion-result history :max-turns-reached last-response)

      (p/catch
       (p/let [;; Execute the conversation turn
               turn-result (execute-conversation-turn
                            {:model-id model-id
                             :goal goal
                             :instructions instructions
                             :history history
                             :turn-count turn-count
                             :tools-args tools-args})

               ;; Check for errors first
               _ (when (:error turn-result)
                   (throw (js/Error. (:message turn-result))))

               ai-text (:text turn-result)
               tool-calls (seq (:tool-calls turn-result))

               ;; Log AI's response
               _ (when ai-text
                   (logging/log-to-channel! conv-id "🤖 AI Agent says:")
                   (logging/log-to-channel! conv-id ai-text))

               ;; Add AI response to history
               history-with-assistant (add-assistant-response history ai-text tool-calls turn-count)

               ;; Execute tools and update history
               final-history (execute-tools-if-present!+ history-with-assistant tool-calls turn-count conv-id)

               ;; Determine what to do next
               outcome (determine-conversation-outcome ai-text tool-calls turn-count max-turns)

               final-tokens (:agent.conversation/total-tokens (state/get-conversation conv-id))]
         (logging/log-to-channel! conv-id (str "✓ Turn " turn-count " completed (total: " final-tokens " tokens)"))

         (if (:continue? outcome)
           ;; Check for cancellation before continuing (catches cancellations between turns)
           (if (:agent.conversation/cancelled? (state/get-conversation conv-id))
             (do
               (logging/log-to-channel! conv-id "🛑 Conversation cancelled by user")
               (format-completion-result final-history :cancelled turn-result))
             (do
               (logging/log-to-channel! conv-id "↻ AI Agent continuing to next step...")
               (continue-conversation-loop
                conversation-data
                final-history
                (inc turn-count)
                turn-result)))
           (do
             (logging/log-to-channel! conv-id (str "Exiting conversation loop: " (:reason outcome)))
             (format-completion-result final-history (:reason outcome) turn-result))))

       (fn [error]
         ;; Handle cancellation errors
         (if (or (= (.-message error) "Cancelled")
                 (re-find #"cancel" (.-message error)))
           (do
             (logging/log-to-channel! conv-id "🛑 Conversation cancelled by user")
             (format-completion-result history :cancelled last-response))
           (do
             (logging/log-to-channel! conv-id (str "❌ Error: " (.-message error)))
             {:history history
              :reason :error
              :error-message (.-message error)
              :final-response last-response})))))))

(defn agentic-conversation!+
  "Create an autonomous AI conversation that drives itself toward a goal.

  Args:
    conversation-data - Map with keys:
      :model-id - LM model ID
      :goal - String describing the task
      :instructions - String or vector of file paths with additional instructions
      :context-file-paths - Optional vector of context file paths
      :max-turns - Maximum conversation turns
      :progress-callback - Function called with progress updates
      :tool-ids - Vector of tool IDs to enable
      :allow-unsafe-tools? - Allow file write operations
      :conv-id - Conversation ID for state tracking

  Returns: Promise of result map with :history, :reason, :final-response"
  [{:keys [goal
           model-id instructions max-turns title caller
           tool-ids progress-callback
           conv-id allow-unsafe-tools? context-file-paths]
    :or {model-id (:model-id default-conversation-data)
         max-turns (:max-turns default-conversation-data)
         progress-callback (:progress-callback default-conversation-data)
         title (:title default-conversation-data)
         caller (:caller default-conversation-data)
         tool-ids (:tool-ids default-conversation-data)
         allow-unsafe-tools? (:allow-unsafe-tools? default-conversation-data)}}]
  (p/let [;; Assemble instructions from string or vector, always appending context after
          final-instructions (instr-util/assemble-instructions!+ instructions context-file-paths)
          tools-args (util/enable-specific-tools tool-ids allow-unsafe-tools?)
          model-info (util/get-model-by-id!+ model-id)]
    (if-not model-info
      {:history []
       :error? true
       :reason :model-not-found-error
       :error-message (str "Model not found: " model-id)
       :final-response nil}
      (p/let [;; Create cancellation token and store it in conversation
              cancellation-token-source (vscode/CancellationTokenSource.)
              _ (state/update-conversation! conv-id {:agent.conversation/cancellation-token-source
                                                     cancellation-token-source})
              result (continue-conversation-loop
                      {:conv-id conv-id
                       :goal goal
                       :model-id model-id
                       :max-turns max-turns
                       :progress-callback progress-callback
                       :title title
                       :caller caller
                       :instructions final-instructions
                       :tools-args (assoc tools-args :cancellationToken (.-token cancellation-token-source))}
                      [] ; empty initial history
                      1  ; start at turn 1
                      nil)
              ;; Check if conversation was cancelled and override reason if so
              final-reason (if (:agent.conversation/cancelled? (state/get-conversation conv-id))
                             :cancelled
                             (:reason result))]
        ;; Update final status
        (state/update-conversation!
         conv-id
         {:agent.conversation/status (case final-reason
                                       :task-complete :task-complete
                                       :max-turns-reached :max-turns-reached
                                       :agent-finished :agent-finished
                                       :cancelled :cancelled
                                       :error :error
                                       :done)
          :agent.conversation/error-message (when (= final-reason :error)
                                              (:error-message result))})
        (monitor/update-agent-monitor-flare!+)
        ;; Dispose the token source
        (.dispose cancellation-token-source)
        result))))

(comment
  ;; Example 1: Core usage with string instructions
  (p/let [conv-id (lm-dispatch.monitor/start-monitoring-conversation!+
                   {:agent.conversation/goal "Test goal"
                    :agent.conversation/model-id "gpt-4o-mini"
                    :agent.conversation/max-turns 5})
          result (agentic-conversation!+
                  {:model-id "gpt-4o-mini"
                   :goal "Count files"
                   :instructions "Go, go, go!"
                   :context-file-paths nil
                   :max-turns 5
                   :tool-ids ["copilot_findFiles"]
                   :conv-id conv-id
                   :progress-callback #()})]
    result)

  ;; Example 2: Core usage with vector of instruction paths
  (p/let [conv-id (lm-dispatch.monitor/start-monitoring-conversation!+
                   {:agent.conversation/goal "Test goal"
                    :agent.conversation/model-id "gpt-4o-mini"
                    :agent.conversation/max-turns 5})
          result (agentic-conversation!+
                  {:model-id "gpt-4o-mini"
                   :goal "Create function"
                   :instructions [(instr-util/user-data-instructions-path "clojure.instructions.md")]
                   :context-file-paths [(instr-util/user-data-instructions-path "clojure-memory.instructions.md")]
                   :max-turns 10
                   :tool-ids ["copilot_readFile"]
                   :conv-id conv-id
                   :progress-callback #()})]
    result)

  :rcf)
