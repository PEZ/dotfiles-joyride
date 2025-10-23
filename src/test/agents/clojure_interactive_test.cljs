;; AGENTS, please read this preamble before working with the namespace:
;; - Use interactive programming
;; - Work using TDD in the repl
#_(do (require 'run-all-tests :reload) (run-all-tests/run!+))
;; - Always prefer your structural editing tools

(ns test.agents.clojure-interactive-test
  (:require
   [cljs.test :refer [deftest is testing run-tests]]
   [clojure.string :as string]
   [agents.clojure-interactive :as ci]))

(deftest interactive-programming-goal-prompt-test
  (testing "Prompt contains all required sections"
    (let [task "Implement a function to process data"
          result (ci/interactive-programming-goal-prompt task)]
      
      (testing "Task is included in TASK tags"
        (is (string/includes? result "<TASK>"))
        (is (string/includes? result "Implement a function to process data"))
        (is (string/includes? result "</TASK>")))
      
      (testing "Contains REPL-first TDD guidance"
        (is (string/includes? result "REPL-first TDD workflow"))
        (is (string/includes? result "Start in the REPL"))
        (is (string/includes? result "clojure.test")))
      
      (testing "Contains tool usage warning"
        (is (string/includes? result "ONLY for reading their documentation"))
        (is (string/includes? result "not for execution")))
      
      (testing "Contains all five deliverable sections"
        (is (string/includes? result "### 1. Achievement Summary"))
        (is (string/includes? result "### 2. REPL State Reconstruction"))
        (is (string/includes? result "### 3. Verification Instructions for Copilot"))
        (is (string/includes? result "### 4. User Verification Handoff"))
        (is (string/includes? result "### 5. Structural Editing Guide for Copilot")))
      
      (testing "Contains explicit STOP instruction"
        (is (string/includes? result "STOP and wait for user feedback"))
        (is (string/includes? result "Do not proceed with file commits")))
      
      (testing "Contains report markers"
        (is (string/includes? result "---BEGIN INTERACTIVE REPORT---"))
        (is (string/includes? result "---END INTERACTIVE REPORT---"))
        (is (string/includes? result "~~~GOAL-ACHIEVED~~~")))))

  (testing "Different tasks produce task-specific prompts"
    (let [task1 "Create fibonacci function"
          task2 "Build data parser"
          result1 (ci/interactive-programming-goal-prompt task1)
          result2 (ci/interactive-programming-goal-prompt task2)]
      (is (string/includes? result1 "Create fibonacci function"))
      (is (string/includes? result2 "Build data parser"))
      (is (not (string/includes? result1 "Build data parser")))
      (is (not (string/includes? result2 "Create fibonacci function"))))))

(deftest defaults-test
  (testing "Default configuration values"
    (is (= "claude-haiku-4.5" ci/default-model))
    (is (= 30 ci/default-max-turns))
    (is (= :instructions-selector ci/default-instructions))
    
    (testing "Default tool suite includes required tools"
      (is (some #{"clojure_evaluate_code"} ci/default-tool-ids))
      (is (some #{"clojure_symbol_info"} ci/default-tool-ids))
      (is (some #{"clojuredocs_info"} ci/default-tool-ids))
      (is (some #{"clojure_repl_output_log"} ci/default-tool-ids))
      (is (some #{"replace_top_level_form"} ci/default-tool-ids))
      (is (some #{"insert_top_level_form"} ci/default-tool-ids))
      (is (some #{"clojure_create_file"} ci/default-tool-ids))
      (is (some #{"joyride_evaluate_code"} ci/default-tool-ids))
      (is (some #{"copilot_readFile"} ci/default-tool-ids))
      
      (testing "Does not include human_intelligence"
        (is (not (some #{"human_intelligence"} ci/default-tool-ids)))))))

(comment
  ;; Run all tests
  (run-tests 'test.agents.clojure-interactive-test)
  
  ;; Run specific tests
  (interactive-programming-goal-prompt-test)
  (defaults-test)
  
  :rcf)
