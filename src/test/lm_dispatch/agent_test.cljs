; AGENTS, please:
; - remember interactive programming
; - consider TDD in the repl
; - prefer your structural editing tools

(ns test.lm-dispatch.agent-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [clojure.string :as string]
   [lm-dispatch.agent :as agent]))

; To run all tests:
#_(do (require 'run-all-tests :reload) (run-all-tests/run!+))

(deftest agent-indicates-completion?-test
  (testing "Detects completion markers"
    (is (agent/agent-indicates-completion? "~~~GOAL-ACHIEVED~~~")
        "Should detect GOAL-ACHIEVED marker")
    (is (agent/agent-indicates-completion? "Task completed successfully")
        "Should detect 'completed' keyword")
    (is (agent/agent-indicates-completion? "Goal accomplished!")
        "Should detect 'accomplished' keyword")
    (is (agent/agent-indicates-completion? "Successfully finished the work")
        "Should detect 'successfully finished'"))

  (testing "Does not match incomplete or continuing messages"
    (is (not (agent/agent-indicates-completion? "I'm still working"))
        "Should not match work in progress")
    (is (not (agent/agent-indicates-completion? "~~~CONTINUING~~~"))
        "Should not match CONTINUING marker")
    (is (not (agent/agent-indicates-completion? nil))
        "Should handle nil gracefully")))

(deftest add-assistant-response-test
  (testing "Adds assistant response to history"
    (let [history []
          updated (agent/add-assistant-response history "AI says hello" #js [] 1)]

      (is (= 1 (count updated))
          "Should add one entry to history")
      (is (= :assistant (:role (first updated)))
          "Entry should have :assistant role")
      (is (= "AI says hello" (:content (first updated)))
          "Entry should contain AI text")
      (is (= 1 (:turn (first updated)))
          "Entry should have turn number")))

  (testing "Preserves existing history"
    (let [history [{:role :assistant :content "Previous" :turn 0}]
          updated (agent/add-assistant-response history "New response" #js [] 1)]

      (is (= 2 (count updated))
          "Should preserve previous entries")
      (is (= "Previous" (:content (first updated)))
          "First entry should be unchanged"))))

(deftest add-tool-results-test
  (testing "Adds tool results to history"
    (let [history [{:role :assistant :content "Using tools"}]
          tool-results ["result 1" "result 2"]
          updated (agent/add-tool-results history tool-results 1)]

      (is (= 2 (count updated))
          "Should add tool results entry")
      (is (= :tool-results (:role (second updated)))
          "Entry should have :tool-results role")
      (is (= tool-results (:processed-results (second updated)))
          "Should store processed results")
      (is (= 1 (:turn (second updated)))
          "Entry should have turn number"))))

(deftest determine-conversation-outcome-test
  (testing "Detects max turns reached"
    (let [outcome (agent/determine-conversation-outcome "text" #js [] 10 10)]
      (is (false? (:continue? outcome)))
      (is (= :max-turns-reached (:reason outcome)))))

  (testing "Continues when tools are executing"
    (let [outcome (agent/determine-conversation-outcome "text" #js [#js {:name "tool"}] 5 10)]
      (is (true? (:continue? outcome)))
      (is (= :tools-executing (:reason outcome)))))

  (testing "Stops when task is complete"
    (let [outcome (agent/determine-conversation-outcome "~~~GOAL-ACHIEVED~~~" #js [] 5 10)]
      (is (false? (:continue? outcome)))
      (is (= :task-complete (:reason outcome)))))

  (testing "Continues when agent indicates continuation"
    (let [outcome (agent/determine-conversation-outcome "~~~CONTINUING~~~ next step" #js [] 5 10)]
      (is (true? (:continue? outcome)))
      (is (= :agent-continuing (:reason outcome)))))

  (testing "Stops when agent is finished"
    (let [outcome (agent/determine-conversation-outcome "I'm done" #js [] 5 10)]
      (is (false? (:continue? outcome)))
      (is (= :agent-finished (:reason outcome))))))

(deftest format-completion-result-test
  (testing "Formats completion result correctly"
    (let [history [{:role :assistant :content "response"}]
          result (agent/format-completion-result history :task-complete {:text "Done"})]

      (is (= history (:history result))
          "Should include history")
      (is (= :task-complete (:reason result))
          "Should include reason")
      (is (= {:text "Done"} (:final-response result))
          "Should include final response"))))

(deftest build-agentic-messages-test
  (testing "First turn creates single goal message"
    (let [messages (agent/build-agentic-messages [] "Instructions" "Count files")]

      (is (= 1 (count messages))
          "First turn should have single message")
      (is (= :user (:role (first messages)))
          "Message should be user role")
      (is (string/includes? (:content (first messages)) "Count files")
          "Should contain goal")
      (is (string/includes? (:content (first messages)) "Instructions")
          "Should contain instructions")))

  (testing "Subsequent turns include history"
    (let [history [{:role :assistant :content "I'll help" :tool-calls [] :turn 1}]
          messages (agent/build-agentic-messages history "Go!" "Task")]

      (is (> (count messages) 1)
          "Should have multiple messages with history")
      (is (= :user (:role (first messages)))
          "First should be goal message")
      (is (= :assistant (:role (second messages)))
          "Second should be assistant response from history")))

  (testing "Tool results become user messages"
    (let [history [{:role :tool-results
                    :processed-results ["result 1" "result 2"]
                    :turn 1}]
          messages (agent/build-agentic-messages history "Go!" "Task")]

      (is (= 3 (count messages))
          "Should have goal + 2 tool result messages")
      (is (= :user (:role (second messages)))
          "Tool results should become user messages")
      (is (string/includes? (:content (second messages)) "result 1")
          "Should contain tool result content"))))
