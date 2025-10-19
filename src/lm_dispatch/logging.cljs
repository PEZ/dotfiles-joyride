(ns lm-dispatch.logging
  "Logging infrastructure for agent dispatch system.

  Manages output channel for conversation logs."
  (:require
   ["vscode" :as vscode]
   [lm-dispatch.state :as state]))

;; To run all tests:
#_(do (require 'run-all-tests :reload) (run-all-tests/run!+))

;; Output Channel Management

(defn get-output-channel!
  "Get or create the output channel for agent logs"
  []
  (if-let [channel (state/get-output-channel)]
    channel
    (let [channel (vscode/window.createOutputChannel "Agent Dispatch")]
      (state/set-output-channel! channel)
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
  (when-let [channel (state/get-output-channel)]
    (.clear channel)))

;; Debug Logging

(defn enable-debug-mode!
  "Enable debug mode to collect logs in memory for inspection"
  []
  (swap! state/!agent-state assoc :debug-mode? true :debug-logs []))

(defn disable-debug-mode!
  "Disable debug mode and clear debug logs"
  []
  (swap! state/!agent-state dissoc :debug-mode? :debug-logs))

(defn add-debug-log!
  "Add a debug log entry if debug mode is enabled"
  [conv-id message]
  (when (:debug-mode? @state/!agent-state)
    (swap! state/!agent-state update :debug-logs conj
           {:conv-id conv-id
            :timestamp (js/Date.)
            :message message})))

(defn get-debug-logs
  "Get all debug logs for a specific conversation, formatted as strings"
  [conv-id]
  (->> (:debug-logs @state/!agent-state)
       (filter #(= (:conv-id %) conv-id))
       (map (fn [{:keys [timestamp message]}]
              (str (.toISOString timestamp) " - " message)))))

(defn get-all-debug-logs
  "Get all debug logs across all conversations, formatted as strings"
  []
  (->> (:debug-logs @state/!agent-state)
       (map (fn [{:keys [conv-id timestamp message]}]
              (str "[Conv-" conv-id "] " (.toISOString timestamp) " - " message)))))

(defn clear-debug-logs!
  "Clear all debug logs but keep debug mode enabled"
  []
  (swap! state/!agent-state assoc :debug-logs []))

(comment
  ;; Enable debug mode
  (enable-debug-mode!)

  ;; Check if debug mode is on
  (:debug-mode? @state/!agent-state)

  ;; Run a conversation with debug logging
  (require '[lm-dispatch.agent-orchestrator :as orchestrator])
  (require '[promesa.core :as p])

  (p/let [result (orchestrator/autonomous-conversation!+
                  "Count to 3"
                  {:model-id "grok-code-fast-1"
                   :caller "debug-test"
                   :title "Debug Test"
                   :max-turns 2
                   :tool-ids ["joyride_evaluate_code"]})]
    ;; After conversation, check debug logs
    (def test-result result))

  ;; View logs for a specific conversation
  (get-debug-logs 10) ; Replace with actual conv-id

  ;; View all debug logs
  (get-all-debug-logs)

  ;; Clear debug logs (keeps debug mode on)
  (clear-debug-logs!)

  ;; Disable debug mode
  (disable-debug-mode!)

  :rcf)
