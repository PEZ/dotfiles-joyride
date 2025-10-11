(ns ai-workflow.agents
  "Autonomous AI conversation system with improved error handling and adaptability"
  (:require
   ["vscode" :as vscode]
   [ai-workflow.chat-util :as util]
   [clojure.edn :as edn]
   [promesa.core :as p]))

(def agentic-system-prompt
  "You are an autonomous AI agent with the ability to take initiative and drive conversations toward goals.

Use the `ai-chat.human-intelligence/ask!+` function to ask questions:

```clojure
(ai-chat.human-intelligence/ask!+ question question-context items timeout-s)
The question will be shown as a quick-pick, and returns a promise
which resolves to the answer the human picks. All arguments must be provided.
   `question` is used for the title of the quick-pick
   `question-context` is used for placeholder
   `items` can be strings or js maps with quickpick item things. E.g. `#js {:label \"baz\" :description \"elaborate on baz\"}`
```

If you get errors like “Function.prototype.apply was called on [object Array], which is an object and not a function“, you can try not using a function call. E.g `vscode/workspace.workspaceFolders` instead of `(vscode/workspace.workspaceFolders)`.

AGENTIC BEHAVIOR RULES:
1. When given a goal, break it down into steps and execute them
2. Use available tools proactively to gather information or take actions
3. After each tool use, analyze the results and decide your next action
4. If a tool returns unexpected results or fails, ADAPT your approach - don't repeat the same action
5. Continue working toward the goal asking for input if needed (using the `ask!+` function)
6. Provide progress updates as you work
7. Ask for clarification when necessary (using the `ask!+` function)
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
    goal - String describing the task (immutable, never stored in history)

  Returns:
    Vector of messages formatted for LM API with goal first, followed by conversation"
  [history goal]
  (let [goal-message {:role :user
                      :content (str "GOAL: " goal
                                    "\n\nPlease work autonomously toward this goal. "
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
  "Check if AI agent indicates the task is complete"
  [ai-text]
  (when ai-text
    (re-find #"(?i)(~~~GOAL-ACHIEVED~~~|task.*(?!\bnot\b).*(complete|done|finished)|goal.*(?!\bnot\b).*(achieved|reached|accomplished)|mission.*(?!\bnot\b).*(complete|success)|successfully (completed|finished))" ai-text)))

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
  [{:keys [model-id goal history turn-count tools-args]}]
  (p/catch
   (p/let [messages (build-agentic-messages history goal)
           response (util/send-prompt-request!+
                     {:model-id model-id
                      :system-prompt agentic-system-prompt
                      :messages messages
                      :options tools-args})
           result (util/collect-response-with-tools!+ response)]
     (assoc result :turn turn-count))
   (fn [error]
     {:message (.-message error)
      :turn turn-count})))

(defn execute-tools-if-present!+
  "Execute tool calls if present, return updated history"
  [history tool-calls turn-count]
  (if (seq tool-calls)
    (do
      (println "\n🔧 AI Agent executing" (count tool-calls) "tool(s)")
      (p/let [tool-results (util/execute-tool-calls!+ tool-calls)]
        (println "✅ Tools executed, processed results:" tool-results)
        (add-tool-results history tool-results turn-count)))
    history))

(defn- generate-call-id
  "Generate a unique call ID using random number and timestamp"
  []
  (let [random-part (.toString (.floor js/Math (* (.random js/Math) 1000000)))
        timestamp-part (.toString (.now js/Date))]
    (str "call-" random-part "-" timestamp-part)))

(defn- extract-tool-call-edn
  "Extract EDN strings from tool call blocks in text"
  [text]
  (let [pattern #"BEGIN-TOOL-CALL\s*\n(.*?)\nEND-TOOL-CALL"]
    (->> (re-seq pattern text)
         (map second))))

(defn- parse-tool-calls
  "Parse tool calls from text and augment each with a unique :callId"
  [text unique-id]
  (->> text
       extract-tool-call-edn
       (map edn/read-string)
       (mapv (fn [i tool] (assoc tool :callId (str unique-id "-" i)))
             (range))
       (clj->js)))

(defn continue-conversation-loop
  "Main conversation loop.

  The goal parameter is passed separately through all turns and combined with history
  by build-agentic-messages. This keeps the immutable goal separate from the growing
  conversation history."
  [{:keys [model-id goal max-turns progress-callback tools-args]} history turn-count last-response]
  (progress-callback (str "Turn " turn-count "/" max-turns))

  (if (> turn-count max-turns)
    (format-completion-result history :max-turns-reached last-response)

    (p/let [;; Execute the conversation turn
            turn-result (execute-conversation-turn
                         {:model-id model-id
                          :goal goal
                          :history history
                          :turn-count turn-count
                          :tools-args tools-args})

            ;; Check for errors first
            _ (when (:error turn-result)
                (throw (js/Error. (:message turn-result))))

            ai-text (:text turn-result)
            tool-calls (or (seq (:tool-calls turn-result))
                           (parse-tool-calls ai-text (generate-call-id)))

            ;; Log AI's response
            _ (when ai-text
                (println "\n🤖 AI Agent says:")
                (println ai-text))

            ;; Add AI response to history
            history-with-assistant (add-assistant-response history ai-text tool-calls turn-count)

            ;; Execute tools and update history
            final-history (execute-tools-if-present!+ history-with-assistant tool-calls turn-count)

            ;; Determine what to do next
            outcome (determine-conversation-outcome ai-text tool-calls turn-count max-turns)]

      (if (:continue? outcome)
        (do
          (println "\n↻ AI Agent continuing to next step...")
          (continue-conversation-loop
           {:model-id model-id :goal goal :max-turns max-turns
            :progress-callback progress-callback :tools-args tools-args}
           final-history
           (inc turn-count)
           turn-result))
        (do
          (println "\n🎯 Agentic conversation ended:" (name (:reason outcome)))
          (format-completion-result final-history (:reason outcome) turn-result))))))

(defn agentic-conversation!+
  "Create an autonomous AI conversation that drives itself toward a goal"
  [{:keys [model-id goal tool-ids max-turns progress-callback allow-unsafe-tools?]
    :or {max-turns 10
         allow-unsafe-tools? false
         progress-callback (fn [step]
                             (println "Progress:" step))}}]
  (p/let [tools-args (util/enable-specific-tools tool-ids allow-unsafe-tools?)
          model-info (util/get-model-by-id!+ model-id)]
    (if-not model-info
      {:history []
       :error? true
       :reason :model-not-found-error
       :error-message (str "Model not found: " model-id)
       :final-response nil}
      (do
        (println "🚀 Starting agentic conversation with goal:" goal)
        (continue-conversation-loop
         {:model-id model-id
          :goal goal
          :max-turns max-turns
          :progress-callback progress-callback
          :tools-args tools-args}
         [] ; empty initial history
         1  ; start at turn 1
         nil)))))

(defn autonomous-conversation!+
  "Start an autonomous AI conversation toward a goal with flexible configuration"
  ([goal]
   (autonomous-conversation!+ goal
                              {}))

  ([goal {:keys [model-id max-turns tool-ids progress-callback allow-unsafe-tools?]
          :or {model-id "gpt-4o-mini"
               tool-ids []
               max-turns 6
               allow-unsafe-tools? false
               progress-callback #(println % "\n")}}]

   (p/let [result (agentic-conversation!+
                   {:model-id model-id
                    :goal goal
                    :max-turns max-turns
                    :tool-ids tool-ids
                    :allow-unsafe-tools? allow-unsafe-tools?
                    :progress-callback progress-callback})]
     ;; Check for model error first
     (if (:error? result)
       (do
         (progress-callback (str "❌ Model error: " (:error-message result)))
         result)
       ;; Show final summary with proper turn counting
       (let [actual-turns (count (filter #(= (:role %) :assistant) (:history result)))
             summary (str "🎯 Agentic task "
                          (case (:reason result)
                            :task-complete "COMPLETED successfully!"
                            :max-turns-reached "reached max turns"
                            :agent-finished "finished"
                            "ended unexpectedly")
                          " (" actual-turns " turns, " (count (:history result)) " conversation steps)")]
         (progress-callback summary)
         result)))))

(comment

  (require '[ai-workflow.ui :as ui])
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

  (autonomous-conversation!+ "Count all .cljs files and show the result"
                             {:tool-ids use-tool-ids})
  (autonomous-conversation!+ "Show an information message that says 'Hello from the adaptive AI agent!' using VS Code APIs"
                             {:tool-ids ["joyride_evaluate_code"
                                         "copilot_getVSCodeAPI"]
                              :max-turns 4})

  (autonomous-conversation!+ (str "Analyze this project structure and create documentation. For tool calls use this syntax: \n\nBEGIN-TOOL-CALL\n{:name \"tool_name\", :input {:someParam \"value\", :someOtherParam [\"value\" 42]}}\nEND-TOOL-CALL\n\nThe result of the tool calls will be provided to you as part of the next step. Keep each step laser focused."
                                  "\nAvailable tools: "
                                  (pr-str use-tool-ids))
                             {:model-id "claude-opus-4"
                              :max-turns 12
                              :progress-callback vscode/window.showInformationMessage
                              :tool-ids use-tool-ids})


  (autonomous-conversation!+ (str "Analyze this project structure and create documentation. Keep each step laser focused."
                                  #_#_"\nAvailable tools: "
                                  (pr-str use-tool-ids))
                             {:model-id "claude-sonnet-4"
                              :max-turns 12
                              :progress-callback vscode/window.showInformationMessage
                              :tool-ids use-tool-ids})

  (autonomous-conversation!+ "Create a file docs/greeting.md with a greeting to Clojurians"
                             {:model-id "claude-sonnet-4"
                              :max-turns 15
                              :progress-callback (fn [step]
                                                   (println "🔄" step)
                                                   (vscode/window.showInformationMessage step))
                              :tool-ids use-tool-ids})

  (autonomous-conversation!+ "Generate the four first numbers in the fibonacci sequence without writing a function, but instead by starting with evaluating `[0 1]` and then each step read the result and evaluate `[second-number sum-of-first-and-second-number]`. In the last step evaluate just `second-number`."
                             {:model-id "claude-sonnet-4"
                              :max-turns 12
                              :progress-callback (fn [step]
                                                   (println "🔄" step)
                                                   (vscode/window.showInformationMessage step))
                              :tool-ids ["joyride_evaluate_code"]})

  (def example-joyride-tool-call {:callId "foo"
                                  :name "joyride_evaluate_code"
                                  :input {:code "(vscode/window.showInformationMessage \"hello\")"}})

  (def example-tool-call {:name "tool_name"
                          :input {:someParam "value"
                                  :someOtherParam ["value" 42]}})

  (-> example-tool-call
      pr-str
      clojure.edn/read-string)

  (autonomous-conversation!+ "print a greeting using the joyride repl. For tool calls use this syntax: \n\nBEGIN-TOOL-CALL\n{:name \"tool_name\", :input {:someParam \"value\", :someOtherParam [\"value\" 42]}}\nEND-TOOL-CALL\n\nThe results from the tool call will be provided to you as part of the next step."
                             {:model-id "claude-opus-4"
                              :max-turns 2
                              :progress-callback (fn [step]
                                                   (println "🔄" step)
                                                   (vscode/window.showInformationMessage step))
                              :tool-ids ["joyride_evaluate_code"
                                         "copilot_getVSCodeAPI"]})

  (autonomous-conversation!+ "print a greeting using the joyride repl. For tool calls use this syntax: \n\n<Tool>\n<tool_name>...</tool_name>\n<parameters>\n<some-param>...</some-param>\n<some-other-param>...</some-other-param>\n</parameters>\n</Tool>\n\nThe results from the tool call will be provided to you as part of the next step."
                             {:model-id "claude-opus-4"
                              :max-turns 4
                              :progress-callback (fn [step]
                                                   (println "🔄" step)
                                                   (vscode/window.showInformationMessage step))
                              :tool-ids ["joyride_evaluate_code"
                                         "copilot_getVSCodeAPI"]})
  :rcf)