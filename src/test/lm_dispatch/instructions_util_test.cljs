;; AGENTS, please read this preamble before working with the namespace:
;; - Use interactive programming
;; - Work using TDD in the repl
;; - Always prefer your structural editing tools

(ns test.lm-dispatch.instructions-util-test
  (:require
   [cljs.test :refer [deftest is testing async]]
   [clojure.string :as string]
   [promesa.core :as p]
   [lm-dispatch.instructions-util :as instr-util]))

;; To run all tests:
#_(do (require 'run-all-tests :reload) (run-all-tests/run!+))

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
                         {:editor-context/file-path "/Users/pez/.config/joyride/src/test/testing-files/sample_code.foobar"
                          :editor-context/selection-start-line 8
                          :editor-context/selection-end-line 10})]
           (is (some? result)
               "Should return enriched map")
           (is (= "/Users/pez/.config/joyride/src/test/testing-files/sample_code.foobar"
                  (:editor-context/file-path result))
               "Should preserve file path")
           (is (= 8 (:editor-context/selection-start-line result))
               "Should preserve start line")
           (is (= 10 (:editor-context/selection-end-line result))
               "Should preserve end line")
           (is (some? (:editor-context/full-file-content result))
               "Should have full file content")
           (is (some? (:editor-context/selected-text result))
               "Should have selected text")
           (is (string/includes? (:editor-context/selected-text result) "frobulator")
               "Selection should contain expected content from lines 8-10")
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
                         {:editor-context/file-path "/Users/pez/.config/joyride/src/test/testing-files/sample_code.foobar"})]
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
                         {:editor-context/file-path "/Users/pez/.config/joyride/src/test/testing-files/sample_code.foobar"
                          :editor-context/selection-start-line 10})]
           (is (some? result)
               "Should return map with partial range")
           (is (nil? (:editor-context/selected-text result))
               "Should have nil selection when range incomplete")
           (done))))

(deftest assemble-instructions-filters-editor-context-file-test
  (async done
         (p/let [editor-file "/Users/pez/.config/joyride/src/test/testing-files/sample_code.foobar"
                 context-files ["/Users/pez/.config/joyride/src/test/testing-files/another_sample.foobar"
                                editor-file  ; This should be filtered out
                                "/Users/pez/.config/joyride/src/test/testing-files/sample_instructions.md"]
                 result (instr-util/assemble-instructions!+
                         "Test instructions"
                         {:editor-context/file-path editor-file}
                         context-files)
                 ;; Count how many times sample_code appears as an attachment
                 sample-attachment-count (count (re-seq #"<attachment filePath=\"[^\"]*sample_code\.foobar\">" result))
                 another-in-result (> (.indexOf result "another_sample.foobar") -1)
                 instructions-in-result (> (.indexOf result "sample_instructions.md") -1)]
           (is another-in-result
               "Context file another_sample.foobar should appear in result")
           (is instructions-in-result
               "Context file sample_instructions.md should appear in result")
           (is (= 1 sample-attachment-count)
               "Editor context file should appear exactly once (filtered from context-file-paths)")
           (done))))
