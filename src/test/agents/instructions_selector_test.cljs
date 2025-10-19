; AGENTS, please:
; - remember interactive programming
; - consider TDD in the repl
; - prefer your structural editing tools

(ns test.agents.instructions-selector-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [clojure.string :as string]
   [lm-dispatch.agent :as agent]
   [lm-dispatch.instructions-util :as instr-util]
   [promesa.core :as p]))

; To run all tests:
#_(do (require 'run-all-tests :reload) (run-all-tests/run!+))

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
    (p/let [result (agent/concatenate-instruction-files!+ [])]
      (is (= "" result)
          "Should return empty string for empty list")))

  (testing "Concatenates files with proper separators"
    (p/let [;; Get some actual instruction files
            user-path (instr-util/user-data-instructions-path)
            files (instr-util/list-instruction-files!+ user-path)
            test-files (take 2 files)
            result (agent/concatenate-instruction-files!+ test-files)]
      (is (string? result)
          "Should return a string")
      (is (string/includes? result "# From:")
          "Should include separator")
      (is (> (count result) 0)
          "Should have content"))))

(deftest collect-all-instruction-descriptions-test
  (testing "Collects descriptions from workspace and global areas"
    (p/let [descriptions (agent/collect-all-instruction-descriptions!+)]
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

(deftest prepare-instructions-with-selection-test
  (testing "Handles empty context-files"
    (p/let [result (agent/prepare-instructions-with-selection!+
                    {:goal "Test goal"
                     :context-files []})]
      (is (string? result)
          "Should return a string")))

  (testing "Includes context-files with separator"
    (p/let [;; Use an actual instruction file as context
            user-path (instr-util/user-data-instructions-path)
            files (instr-util/list-instruction-files!+ user-path)
            context-file (first files)
            result (agent/prepare-instructions-with-selection!+
                    {:goal "Test goal with context"
                     :context-files [context-file]})]
      (is (string/includes? result "# === Context Files ===")
          "Should include context separator when context-files provided")
      (is (string/includes? result "# From:")
          "Should include file separators"))))
