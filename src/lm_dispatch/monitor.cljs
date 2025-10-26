;; AGENTS, please read this preamble before working with the namespace:
;; - Use interactive programming
;; - Work using TDD in the repl, existing tests: src/test/lm_dispatch/monitor_test.cljs
#_(do (require 'run-all-tests :reload) (run-all-tests/run!+))
;; - Always prefer your structural editing tools

(ns lm-dispatch.monitor
  "Presentation layer for agent dispatch monitoring.

  Renders conversation UI in sidebar flare and handles user interactions."
  (:require
   [lm-dispatch.state :as state]
   [lm-dispatch.logging :as logging]
   [clojure.string :as string]
   [joyride.flare :as flare]
   [promesa.core :as p]
   ["vscode" :as vscode]))

;; UI Interaction Handlers

(defn cancel-conversation!
  "Cancels cancellation token and marks conversation `conv-id` as cancelled."
  [conv-id]
  (when-let [conv (state/get-conversation conv-id)]
    (logging/log-to-channel! conv-id "🛑 Cancellation requested")
    (when-let [token-source (:agent.conversation/cancellation-token-source conv)]
      (.cancel token-source))
    (state/mark-conversation-cancelled! conv-id)))

(defn delete-conversation!
  "Removes conversation `conv-id` from state."
  [conv-id]
  (state/delete-conversation! conv-id))

(defn truncate-summary
  "Returns `text` truncated to `max-length` with ellipsis if needed,
   or `nil` if `text` is `nil`."
  [text max-length]
  (when text
    (if (> (count text) max-length)
      (str (subs text 0 max-length) "...")
      text)))

(defn open-results-document!+
  "Returns promise of showing conversation results from `conv-id` in new untitled document."
  [conv-id]
  (when-let [conv (state/get-conversation conv-id)]
    (when-let [results (:agent.conversation/results conv)]
      (p/let [doc (vscode/workspace.openTextDocument
                    #js {:content results
                         :language "text"})]
        (vscode/window.showTextDocument doc)))))

;; UI Rendering

(defn status-icon
  "Returns codicon class string for conversation `status`."
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
  "Returns HH:MM formatted string from `js-date`, or '--:--' if `nil`."
  [js-date]
  (if js-date
    (let [hours (.getHours js-date)
          minutes (.getMinutes js-date)
          pad (fn [n] (if (< n 10) (str "0" n) (str n)))]
      (str (pad hours) ":" (pad minutes)))
    "--:--"))

(defn render-results-section
  "Returns Hiccup div rendering `results` section with 'View Full' button,
   or `nil` if no results."
  [conv-id results icon-color]
  (when results
    (let [summary (truncate-summary results 100)
          result-color (or icon-color "var(--vscode-charts-green)")]
      [:div {:style {:color result-color
                     :font-size "0.85em"
                     :margin-top "4px"
                     :display :flex
                     :justify-content :space-between
                     :align-items :center
                     :gap "8px"}}
       [:span {:style {:flex "1" :min-width "0"}}
        "Results: " summary]
       [:button {:onclick (str "vscode.postMessage({command: 'showResults', id: " conv-id "})")
                 :style {:padding "2px 6px"
                         :background "var(--vscode-button-background)"
                         :color "var(--vscode-button-foreground)"
                         :border :none
                         :border-radius "2px"
                         :cursor :pointer
                         :font-size "0.8em"
                         :flex-shrink "0"}}
        "View Full"]])))

(defn conversation-html
  "Returns Hiccup HTML for single conversation entry from `conv` map."
  [conv]
  (let [{:agent.conversation/keys [id goal status model-id
                                   caller title
                                   current-turn max-turns
                                   started-at error-message
                                   results total-tokens]} conv
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
        time-str]
       (when (#{:task-complete :max-turns-reached :agent-finished :cancelled :error :done} status)
         [:button {:onclick (str "vscode.postMessage({command: 'delete', id: " id "})")
                   :style {:padding "2px 4px"
                           :background "transparent"
                           :border "none"
                           :cursor :pointer
                           :opacity "0.6"
                           :flex-shrink "0"}}
          [:i {:class "codicon codicon-close"
               :style {:color "var(--vscode-foreground)"
                       :font-size "12px"}}]])]]
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
        "Error: " error-message])
     (render-results-section id results icon-color)]))

(defn agent-monitor-html
  "Returns complete Hiccup HTML for monitor display."
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

;; Replicant-based Monitor (New Implementation)

(defn message-handler-fn
  "Handles messages from Replicant webview."
  [msg]
  (let [msg-type (keyword (.-type msg))
        msg-data (js->clj (.-data msg) :keywordize-keys true)]
    (case msg-type
      :cancel-conversation (cancel-conversation! (:id msg-data))
      :delete-conversation (delete-conversation! (:id msg-data))
      :show-results (open-results-document!+ (:id msg-data))
      :show-logs (.show (logging/get-output-channel!))
      :log (apply println (into msg-data "\n"))
      :warn (apply (partial println "WARNING: ") msg-data)
      (js/console.warn "Unknown message from webview:" msg-type))))

(defn create-monitor-flare!+
  "Returns promise of creating the Replicant-based monitor flare in sidebar."
  []
  (when-let [slot (ensure-sidebar-slot!)]
    (flare/flare!+
     {:key slot
      :title "Agent Dispatch"
      :html [:html
             [:head
              [:script {:src "https://cdn.jsdelivr.net/npm/scittle@0.7.28/dist/scittle.js"
                        :type "application/javascript"}]
              [:script {:src "https://cdn.jsdelivr.net/npm/scittle@0.7.28/dist/scittle.replicant.js"
                        :type "application/javascript"}]
              [:script {:type "application/x-scittle"
                        :src "{joyride/user-dir}/resources/scittle/monitor/dispatch.cljs"}]
              [:script {:type "application/x-scittle"
                        :src "{joyride/user-dir}/resources/scittle/monitor/ui.cljs"}]
              [:script {:type "application/x-scittle"
                        :src "{joyride/user-dir}/resources/scittle/monitor/core.cljs"}]
              [:link {:rel "stylesheet"
                      :href "https://unpkg.com/@vscode/codicons@latest/dist/codicon.css"}]
              [:style "body { margin: 0; padding: 0; }"]]
             [:body
              [:div#app]]]
      :reveal? false
      :preserve-focus? true
      :webview-options {:enableScripts true
                        :retainContextWhenHidden true}
      :message-handler message-handler-fn})))

(defn send-state-to-webview!+
  "Returns promise of sending current conversation state to webview."
  []
  (when-let [slot (state/get-sidebar-slot)]
    (let [conversations (state/get-all-conversations)]
      (flare/post-message!+ slot
        {:type "state-update"
         :data {:conversations conversations}}))))

;; Public API for Integration

(defn start-monitoring-conversation!+
  "Returns promise of conversation ID after registering `conversation-data`,
   logging, and sending state to Replicant webview."
  [{:agent.conversation/keys [goal] :as conversation-data}]
  (let [conv-id (state/register-conversation! conversation-data)]
    (logging/log-to-channel! conv-id (str "🚀 Starting conversation: " (logging/truncate-strings-for-logging goal)))
    (p/let [_ (send-state-to-webview!+)]
      conv-id)))

(defn log-and-update!+
  "Returns promise of logging variadic `messages`, updating conversation `conv-id`
   with `status-updates`, and sending state to Replicant webview.

   Accepts variadic messages for compatibility with `println`."
  [conv-id status-updates & messages]
  (logging/log-to-channel! conv-id (string/join " " messages))
  (when status-updates
    (state/update-conversation! conv-id status-updates))
  (send-state-to-webview!+))

(defn reveal-dispatch-monitor!+
  "Returns promise of revealing the Replicant dispatch monitor in the sidebar."
  []
  (p/let [_ (create-monitor-flare!+)
          _ (send-state-to-webview!+)]
    (let [slot (state/get-sidebar-slot)
          view (some-> (flare/ls) slot :view)]
      (.show view true))))

(defn update-agent-monitor-flare!+
  "Returns promise of updating the agent monitor flare in sidebar."
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
                           "showResults" (when-let [id (.-id msg)]
                                          (open-results-document!+ id))
                           "delete" (when-let [id (.-id msg)]
                                     (delete-conversation! id)
                                     (update-agent-monitor-flare!+))
                           nil))})))

(comment
  (reveal-dispatch-monitor!+)
  (flare/ls)
  (flare/close-all!)

  ;; Create first conversation
  (p/let [conv-id (start-monitoring-conversation!+
                   {:agent.conversation/goal "Test workflow with flare updates"
                    :agent.conversation/model-id "gpt-4o-mini"
                    :agent.conversation/max-turns 5
                    :agent.conversation/caller "repl-test"
                    :agent.conversation/title "Monitor test"})]
    (def test-conv-id conv-id)
    (println "Started conversation" conv-id))

  ;; Update progress
  (p/let [_ (log-and-update!+
             test-conv-id
             {:agent.conversation/status :working
              :agent.conversation/current-turn 3
              :agent.conversation/total-tokens 1500}
             "🔄 Turn 3/10")]
    (println "Updated to working, and then some"))

  ;; Add more activity
  (p/let [_ (log-and-update!+ test-conv-id nil "🤖 AI Agent says: Analyzing...")
          _ (log-and-update!+ test-conv-id nil "🔧 Executing tools...")]
    (println "Logged activity"))

  ;; Complete conversation
  (p/let [_ (log-and-update!+ test-conv-id
                              {:agent.conversation/status :done
                               :agent.conversation/current-turn 5
                               :agent.conversation/results "Replicant integration complete! Much more better"}
                              "✅ Task completed!")]
    (println "Conversation completed"))

  ;; Create error conversation
  (p/let [conv-id (start-monitoring-conversation!+
                   {:agent.conversation/goal "Task that encounters error"
                    :agent.conversation/model-id "claude-sonnet-4"
                    :agent.conversation/max-turns 10
                    :agent.conversation/caller "error-test"})
          _ (log-and-update!+ conv-id
                              {:agent.conversation/status :error
                               :agent.conversation/current-turn 3
                               :agent.conversation/error-message "Model not found"}
                              "❌ Error: Model not found")]
    (println "Error conversation created"))


  ;; Check state
  @state/!agent-state

  ;; Get all conversations
  (state/get-all-conversations)

  ;; Manually update flare (old way)
  (update-agent-monitor-flare!+)

  ;; Manually send state to Replicant (new way)
  (send-state-to-webview!+)

  (flare/flare!+ {:html [:div [:h2 "Sidebar"] [:p "Content"]]
                  :key :sidebar-2})

  :rcf)
