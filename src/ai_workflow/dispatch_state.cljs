(ns ai-workflow.dispatch-state
  "Pure state management for agent dispatch conversations.
  
  This namespace provides the single source of truth for conversation data
  with pure functions operating on a centralized atom. No side effects.")

;; State Atom

(defonce !agent-state
  (atom {:agent/conversations {}
         :agent/next-id 1}))

;; Pure State Operations

(defn register-conversation!
  "Register a new conversation and return its ID"
  [conversation-data]
  (let [id (:agent/next-id @!agent-state)
        conversation (merge conversation-data
                           {:agent.conversation/id id
                            :agent.conversation/status :started
                            :agent.conversation/current-turn 0
                            :agent.conversation/started-at (js/Date.)
                            :agent.conversation/cancelled? false
                            :agent.conversation/cancellation-token-source nil})]
    (swap! !agent-state
           (fn [state]
             (-> state
                 (assoc-in [:agent/conversations id] conversation)
                 (update :agent/next-id inc))))
    id))

(defn update-conversation!
  "Update conversation by ID with provided data map"
  [conv-id updates]
  (swap! !agent-state
         (fn [state]
           (update-in state [:agent/conversations conv-id] merge updates))))

(defn mark-conversation-cancelled!
  "Mark conversation as cancelled (state only, does not interact with token)"
  [conv-id]
  (swap! !agent-state
         (fn [state]
           (-> state
               (assoc-in [:agent/conversations conv-id :agent.conversation/cancelled?] true)
               (assoc-in [:agent/conversations conv-id :agent.conversation/status] :cancelled)))))

(defn get-conversation
  "Get conversation by ID"
  [conv-id]
  (get-in @!agent-state [:agent/conversations conv-id]))

(defn get-all-conversations
  "Get all conversations as a sequence"
  []
  (vals (:agent/conversations @!agent-state)))

(defn get-sidebar-slot
  "Get the sidebar slot for the monitor"
  []
  (:agent/sidebar-slot @!agent-state))

(defn set-sidebar-slot!
  "Set the sidebar slot for the monitor"
  [slot]
  (swap! !agent-state assoc :agent/sidebar-slot slot))
