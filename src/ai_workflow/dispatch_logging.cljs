(ns ai-workflow.dispatch-logging
  "Logging infrastructure for agent dispatch system.
  
  Manages output channel for conversation logs."
  (:require
   ["vscode" :as vscode]
   [ai-workflow.dispatch-state :as state]))

;; Output Channel Management

(defonce !output-channel (atom nil))

(defn get-output-channel!
  "Get or create the output channel for agent logs"
  []
  (if-let [channel @!output-channel]
    channel
    (let [channel (vscode/window.createOutputChannel "Agent Dispatch")]
      (reset! !output-channel channel)
      channel)))

(defn log-to-channel!
  "Log a message to the agent channel with conversation ID prefix"
  [conv-id message]
  (let [channel (get-output-channel!)
        timestamp (.toLocaleTimeString (js/Date.))
        prefix (str "[" timestamp "] [Conv-" conv-id "] ")]
    (.appendLine channel (str prefix message))))

(defn clear-log!
  "Clear the output channel"
  []
  (when-let [channel @!output-channel]
    (.clear channel)))
