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

(def monitor-title "Agent Dispatch")

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

(defn open-results-document!+
  "Returns promise of showing conversation results from `conv-id` in new untitled document."
  [conv-id]
  (when-let [conv (state/get-conversation conv-id)]
    (when-let [results (:agent.conversation/results conv)]
      (p/let [doc (vscode/workspace.openTextDocument
                    #js {:content results
                         :language "text"})]
        (vscode/window.showTextDocument doc)))))

;; Flare Management

(defn find-monitor-slot
  "Returns the slot keyword where the Agent Dispatch monitor currently exists,
   or nil if it doesn't exist."
  []
  (some (fn [[slot flare-info]]
          (when (= monitor-title (some-> flare-info :view .-title))
            slot))
        (flare/ls)))

(defn find-free-sidebar-slot
  "Returns first available sidebar slot, or nil if all are occupied."
  []
  (let [free-slots (apply disj
                          #{:sidebar-1 :sidebar-2 :sidebar-3 :sidebar-4 :sidebar-5}
                          (vec (keys (flare/ls))))]
    (first free-slots)))

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

(defn ensure-monitor-flare!+
  "Returns promise of the slot containing the monitor flare.
   Creates the flare in a free slot if it doesn't exist."
  []
  (if-let [existing-slot (find-monitor-slot)]
    (p/resolved existing-slot)
    (when-let [free-slot (find-free-sidebar-slot)]
      (p/let [_ (flare/flare!+
                 {:key free-slot
                  :title monitor-title
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
                          [:link {:rel "stylesheet"
                                  :href "{joyride/user-dir}/resources/scittle/monitor/style.css"}]
                          [:style "body { margin: 0; padding: 0; }"]]
                         [:body
                          [:div#app]]]
                  :reveal? false
                  :preserve-focus? true
                  :webview-options {:enableScripts true
                                    :retainContextWhenHidden true}
                  :message-handler message-handler-fn})]
        free-slot))))

(defn send-state-to-webview!+
  "Returns promise of sending current conversation state to webview."
  []
  (when-let [slot (find-monitor-slot)]
    (let [conversations (state/get-all-conversations)]
      (flare/post-message!+ slot
        {:type "state-update"
         :data {:conversations conversations}}))))

;; Public API for Integration

(defn start-monitoring-conversation!+
  "Returns promise of conversation ID after registering `conversation-data`,
   ensuring monitor flare exists, logging, and sending state to Replicant webview."
  [{:agent.conversation/keys [goal] :as conversation-data}]
  (let [conv-id (state/register-conversation! conversation-data)]
    (logging/log-to-channel! conv-id (str "🚀 Starting conversation: " (logging/truncate-strings-for-logging goal)))
    (p/let [_ (ensure-monitor-flare!+)
            _ (send-state-to-webview!+)]
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
  (p/let [slot (ensure-monitor-flare!+)
          _ (send-state-to-webview!+)]
    (when slot
      (let [view (some-> (flare/ls) slot :view)]
        (.show view true)))))

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

  ;; Manually send state to Replicant (new way)
  (send-state-to-webview!+)

  (flare/flare!+ {:html [:div [:h2 "Sidebar"] [:p "Content"]]
                  :key :sidebar-2})

  :rcf)
