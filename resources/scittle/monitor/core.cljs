(ns monitor.core
  "Main entry point for Replicant-based monitor"
  (:require [replicant.dom :as r]
            [monitor.dispatch :as dispatch]
            [monitor.ui :as ui]))

;; State atom for webview
(defonce !monitor-state (atom {:conversations []}))


;; Replicant dispatch - routes UI action vectors to appropriate handlers
(r/set-dispatch! dispatch/dispatch!)

;; Render function
(defn render! []
  (let [el (js/document.getElementById "app")
        state @!monitor-state]
    (->> (:conversations state)
         ui/render-monitor
         (r/render el))))

;; Watch state and re-render on changes
(add-watch !monitor-state ::render
           (fn [_ _ _ _]
             (render!)))

;; Incoming message handler from host
(defn handle-incoming-message [message-event]
  (let [message (js->clj (.-data message-event) :keywordize-keys true)
        msg-type (keyword (:type message))
        msg-data (:data message)]
    (case msg-type
      :state-update
      (do
        (dispatch/log! "📥 Received state update with"
                       (count (:conversations msg-data)) "conversations")
        (reset! !monitor-state msg-data))

      :conversation-update
      (do
        (dispatch/log! "📥 Conversation update:" (:agent.conversation/id msg-data))
        (swap! !monitor-state
               (fn [state]
                 (update state :conversations
                         (fn [convs]
                           (mapv #(if (= (:agent.conversation/id %)
                                         (:agent.conversation/id msg-data))
                                    (merge % msg-data)
                                    %)
                                 convs))))))

      (dispatch/warn! "❓ Unknown message type:" msg-type))))

;; Initialize
(defn init! []
  (dispatch/log! "🚀 Monitor Replicant initializing...")
  (dispatch/log! "📍 Current state:" @!monitor-state)
  (.addEventListener js/window "message" handle-incoming-message)
  (render!)
  (dispatch/log! "✅ Monitor Replicant ready!")
  (dispatch/log! "📍 State after render:" @!monitor-state))

(init!)
