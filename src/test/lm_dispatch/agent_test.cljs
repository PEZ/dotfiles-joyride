;; AGENTS, please read this preamble before working with the namespace:
;; - Use interactive programming
;; - Work using TDD in the repl
;; - Always prefer your structural editing tools

(ns test.lm-dispatch.agent-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [clojure.string :as string]
   [promesa.core :as p]
   [lm-dispatch.agent-core :as agent]
   [lm-dispatch.agent-orchestrator :as orchestrator]
   [lm-dispatch.instructions-util :as instr-util]))

;; To run all tests:
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

(deftest concatenate-instruction-files-test
  (testing "Concatenates empty file list to empty string"
    (p/let [result (instr-util/concatenate-instruction-files!+ [])]
      (is (= "" result)
          "Empty list should return empty string"))))


(deftest collect-all-instruction-descriptions-test
  (testing "Collects instruction descriptions"
    (p/let [descriptions (instr-util/collect-all-instruction-descriptions!+)]
      (is (vector? descriptions)
          "Should return a vector")
      (is (every? map? descriptions)
          "Each item should be a map")
      (is (every? #(contains? % :file) descriptions)
          "Each description should have :file key")
      (is (every? #(contains? % :filename) descriptions)
          "Each description should have :filename key"))))

(deftest prepare-instructions-from-selected-paths-test
  (testing "Returns empty string for empty inputs"
    (p/let [result (instr-util/prepare-instructions-from-selected-paths!+
                    {:agent.conversation/instructions-paths []
                     :agent.conversation/context-files []})]
      (is (= "" result)
          "Empty paths should return empty string")))

  (testing "Handles nil inputs gracefully"
    (p/let [result (instr-util/prepare-instructions-from-selected-paths!+
                    {:agent.conversation/instructions-paths nil
                     :agent.conversation/context-files nil})]
      (is (= "" result)
          "Nil paths should return empty string"))))

(deftest validate-instructions-test
  (testing "Accepts valid instruction types"
    (is (nil? (orchestrator/validate-instructions! "Go!"))
        "Should accept string")
    (is (nil? (orchestrator/validate-instructions! ["/path/to/file.md"]))
        "Should accept vector")
    (is (nil? (orchestrator/validate-instructions! :instructions-selector))
        "Should accept :instructions-selector keyword")
    (is (nil? (orchestrator/validate-instructions! nil))
        "Should accept nil"))

  (testing "Rejects invalid instruction types"
    (is (thrown? js/Error (orchestrator/validate-instructions! 123))
        "Should reject number")
    (is (thrown? js/Error (orchestrator/validate-instructions! {:foo "bar"}))
        "Should reject map")
    (is (thrown? js/Error (orchestrator/validate-instructions! :wrong-keyword))
        "Should reject wrong keyword")))

(deftest validate-context-paths-test
  (testing "Accepts valid context path types"
    (is (nil? (orchestrator/validate-context-paths! ["/path.md"]))
        "Should accept vector")
    (is (nil? (orchestrator/validate-context-paths! []))
        "Should accept empty vector")
    (is (nil? (orchestrator/validate-context-paths! nil))
        "Should accept nil"))

  (testing "Rejects invalid context path types"
    (is (thrown? js/Error (orchestrator/validate-context-paths! "not-a-vector"))
        "Should reject string")
    (is (thrown? js/Error (orchestrator/validate-context-paths! 123))
        "Should reject number")))

(deftest assemble-instructions-test
  (testing "Handles string instructions"
    (p/let [result (instr-util/assemble-instructions!+ "Go, go, go!" nil)]
      (is (= "Go, go, go!" result)
          "Should return string as-is")))

  (testing "Handles empty vector instructions"
    (p/let [result (instr-util/assemble-instructions!+ [] nil)]
      (is (= "" result)
          "Should return empty string for empty vector")))

  (testing "Handles nil instructions"
    (p/let [result (instr-util/assemble-instructions!+ nil nil)]
      (is (= "" result)
          "Should return empty string for nil")))

  (testing "Appends context files after instructions"
    (p/let [result (instr-util/assemble-instructions!+ "Instructions here" [])]
      (is (= "Instructions here" result)
          "Should handle empty context"))))
