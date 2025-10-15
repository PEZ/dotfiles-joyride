(ns ai-workflow.agent-monitor
  "Agent conversation monitoring with flare UI and output channel logging"
  (:require
   ["vscode" :as vscode]
   [joyride.flare :as flare]
   [promesa.core :as p]))

;; State Management

(defonce !agent-state
  (atom {:agent/conversations {}
         :agent/next-id 1
         :agent/output-channel nil}))

;; Output Channel Management

(defn get-output-channel!
  "Get or create the Sub Agents output channel"
  []
  (or (:agent/output-channel @!agent-state)
      (let [channel (vscode/window.createOutputChannel "Sub Agents")]
        (swap! !agent-state assoc :agent/output-channel channel)
        channel)))

(defn log-to-agent-channel!
  "Log a message to the agent output channel with conversation ID prefix"
  [conv-id message]
  (let [channel (get-output-channel!)
        prefixed-msg (str "[" conv-id "] " message)]
    (.appendLine channel prefixed-msg)))

;; Conversation Management

(defn register-conversation!
  "Register a new conversation and return its ID"
  [{:agent.conversation/keys [goal model-id max-turns caller]}]
  (let [id (:agent/next-id @!agent-state)
        conversation {:agent.conversation/id id
                      :agent.conversation/goal goal
                      :agent.conversation/caller (or caller "Unknown")
                      :agent.conversation/model-id model-id
                      :agent.conversation/max-turns max-turns
                      :agent.conversation/status :started
                      :agent.conversation/started-at (js/Date.)
                      :agent.conversation/updated-at (js/Date.)
                      :agent.conversation/current-turn 0
                      :agent.conversation/error-message nil}]
    (swap! !agent-state
           (fn [state]
             (-> state
                 (assoc-in [:agent/conversations id] conversation)
                 (update :agent/next-id inc))))
    id))

(defn update-conversation!
  "Update conversation status and metadata"
  [id updates]
  (swap! !agent-state
         (fn [state]
           (update-in state
                      [:agent/conversations id]
                      merge
                      (assoc updates :agent.conversation/updated-at (js/Date.))))))

(defn get-conversation
  "Get conversation by ID"
  [id]
  (get-in @!agent-state [:agent/conversations id]))

(defn get-all-conversations
  "Get all conversations"
  []
  (-> @!agent-state :agent/conversations vals))

;; UI Rendering

(defn status-icon
  "Get icon for conversation status"
  [status]
  (case status
    :started "⏸️"
    :working "⚙️"
    :done "✅"
    :error "❌"
    "❓"))

(defn format-time
  "Format JS Date to HH:MM"
  [js-date]
  (let [hours (.getHours js-date)
        minutes (.getMinutes js-date)
        pad (fn [n] (if (< n 10) (str "0" n) (str n)))]
    (str (pad hours) ":" (pad minutes))))

(defn conversation-html
  "Generate HTML for a single conversation entry"
  [conv]
  (let [{:agent.conversation/keys [id goal status model-id caller
                                    current-turn max-turns
                                    started-at error-message]} conv
        icon (status-icon status)
        time-str (format-time started-at)]
    [:div {:style {:border "1px solid var(--vscode-panel-border)"
                   :padding "8px"
                   :margin "4px 0"
                   :border-radius "4px"}}
     [:div {:style {:display :flex
                    :justify-content :space-between
                    :align-items :center
                    :margin-bottom "4px"}}
      [:span {:style {:font-weight :bold}}
       (str icon " [" id "] " (name status))]
      [:span {:style {:font-size "0.9em" :opacity "0.7"}}
       time-str]]
     [:div {:style {:font-size "0.9em" :margin-bottom "4px"}}
      [:strong "Caller: "] caller " | "
      [:strong "Model: "] model-id " | "
      [:strong "Turn: "] current-turn "/" max-turns]
     [:div {:style {:max-height "60px"
                    :overflow-y :auto
                    :font-size "0.9em"
                    :padding "4px"
                    :background "var(--vscode-editor-background)"
                    :border-radius "2px"}}
      goal]
     (when error-message
       [:div {:style {:color "var(--vscode-errorForeground)"
                      :font-size "0.85em"
                      :margin-top "4px"}}
        "Error: " error-message])]))

(defn agent-monitor-html
  "Generate complete monitor HTML"
  []
  (let [conversations (get-all-conversations)
        sorted-convs (reverse (sort-by :agent.conversation/id conversations))]
    [:div {:style {:padding "10px"}}
     [:div {:style {:display :flex
                    :justify-content :space-between
                    :align-items :center
                    :margin-bottom "10px"}}
      [:h2 {:style {:margin "0"}} "🤖 Sub Agents Monitor"]
      [:button {:onclick "showAgentLogs()"
                :style {:padding "4px 8px"
                        :background "var(--vscode-button-background)"
                        :color "var(--vscode-button-foreground)"
                        :border :none
                        :border-radius "2px"
                        :cursor :pointer}}
       "📋 Show Logs"]]
     [:div {:style {:margin-top "10px"}}
      (if (empty? sorted-convs)
        [:p {:style {:font-style :italic :opacity "0.7"}}
         "No active conversations"]
        (into [:div] (map conversation-html sorted-convs)))]]))

;; Flare Management

(defn update-agent-monitor-flare!+
  "Update the agent monitor flare in sidebar"
  []
  (flare/flare!+
   {:key :sidebar-1
    :title "Sub Agents"
    :html (agent-monitor-html)
    :message-handler
    (fn [msg]
      (when (= (.-command msg) "showLogs")
        (.show (get-output-channel!))))}))

;; Public API for Integration

(defn start-monitoring-conversation!+
  "Start monitoring a conversation - registers it and updates flare"
  [{:agent.conversation/keys [goal model-id max-turns caller]}]
  (let [conv-id (register-conversation!
                 {:agent.conversation/goal goal
                  :agent.conversation/model-id model-id
                  :agent.conversation/max-turns max-turns
                  :agent.conversation/caller caller})]
    (log-to-agent-channel! conv-id (str "🚀 Starting conversation: " goal))
    (p/let [_ (update-agent-monitor-flare!+)]
      conv-id)))

(defn log-and-update!+
  "Log a message and optionally update conversation status"
  ([conv-id message]
   (log-and-update!+ conv-id message nil))
  ([conv-id message status-updates]
   (log-to-agent-channel! conv-id message)
   (when status-updates
     (update-conversation! conv-id status-updates))
   (update-agent-monitor-flare!+)))

(comment
  ;; Test the monitor

  ;; Create first conversation
  (p/let [conv-id (start-monitoring-conversation!+
                   {:agent.conversation/goal "Test workflow with flare updates"
                    :agent.conversation/model-id "gpt-4o-mini"
                    :agent.conversation/max-turns 5
                    :agent.conversation/caller "repl-test"})]
    (def test-conv-id conv-id)
    (println "Started conversation" conv-id))

  ;; Update progress
  (p/let [_ (log-and-update!+ test-conv-id
                              "Turn 1/5"
                              {:agent.conversation/status :working
                               :agent.conversation/current-turn 1})]
    (println "Updated to working"))

  ;; Add more activity
  (p/let [_ (log-and-update!+ test-conv-id "🤖 AI Agent says: Analyzing..." nil)
          _ (log-and-update!+ test-conv-id "🔧 Executing tools..." nil)]
    (println "Logged activity"))

  ;; Complete conversation
  (p/let [_ (log-and-update!+ test-conv-id
                              "✅ Task completed!"
                              {:agent.conversation/status :done
                               :agent.conversation/current-turn 5})]
    (println "Conversation completed"))

  ;; Create error conversation
  (p/let [conv-id (start-monitoring-conversation!+
                   {:agent.conversation/goal "Task that encounters error"
                    :agent.conversation/model-id "claude-sonnet-4"
                    :agent.conversation/max-turns 10
                    :agent.conversation/caller "error-test"})
          _ (log-and-update!+ conv-id
                              "❌ Error: Model not found"
                              {:agent.conversation/status :error
                               :agent.conversation/current-turn 3
                               :agent.conversation/error-message "Model not found"})]
    (println "Error conversation created"))

  ;; Check state
  @!agent-state

  ;; Get all conversations
  (get-all-conversations)

  ;; Manually update flare
  (update-agent-monitor-flare!+)

  :rcf)
