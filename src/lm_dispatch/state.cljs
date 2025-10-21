;; AGENTS, please read this preamble before working with the namespace:
;; - Use interactive programming
;; - Work using TDD in the repl, existing tests: src/test/lm_dispatch/state_test.cljs
#_(do (require 'run-all-tests :reload-all-all) (run-all-tests/run!+))
;; - Always prefer your structural editing tools

(ns lm-dispatch.state
  "Pure state management for agent dispatch conversations.

  This namespace provides the single source of truth for conversation data
  with pure functions operating on a centralized atom. No side effects.")

;; State Atom

(defonce !agent-state
  (atom {:agent/conversations {}
         :agent/next-id 1
         :agent/output-channel nil
         :agent/sidebar-slot nil}))

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
                             :agent.conversation/cancellation-token-source nil
                             :agent.conversation/total-tokens 0})]
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
  "Mark conversation as cancelled and trigger the cancellation token"
  [conv-id]
  (let [conv (get-in @!agent-state [:agent/conversations conv-id])
        token-source (:agent.conversation/cancellation-token-source conv)]
    (when token-source
      (.cancel token-source))
    (swap! !agent-state
           (fn [state]
             (-> state ; TODO: This is confusing, is it cancelled or is cancel requested?
                 (assoc-in [:agent/conversations conv-id :agent.conversation/cancelled?] true)
                 (assoc-in [:agent/conversations conv-id :agent.conversation/status] :cancel-requested))))))

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

(defn get-output-channel
  "Get the output channel from state"
  []
  (:agent/output-channel @!agent-state))

(defn set-output-channel!
  "Set the output channel in state"
  [channel]
  (swap! !agent-state assoc :agent/output-channel channel))
