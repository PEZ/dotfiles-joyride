;; AGENTS, please read this preamble before working with the namespace:
;; - Use interactive programming
;; - Work using TDD in the repl, existing tests: src/test/lm_dispatch/monitor_test.cljs
;; - Always prefer your structural editing tools

(ns lm-dispatch.monitor
  "Presentation layer for agent dispatch monitoring.

  Renders conversation UI in sidebar flare and handles user interactions."
  (:require
   [lm-dispatch.state :as state]
   [lm-dispatch.logging :as logging]
   [clojure.string :as string]
   [joyride.flare :as flare]
   [promesa.core :as p]))

;; To run all tests:
#_(do (require 'run-all-tests :reload) (run-all-tests/run!+))

;; UI Interaction Handlers

(defn cancel-conversation!
  "Handle cancel button click - cancels token and marks conversation cancelled"
  [conv-id]
  (when-let [conv (state/get-conversation conv-id)]
    (logging/log-to-channel! conv-id "🛑 Cancellation requested")
    (when-let [token-source (:agent.conversation/cancellation-token-source conv)]
      (.cancel token-source))
    (state/mark-conversation-cancelled! conv-id)))

;; UI Rendering

(defn status-icon
  "Get codicon class for conversation status"
  [status]
  (case status
    :started "codicon-debug-pause"
    :working "codicon-loading codicon-modifier-spin"
    :cancel-requested "codicon-loading codicon-modifier-spin"
    :task-complete "codicon-check"
    :max-turns-reached "codicon-clock"
    :agent-finished "codicon-info"
    :done "codicon-pass"
    :error "codicon-error"
    :cancelled "codicon-debug-stop"
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
  (let [{:agent.conversation/keys [id goal status model-id
                                   caller title
                                   current-turn max-turns
                                   started-at error-message
                                   total-tokens]} conv
        icon-class (status-icon status)
        icon-color (case status
                     :task-complete "var(--vscode-charts-green)"
                     :max-turns-reached "var(--vscode-charts-yellow)"
                     :agent-finished "var(--vscode-charts-blue)"
                     :done "var(--vscode-charts-green)"
                     :error "var(--vscode-charts-red)"
                     :working "var(--vscode-charts-blue)"
                     :cancel-requested "var(--vscode-charts-orange)"
                     :cancelled "var(--vscode-charts-orange)"
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
                      :gap "4px"
                      :flex "1"
                      :min-width "0"
                      :overflow :hidden
                      :text-overflow :ellipsis
                      :white-space :nowrap}}
       [:i {:class (str "codicon " icon-class)
            :style (merge {:padding-top "2px"
                           :flex-shrink "0"}
                          (when icon-color
                            {:color icon-color}))}]
       [:span {:style {:overflow :hidden
                       :text-overflow :ellipsis}}
        (str "[" id "] ")
        [:span {:style {:color "var(--vscode-charts-foreground)"}}
         title]]]
      [:div {:style {:display :flex
                     :align-items :center
                     :gap "4px"}}
       (when (#{:working :started} status)
         [:button {:onclick (str "vscode.postMessage({command: 'cancel', id: " id "})")
                   :style {:padding "2px 4px"
                           :background "transparent"
                           :border "1px solid var(--vscode-button-border)"
                           :border-radius "2px"
                           :cursor :pointer
                           :flex-shrink "0"}}
          [:i {:class "codicon codicon-debug-stop"
               :style {:color "var(--vscode-errorForeground)"
                       :font-size "14px"}}]])
       [:span {:style {:font-size "0.9em" :opacity "0.7" :flex-shrink "0"}}
        time-str]]]
     [:div {:style {:font-size "0.9em" :margin-bottom "4px"}}
      current-turn "/" max-turns " | "
      [:strong "Tks: "] total-tokens " | "
      model-id " | "
      (when caller
        [:span
         [:strong "Who: "] caller])]
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
  (let [conversations (state/get-all-conversations)
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
      [:h2 {:style {:margin "0"}} "Dispatch Monitor"]
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
  (if-let [slot (state/get-sidebar-slot)]
    slot
    (let [free-slots (apply disj
                            #{:sidebar-1 :sidebar-2 :sidebar-3 :sidebar-4 :sidebar-5}
                            (vec (keys (flare/ls))))]
      (when (seq free-slots)
        (let [new-slot (first free-slots)]
          (state/set-sidebar-slot! new-slot)
          new-slot)))))

(defn update-agent-monitor-flare!+
  "Update the agent monitor flare in sidebar"
  []
  (when-let [slot (ensure-sidebar-slot!)]
    (flare/flare!+
     {:key slot
      :title "Agent Dispatch"
      :html (agent-monitor-html)
      :reveal? false
      :message-handler (fn [msg]
                         (case (.-command msg)
                           "showLogs" (.show (logging/get-output-channel!))
                           "cancel" (when-let [id (.-id msg)]
                                     (cancel-conversation! id)
                                     (update-agent-monitor-flare!+))
                           nil))})))

;; Public API for Integration

(defn reveal-dispatch-monitor!+
  "Reveal the dispatch monitor in the sidebar"
  []
  (update-agent-monitor-flare!+)
  (let [slot (state/get-sidebar-slot)
        view (some-> (flare/ls) slot :view)]
    (.show view true)))

(defn start-monitoring-conversation!+
  "Start monitoring a conversation - registers it, logs, and updates flare"
  [{:agent.conversation/keys [goal] :as conversation-data}]
  (let [conv-id (state/register-conversation! conversation-data)]
    (logging/log-to-channel! conv-id (str "🚀 Starting conversation: " goal))
    (p/let [_ (update-agent-monitor-flare!+)]
      conv-id)))

(defn log-and-update!+
  "Log messages and optionally update conversation status.
   Accepts variadic messages for compatibility with `println`."
  [conv-id status-updates & messages]
  (logging/log-to-channel! conv-id (string/join " " messages))
  (when status-updates
    (state/update-conversation! conv-id status-updates))
  (update-agent-monitor-flare!+))

(comment
  ;; Test the monitor

  ;; Create first conversation
  (p/let [conv-id (start-monitoring-conversation!+
                   {:agent.conversation/goal "Test workflow with flare updates"
                    :agent.conversation/model-id "gpt-4o-mini"
                    :agent.conversation/max-turns 5
                    :agent.conversation/caller "repl-test"
                    :agent.conversation/title "Monior test"})]
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
  @state/!agent-state

  ;; Get all conversations
  (state/get-all-conversations)

  ;; Manually update flare
  (update-agent-monitor-flare!+)

  (flare/flare!+ {:html [:div [:h2 "Sidebar"] [:p "Content"]]
                  :key :sidebar-2})

  :rcf)
