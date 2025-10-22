;; AGENTS, please read this preamble before working with the namespace:
;; - Use interactive programming
;; - Work using TDD in the repl
;; - Always prefer your structural editing tools

(ns test.lm-dispatch.agent-test
  (:require
   [cljs.test :refer [deftest is testing async]]
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
  (async done
         (p/let [result (instr-util/concatenate-instruction-files!+ [])]
           (is (= "" result)
               "Empty list should return empty string")
           (done))))


(deftest collect-all-instruction-descriptions-test
  (async done
    (p/let [descriptions (instr-util/collect-all-instruction-descriptions!+)]
      (is (vector? descriptions)
          "Should return a vector")
      (is (every? map? descriptions)
          "Each item should be a map")
      (is (every? #(contains? % :file) descriptions)
          "Each description should have :file key")
      (is (every? #(contains? % :filename) descriptions)
          "Each description should have :filename key")
      (done))))

(deftest prepare-instructions-from-selected-paths-empty-test
  (async done
    (p/let [result (instr-util/prepare-instructions-from-selected-paths!+
                    {:agent.conversation/instructions-paths []
                     :agent.conversation/context-files []})]
      (is (= "" result)
          "Empty paths should return empty string")
      (done))))

(deftest prepare-instructions-from-selected-paths-nil-test
  (async done
    (p/let [result (instr-util/prepare-instructions-from-selected-paths!+
                    {:agent.conversation/instructions-paths nil
                     :agent.conversation/context-files nil})]
      (is (= "" result)
          "Nil paths should return empty string")
      (done))))

(deftest autonomous-conversation-validation-test
  (testing "Accepts valid instruction types"
    ;; These should not throw during parameter validation
    (is (map? {:instructions "Go!"}) "String instructions accepted")
    (is (map? {:instructions ["/path/to/file.md"]}) "Vector instructions accepted")
    (is (map? {:instructions :instructions-selector}) ":instructions-selector accepted")
    (is (map? {:instructions nil}) "Nil instructions accepted"))

  (testing "Accepts valid context path types"
    (is (map? {:context-file-paths ["/path.md"]}) "Vector context paths accepted")
    (is (map? {:context-file-paths []}) "Empty vector accepted")
    (is (map? {:context-file-paths nil}) "Nil context paths accepted"))

  (testing "Rejects invalid instruction types via :pre"
    (is (thrown? js/Error
                 (orchestrator/autonomous-conversation!+
                  "Test goal"
                  {:instructions 123}))
        "Should reject number")
    (is (thrown? js/Error
                 (orchestrator/autonomous-conversation!+
                  "Test goal"
                  {:instructions {:foo "bar"}}))
        "Should reject map")
    (is (thrown? js/Error
                 (orchestrator/autonomous-conversation!+
                  "Test goal"
                  {:instructions :wrong-keyword}))
        "Should reject wrong keyword"))

  (testing "Rejects invalid context path types via :pre"
    (is (thrown? js/Error
                 (orchestrator/autonomous-conversation!+
                  "Test goal"
                  {:context-file-paths "not-a-vector"}))
        "Should reject string")
    (is (thrown? js/Error
                 (orchestrator/autonomous-conversation!+
                  "Test goal"
                  {:context-file-paths 123}))
        "Should reject number")))

(deftest assemble-instructions-string-test
  (async done
    (p/let [result (instr-util/assemble-instructions!+ "Go, go, go!" nil nil)]
      (is (= "Go, go, go!" result)
          "Should return string as-is")
      (done))))

(deftest assemble-instructions-empty-vector-test
  (async done
    (p/let [result (instr-util/assemble-instructions!+ [] nil nil)]
      (is (= "" result)
          "Should return empty string for empty vector")
      (done))))

(deftest assemble-instructions-nil-test
  (async done
    (p/let [result (instr-util/assemble-instructions!+ nil nil nil)]
      (is (= "" result)
          "Should return empty string for nil")
      (done))))

(deftest assemble-instructions-with-context-test
  (async done
    (p/let [result (instr-util/assemble-instructions!+ "Instructions here" nil [])]
      (is (= "Instructions here" result)
          "Should handle empty context")
      (done))))

(deftest enrich-editor-context-full-test
  (async done
    (p/let [result (instr-util/enrich-editor-context!+
                    {:editor-context/file-path "/Users/pez/.config/joyride/src/agents/memory_keeper.cljs"
                     :editor-context/selection-start-line 10
                     :editor-context/selection-end-line 12})]
      (is (some? result)
          "Should return enriched map")
      (is (= "/Users/pez/.config/joyride/src/agents/memory_keeper.cljs"
             (:editor-context/file-path result))
          "Should preserve file path")
      (is (= 10 (:editor-context/selection-start-line result))
          "Should preserve start line")
      (is (= 12 (:editor-context/selection-end-line result))
          "Should preserve end line")
      (is (some? (:editor-context/full-file-content result))
          "Should have full file content")
      (is (some? (:editor-context/selected-text result))
          "Should have selected text")
      (is (string/includes? (:editor-context/selected-text result) "vscode")
          "Selection should contain expected content from lines 10-12")
      (done))))

(deftest enrich-editor-context-nil-path-test
  (async done
    (p/let [result (instr-util/enrich-editor-context!+ nil)]
      (is (nil? result)
          "Should return nil when no file-path")
      (done))))

(deftest enrich-editor-context-no-selection-test
  (async done
    (p/let [result (instr-util/enrich-editor-context!+
                    {:editor-context/file-path "/Users/pez/.config/joyride/src/agents/memory_keeper.cljs"})]
      (is (some? result)
          "Should return map even without selection")
      (is (some? (:editor-context/full-file-content result))
          "Should have full file content")
      (is (nil? (:editor-context/selected-text result))
          "Should have nil selection when no range provided")
      (done))))

(deftest enrich-editor-context-partial-range-test
  (async done
    (p/let [result (instr-util/enrich-editor-context!+
                    {:editor-context/file-path "/Users/pez/.config/joyride/src/agents/memory_keeper.cljs"
                     :editor-context/selection-start-line 10})]
      (is (some? result)
          "Should return map with partial range")
      (is (nil? (:editor-context/selected-text result))
          "Should have nil selection when range incomplete")
      (done))))
