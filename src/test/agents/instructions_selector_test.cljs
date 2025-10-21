;; AGENTS, please read this preamble before working with the namespace:
;; - Use interactive programming
;; - Work using TDD in the repl
;; - Always prefer your structural editing tools

(ns test.agents.instructions-selector-test
  (:require
   [agents.instructions-selector :as selector]
   [cljs.test :refer [deftest is testing]]
   [clojure.string :as string]
   [lm-dispatch.instructions-util :as instr-util]
   [promesa.core :as p]))

;; To run all tests:
#_(do (require 'run-all-tests :reload-all-all) (run-all-tests/run!+))

(deftest extract-domain-from-filename-test
  (testing "Extracts domain from hyphenated filenames"
    (is (= "clojure" (instr-util/extract-domain-from-filename "clojure-memory.instructions.md"))
        "Should extract 'clojure' from clojure-memory")
    (is (= "shadow-cljs" (instr-util/extract-domain-from-filename "shadow-cljs-memory.instructions.md"))
        "Should extract 'shadow-cljs' from shadow-cljs-memory")
    (is (= "pez" (instr-util/extract-domain-from-filename "pez-preferences.instructions.md"))
        "Should extract 'pez' from pez-preferences"))

  (testing "Extracts domain from simple filenames"
    (is (= "joyride" (instr-util/extract-domain-from-filename "joyride.instructions.md"))
        "Should extract 'joyride' from simple filename"))

  (testing "Filters reserved 'memory' domain"
    (is (nil? (instr-util/extract-domain-from-filename "memory.instructions.md"))
        "Should return nil for reserved 'memory' domain"))

  (testing "Returns nil for non-instruction files"
    (is (nil? (instr-util/extract-domain-from-filename "not-an-instruction.md"))
        "Should return nil for non-matching files")
    (is (nil? (instr-util/extract-domain-from-filename "readme.md"))
        "Should return nil for regular markdown")))

(deftest concatenate-instruction-files-test
  (testing "Returns empty string for empty file list"
    (p/let [result (instr-util/concatenate-instruction-files!+ [])]
      (is (= "" result)
          "Should return empty string for empty list")))

  (testing "Concatenates files with proper separators"
    (p/let [;; Get some actual instruction files
            user-path (instr-util/user-data-instructions-path)
            files (instr-util/list-instruction-files!+ user-path)
            test-files (take 2 files)
            result (instr-util/concatenate-instruction-files!+ test-files)]
      (is (string? result)
          "Should return a string")
      (is (string/includes? result "# From:")
          "Should include separator")
      (is (> (count result) 0)
          "Should have content"))))

(deftest collect-all-instruction-descriptions-test
  (testing "Collects descriptions from workspace and global areas"
    (p/let [descriptions (instr-util/collect-all-instruction-descriptions!+)]
      (is (vector? descriptions)
          "Should return a vector")
      (is (> (count descriptions) 0)
          "Should find at least some instruction files")
      (is (every? #(contains? % :file) descriptions)
          "Each description should have :file key")
      (is (every? #(contains? % :filename) descriptions)
          "Each description should have :filename key")
      (is (every? #(contains? % :description) descriptions)
          "Each description should have :description key")
      (is (every? #(contains? % :domain) descriptions)
          "Each description should have :domain key"))))



(deftest build-selection-prompt-test
  (testing "Includes tool section when tool-ids provided"
    (let [prompt (selector/build-selection-prompt
                  {:goal "Test goal"
                   :file-descriptions [{:file "/test.md" :description "Test"}]
                   :tool-ids ["copilot_readFile" "copilot_writeFile"]})]
      (is (string/includes? prompt "## Available Tools")
          "Should include Available Tools section")
      (is (string/includes? prompt "copilot_readFile")
          "Should list first tool")
      (is (string/includes? prompt "copilot_writeFile")
          "Should list second tool")))

  (testing "Omits tool section when no tool-ids"
    (let [prompt (selector/build-selection-prompt
                  {:goal "Test goal"
                   :file-descriptions [{:file "/test.md" :description "Test"}]})]
      (is (not (string/includes? prompt "## Available Tools"))
          "Should not include Available Tools section")))

  (testing "Includes context section when provided"
    (let [prompt (selector/build-selection-prompt
                  {:goal "Test goal"
                   :file-descriptions [{:file "/test.md" :description "Test"}]
                   :context-content "Some context"})]
      (is (string/includes? prompt "## Additional Context")
          "Should include Additional Context section")
      (is (string/includes? prompt "Some context")
          "Should include context content")))

  (testing "Always includes core sections"
    (let [prompt (selector/build-selection-prompt
                  {:goal "Test goal"
                   :file-descriptions [{:file "/test.md" :description "Test"}]})]
      (is (string/includes? prompt "TASK-GOAL")
          "Should include task goal marker")
      (is (string/includes? prompt "Test goal")
          "Should include actual goal text")
      (is (string/includes? prompt "Available Instruction Files")
          "Should include file descriptions section")
      (is (string/includes? prompt "GOAL-ACHIEVED")
          "Should include completion marker"))))

(deftest parse-selection-result-test
  (testing "Parses valid file paths"
    (let [response {:text "/path/to/file1.instructions.md\n/path/to/file2.instructions.md"}
          result (selector/parse-selection-result response)]
      (is (= 2 (count result))
          "Should parse two paths")
      (is (= "/path/to/file1.instructions.md" (first result))
          "Should preserve first path")
      (is (= "/path/to/file2.instructions.md" (second result))
          "Should preserve second path")))

  (testing "Filters out non-instruction paths"
    (let [response {:text "/path/to/file1.instructions.md\n/path/to/other.md\n/path/to/file2.instructions.md"}
          result (selector/parse-selection-result response)]
      (is (= 2 (count result))
          "Should only include .instructions.md files")
      (is (every? #(string/ends-with? % ".instructions.md") result)
          "All results should end with .instructions.md")))

  (testing "Handles empty response"
    (is (empty? (selector/parse-selection-result {:text ""}))
        "Should return empty vector for empty text")
    (is (empty? (selector/parse-selection-result nil))
        "Should return empty vector for nil"))

  (testing "Filters relative paths"
    (let [response {:text "relative/path.instructions.md\n/absolute/path.instructions.md"}
          result (selector/parse-selection-result response)]
      (is (= 1 (count result))
          "Should only include absolute paths")
      (is (string/starts-with? (first result) "/")
          "Result should be absolute path"))))
