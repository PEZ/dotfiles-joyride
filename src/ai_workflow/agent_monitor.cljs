(ns ai-workflow.agent-monitor
  "Agent conversation monitoring with flare UI and output channel logging"
  (:require
   ["vscode" :as vscode]
   [clojure.string :as string]
   [joyride.flare :as flare]
   [promesa.core :as p]))

;; State Management

(defonce !agent-state
  (atom {:agent/conversations {}
         :agent/next-id 1
         :agent/output-channel nil
         :agent/sidebar-slot nil}))
(swap! !agent-state update :agent/conversations dissoc 4 5 6 7 8 9)

;; Output Channel Management

(defn get-output-channel!
  "Get or create the Sub Agents output channel"
  []
  (or (:agent/output-channel @!agent-state)
      (let [channel (vscode/window.createOutputChannel "Sub Agents")]
        (swap! !agent-state assoc :agent/output-channel channel)
        channel)))

(defn log-to-agent-channel!
  "Log a message to the agent output channel with conversation ID prefix on all lines"
  [conv-id message]
  (let [channel (get-output-channel!)
        lines (string/split (str message) #"\r?\n")
        prefix (str "[" conv-id "] ")]
    (doseq [line lines]
      (.appendLine channel (str prefix line)))))

;; Conversation Management

(defn register-conversation!
  "Register a new conversation and return its ID"
  [conversation-data]
  (let [id (:agent/next-id @!agent-state)
        conversation (merge conversation-data
                           {:agent.conversation/id id
                            :agent.conversation/status :started
                            :agent.conversation/started-at (js/Date.)
                            :agent.conversation/updated-at (js/Date.)
                            :agent.conversation/current-turn 0
                            :agent.conversation/error-message nil})]
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
  "Get codicon class for conversation status"
  [status]
  (case status
    :started "codicon-debug-pause"
    :working "codicon-loading codicon-modifier-spin"
    :done "codicon-pass"
    :error "codicon-error"
    "codicon-question"))

(defn format-time
  "Format JS Date to HH:MM"
  [js-date]
  (if js-date
    (let [hours (.getHours js-date)
          minutes (.getMinutes js-date)
          pad (fn [n] (if (< n 10) (str "0" n) (str n)))]
      (str (pad hours) ":" (pad minutes)))
    "--:--"))

(defn conversation-html
  "Generate HTML for a single conversation entry"
  [conv]
  (let [{:agent.conversation/keys [id goal status model-id caller
                                   current-turn max-turns
                                   started-at error-message]} conv
        icon-class (status-icon status)
        icon-color (case status
                     :done "var(--vscode-charts-green)"
                     :error "var(--vscode-charts-red)"
                     :working "var(--vscode-charts-blue)"
                     nil)
        time-str (format-time started-at)]
    [:div {:style {:border "1px solid var(--vscode-panel-border)"
                   :padding "8px"
                   :margin "4px 0"
                   :border-radius "4px"}}
     [:div {:style {:display :flex
                    :justify-content :space-between
                    :align-items :center
                    :margin-bottom "4px"}}
      [:span {:style {:font-weight :bold
                      :display :flex
                      :align-items :center
                      :gap "4px"}}
       [:i {:class (str "codicon " icon-class)
            :style (merge {:padding-top "2px"}
                          (when icon-color
                            {:color icon-color}))}]
       (str "[" id "] ") [:strong "Turn: "] current-turn "/" max-turns]
      [:span {:style {:font-size "0.9em" :opacity "0.7"}}
       time-str]]
     [:div {:style {:font-size "0.9em" :margin-bottom "4px"}}
      [:strong "Caller: "] caller " | "
      [:strong "Model: "] model-id]
     [:div {:style {:max-height "120px"
                    :overflow-y :auto
                    :font-size "0.9em"
                    :font-family "var(--vscode-editor-font-family)"
                    :white-space :pre-wrap
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
    [:div {:style {:padding 0}}
     [:style "body { margin: 0; padding: 0;}"]
     [:link {:rel "stylesheet"
             :href "https://unpkg.com/@vscode/codicons@latest/dist/codicon.css"}]
     [:script {:type "text/javascript"}
      "const vscode = acquireVsCodeApi();"]
     [:div {:style {:display :flex
                    :justify-content :space-between
                    :align-items :center
                    :margin-bottom "10px"
                    :padding "0 8px"}}
      [:h2 {:style {:margin "0"}} "Sub Agents Monitor"]
      [:button {:onclick "vscode.postMessage({command: 'showLogs'})"
                :style {:padding "4px 8px"
                        :background "var(--vscode-button-background)"
                        :color "var(--vscode-button-foreground)"
                        :border :none
                        :border-radius "2px"
                        :cursor :pointer}}
       "Show Logs"]]
     [:div {:style {:margin-top "10px"
                    :padding "0 8px"}}
      (if (empty? sorted-convs)
        [:p {:style {:font-style :italic :opacity "0.7"}}
         "No active conversations"]
        (into [:div] (map conversation-html sorted-convs)))]]))

;; Flare Management

(defn ensure-sidebar-slot! []
  (if-let [slot (:agent/sidebar-slot @!agent-state)]
    slot
    (let [free-slots (apply disj
                            #{:sidebar-1 :sidebar-2 :sidebar-3 :sidebar-4 :sidebar-5}
                            (vec (keys (flare/ls))))]
      (when (seq free-slots)
        (let [new-slot (first free-slots)]
          (swap! !agent-state assoc :agent/sidebar-slot new-slot)
          new-slot)))))

(defn update-agent-monitor-flare!+
  "Update the agent monitor flare in sidebar"
  []
  (when-let [slot (ensure-sidebar-slot!)]
    (flare/flare!+
     {:key slot
      :title "Sub Agents"
      :html (agent-monitor-html)
      :reveal? false
      :message-handler (fn [msg]
                         (when (= (.-command msg) "showLogs")
                           (.show (get-output-channel!))))})))

;; Public API for Integration

(defn start-monitoring-conversation!+
  "Start monitoring a conversation - registers it and updates flare"
  [{:agent.conversation/keys [goal caller] :as conversation-data}]
  (let [conversation-data (assoc conversation-data
                                 :agent.conversation/caller
                                 (or caller "Unknown"))
        conv-id (register-conversation! conversation-data)]
    (log-to-agent-channel! conv-id (str "🚀 Starting conversation: " goal))
    (p/let [_ (update-agent-monitor-flare!+)]
      conv-id)))

(defn log-and-update!+
  "Log messages and optionally update conversation status.
   Accepts variadic messages for compatibility with `println`."
  [conv-id status-updates & messages]
  (log-to-agent-channel! conv-id (string/join " " messages))
  (when status-updates
    (update-conversation! conv-id status-updates))
  (update-agent-monitor-flare!+))

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
