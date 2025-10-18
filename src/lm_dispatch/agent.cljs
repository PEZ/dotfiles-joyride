; AGENTS, please:
; - remember interactive programming
; - consider TDD in the repl, existing tests: src/test/lm_dispatch/agent_test.cljs
; - prefer your structural editing tools

(ns lm-dispatch.agent
  "Autonomous AI conversation system"
  (:require
   ["path" :as path]
   ["vscode" :as vscode]
   [agents.instructions-selector :as selector]
   [clojure.string :as string]
   [lm-dispatch.instructions-util :as instr-util]
   [lm-dispatch.state :as state]
   [lm-dispatch.logging :as logging]
   [lm-dispatch.monitor :as monitor]
   [lm-dispatch.util :as util]
   [promesa.core :as p]))

; To run all tests:
#_(do (require 'run-all-tests :reload) (run-all-tests/run!+))

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
  [{:keys [model-id goal instructions max-turns progress-callback tools-args conv-id] :as conversation-data}
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
  "Create an autonomous AI conversation that drives itself toward a goal"
  [{:keys [model-id tool-ids allow-unsafe-tools? conv-id] :as conversation-data}]
  (p/let [tools-args (util/enable-specific-tools tool-ids allow-unsafe-tools?)
          model-info (util/get-model-by-id!+ model-id)]
    (if-not model-info
      {:history []
       :error? true
       :reason :model-not-found-error
       :error-message (str "Model not found: " model-id)
       :final-response nil}
      (p/let [;; Create cancellation token and store it in conversation
              cancellation-token-source (vscode/CancellationTokenSource.)
              _ (state/update-conversation! conv-id {:agent.conversation/cancellation-token-source cancellation-token-source})
              result (continue-conversation-loop
                      (merge conversation-data
                             {:tools-args (assoc tools-args :cancellationToken (.-token cancellation-token-source))})
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

(defn concatenate-instruction-files!+
  "Slurp instruction files and concatenate with separators.

  Args:
    file-paths - Vector of absolute file paths

  Returns: Promise of concatenated string with '# From: filename' separators"
  [file-paths]
  (if (empty? file-paths)
    (p/resolved "")
    (p/let [contents (p/all (for [file-path file-paths]
                              (p/let [content (instr-util/read-file-content!+ file-path)
                                      filename (path/basename file-path)]
                                {:filename filename
                                 :content (or content "")})))]
      (clojure.string/join
       "\n\n"
       (for [{:keys [filename content]} contents]
         (str "# From: " filename "\n\n" content))))))

(defn collect-all-instruction-descriptions!+
  "Collect instruction file descriptions from both workspace and global areas.

  Returns: Promise of vector of {:file :filename :description :domain} maps"
  []
  (p/let [;; Try workspace instructions (might not exist)
          workspace-descriptions (p/catch
                                  (p/let [ws-path (instr-util/workspace-instructions-path)]
                                    (instr-util/build-file-descriptions-map!+ ws-path))
                                  (fn [_] []))
          ;; Get global user instructions
          user-descriptions (instr-util/build-file-descriptions-map!+
                             (instr-util/user-data-instructions-path))]
    ;; Combine both, workspace first
    (vec (concat workspace-descriptions user-descriptions))))

(defn prepare-instructions-with-selection!+
  "Prepare instructions by selecting relevant files and concatenating with context files.

  Args:
    goal - The task goal to match instructions against
    context-files - Vector of file paths to include as additional context

  Returns: Promise of concatenated instructions string"
  [{:keys [goal context-files]}]
  (p/let [;; Step 1: Collect all available instruction descriptions
          all-descriptions (collect-all-instruction-descriptions!+)

          ;; Step 2: Slurp context files if provided (for selector prompt)
          context-content (when (seq context-files)
                            (concatenate-instruction-files!+ context-files))

          ;; Step 3: Dispatch selector to choose relevant instructions
          selected-paths (selector/select-instructions!+
                          {:goal goal
                           :file-descriptions all-descriptions
                           :context-content context-content})

          ;; Step 4: Slurp selected instruction files
          selected-content (concatenate-instruction-files!+ selected-paths)

          ;; Step 5: Slurp context files again (to append after selected)
          final-context-content (if (seq context-files)
                                  (concatenate-instruction-files!+ context-files)
                                  "")

          ;; Step 6: Concatenate with separator
          final-instructions (if (seq final-context-content)
                               (str selected-content
                                    (when (seq selected-content) "\n\n")
                                    "# === Context Files ===\n\n"
                                    final-context-content)
                               selected-content)]
    final-instructions))

(defn autonomous-conversation!+
  "Start an autonomous AI conversation toward a goal with flexible configuration.

  Options:
    :model-id - LM model ID (default: gpt-4o-mini)
    :max-turns - Maximum conversation turns (default: 10)
    :tool-ids - Vector of tool IDs to enable (default: [])
    :progress-callback - Function called with progress updates (default: no-op)
    :allow-unsafe-tools? - Allow file write operations (default: false)
    :caller - String identifying who started the conversation
    :title - Display title for the conversation
    :use-instruction-selection? - Enable automatic instruction file selection (default: false)
    :context-files - Vector of file paths to include as additional context (default: [])"
  ([goal]
   (autonomous-conversation!+ goal
                              {}))

  ([goal {:keys [model-id max-turns tool-ids progress-callback allow-unsafe-tools? caller title
                 use-instruction-selection? context-files]
          :or {model-id "gpt-4o-mini"
               tool-ids []
               max-turns 10
               allow-unsafe-tools? false
               use-instruction-selection? false
               context-files []}}]

   (p/let [conv-id (monitor/start-monitoring-conversation!+
                    (cond-> {:agent.conversation/goal goal
                             :agent.conversation/model-id model-id
                             :agent.conversation/max-turns max-turns
                             :agent.conversation/caller caller
                             :agent.conversation/title title}))
           progress-callback (or progress-callback
                                 #())
           ;; Prepare instructions based on selection setting
           instructions (if use-instruction-selection?
                          (prepare-instructions-with-selection!+ {:goal goal
                                                                  :context-files context-files})
                          ;; Default simple instructions, with context-files if provided
                          (if (seq context-files)
                            (p/let [context-content (concatenate-instruction-files!+ context-files)]
                              (str "Go, go, go!\n\n# === Context Files ===\n\n" context-content))
                            (p/resolved "Go, go, go!")))
           result (agentic-conversation!+
                   {:model-id model-id
                    :goal goal
                    :instructions instructions
                    :max-turns max-turns
                    :tool-ids tool-ids
                    :allow-unsafe-tools? allow-unsafe-tools?
                    :caller caller
                    :conv-id conv-id
                    :progress-callback progress-callback})]
     ;; Check for model error first
     (if (:error? result)
       (do
         (logging/log-to-channel! conv-id (str "❌ Model error: " (:error-message result)))
         result)
       ;; Show final summary with proper turn counting
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

  (require '[lm-dispatch.ui :as ui])
  (p/let [use-tool-ids (ui/tools-picker+ ["joyride_evaluate_code"
                                          "copilot_searchCodebase"
                                          "copilot_searchWorkspaceSymbols"
                                          "copilot_listCodeUsages"
                                          "copilot_getVSCodeAPI"
                                          "copilot_findFiles"
                                          "copilot_findTextInFiles"
                                          "copilot_readFile"
                                          "copilot_listDirectory"
                                          "copilot_insertEdit"
                                          "copilot_createFile"])]
    (def use-tool-ids (set use-tool-ids))
    (println (pr-str use-tool-ids) "\n"))

  (p/let [fib-res (autonomous-conversation!+ "# Fibonacci Generator
Generate the nine first numbers in the fibonacci sequence without writing a function, but instead by starting with evaluating `[0 1]` and then each step read the result and evaluate `[second-number sum-of-first-and-second-number]`. In the last step evaluate just `second-number`."
                                             {:model-id "grok-code-fast-1"
                                              :caller "Mr Clojurian"
                                              :title "Expensive fibs"
                                              :max-turns 12
                                              :tool-ids ["joyride_evaluate_code"]})]
    (def fib-res fib-res))

  (autonomous-conversation!+ "Count all .cljs files and show the result"
                             {:title "Power Counter"
                              :tool-ids use-tool-ids
                              :caller "repl-file-counter"})

  (autonomous-conversation!+ "Show an information message that says 'Hello from the adaptive AI agent!' using VS Code APIs"
                             {:tool-ids ["joyride_evaluate_code"
                                         "copilot_getVSCodeAPI"]
                              :caller "repl-greeting-test"
                              :max-turns 4})


  (autonomous-conversation!+ (str "Analyze this project structure and create documentation. For tool calls use this syntax: \n\nBEGIN-TOOL-CALL\n{:name \"tool_name\", :input {:someParam \"value\", :someOtherParam [\"value\" 42]}}\nEND-TOOL-CALL\n\nThe result of the tool calls will be provided to you as part of the next step. Keep each step laser focused."
                                  "\nAvailable tools: "
                                  (pr-str use-tool-ids))
                             {:title "Project Summarizer"
                              :model-id "claude-sonnet-4.5"
                              :max-turns 15
                              :caller "Mr Clojurian"
                              :tool-ids use-tool-ids})

  (autonomous-conversation!+ "Analyze the lm_dispatch system in this workspace, by reading the code and using the repl, and create documentation."
                             #_#_"\nAvailable tools: "
                               (pr-str use-tool-ids)
                             {:title "Project Summarizer"
                              :model-id "claude-sonnet-4.5"
                              :caller "Mr Clojurian"
                              :max-turns 25
                              :tool-ids use-tool-ids})

  (autonomous-conversation!+ "Create a file docs/greeting.md with a greeting to Clojurians"
                             {:model-id "claude-sonnet-4"
                              :max-turns 15
                              :progress-callback (fn [step]
                                                   (println "🔄" step)
                                                   (vscode/window.showInformationMessage step))
                              :tool-ids use-tool-ids})

  (def example-joyride-tool-call {:callId "foo"
                                  :name "joyride_evaluate_code"
                                  :input {:code "(vscode/window.showInformationMessage \"hello\")"}})

  :rcf)