(ns test.ai-workflow.memory-agent-test
  (:require
   [cljs.test :refer [deftest is testing run-tests]]
   [clojure.string :as string]
   [ai-workflow.memory-agent :as ma]))

(deftest validate-file-content-test
  (testing "Accepts new files (nil existing content)"
    (is (:valid? (ma/validate-file-content "new content" nil))))

  (testing "Accepts new files (empty existing content)"
    (is (:valid? (ma/validate-file-content "new content" ""))))

  (testing "Accepts content with same or more lines"
    (let [existing "Line 1\nLine 2\nLine 3\nLine 4\nLine 5"
          new-same "Line 1\nLine 2\nLine 3\nLine 4\nLine 5"
          new-more "Line 1\nLine 2\nLine 3\nLine 4\nLine 5\nLine 6"]
      (is (:valid? (ma/validate-file-content new-same existing)))
      (is (:valid? (ma/validate-file-content new-more existing)))))

  (testing "Accepts content with 75% or more lines (minor edits)"
    (let [existing "Line 1\nLine 2\nLine 3\nLine 4"
          new-content "Line 1\nLine 2\nLine 3"]
      (is (:valid? (ma/validate-file-content new-content existing)))))

  (testing "Rejects content significantly shorter (< 75%)"
    (let [existing "Line 1\nLine 2\nLine 3\nLine 4\nLine 5"
          incomplete "Line 1\nLine 2"
          result (ma/validate-file-content incomplete existing)]
      (is (not (:valid? result)))
      (is (string/includes? (:reason result) "VALIDATION FAILED"))
      (is (string/includes? (:reason result) "2 lines"))
      (is (string/includes? (:reason result) "5 lines")))))

(comment
  ;; Run all tests
  (run-tests 'test.ai-workflow.memory-agent-test)

  ;; Run specific test
  (validate-file-content-test)
  (build-goal-prompt-test)

  :rcf)

(deftest build-goal-prompt-test
  (testing "With domain - includes domain tag and lesson in SESSION-LESSON"
    (let [result (ma/build-goal-prompt
                  {:ma/summary "Use REPL for debugging"
                   :ma/domain "clojure"
                   :ma/search-dir "/test/prompts"})]
      (is (string? result))
      (is (string/includes? result "<DOMAIN>clojure</DOMAIN>"))
      (is (string/includes? result "<SESSION-LESSON>"))
      (is (string/includes? result "Use REPL for debugging"))
      (is (string/includes? result "</SESSION-LESSON>"))
      (is (string/includes? result "/test/prompts"))))

  (testing "Without domain - includes determine step, no domain tag"
    (let [result (ma/build-goal-prompt
                  {:ma/summary "Verify API responses"
                   :ma/domain nil
                   :ma/search-dir "/test/prompts"})]
      (is (string? result))
      (is (not (string/includes? result "<DOMAIN>")))
      (is (string/includes? result "Determine the memory domain"))
      (is (string/includes? result "<SESSION-LESSON>"))
      (is (string/includes? result "Verify API responses"))
      (is (string/includes? result "</SESSION-LESSON>"))))

  (testing "Lesson appears in SESSION-LESSON section"
    (let [result (ma/build-goal-prompt
                  {:ma/summary "Verify API responses"
                   :ma/domain nil
                   :ma/search-dir "/test/prompts"})
          lesson (second (re-find #"<SESSION-LESSON>\s*(.+?)\s*</SESSION-LESSON>" result))]
      (is (= lesson "Verify API responses"))))

  (testing "Search directory replacement - no placeholder remains"
    (let [result (ma/build-goal-prompt
                  {:ma/summary "Test"
                   :ma/domain "git"
                   :ma/search-dir "/my/custom/dir"})]
      (is (string/includes? result "/my/custom/dir")
          "Should contain actual search directory path")
      (is (not (string/includes? result "{SEARCH-DIRECTORY}"))
          "Should not contain unreplaced {SEARCH-DIRECTORY} placeholder")
      (is (not (string/includes? result "{SEARCH_DIRECTORY}"))
          "Should not contain old {SEARCH_DIRECTORY} placeholder either")))

  (testing "Different domains produce domain-specific prompts"
    (let [clj-result (ma/build-goal-prompt
                      {:ma/summary "Test"
                       :ma/domain "clojure"
                       :ma/search-dir "/dir"})
          git-result (ma/build-goal-prompt
                      {:ma/summary "Test"
                       :ma/domain "git-workflow"
                       :ma/search-dir "/dir"})]
      (is (string/includes? clj-result "<DOMAIN>clojure</DOMAIN>"))
      (is (string/includes? git-result "<DOMAIN>git-workflow</DOMAIN>"))
      (is (string/includes? clj-result "clojure.instructions.md"))
      (is (string/includes? git-result "git-workflow.instructions.md")))))

(deftest build-new-file-content-test
  (testing "Builds complete file with frontmatter"
    (let [new-file-data {:description "Git workflow patterns"
                         :domain-tagline "Git Workflow Memory"
                         :applyTo ["**"]
                         :heading "Rebase with Autostash"
                         :content "Use --autostash flag"}
          result (ma/build-new-file-content new-file-data)]
      (is (string/includes? result "---"))
      (is (string/includes? result "description: 'Git workflow patterns'"))
      (is (string/includes? result "applyTo: '**'"))
      (is (string/includes? result "# Git Workflow Memory"))
      (is (string/includes? result "## Rebase with Autostash"))
      (is (string/includes? result "Use --autostash flag"))))

  (testing "Handles multiple applyTo patterns"
    (let [new-file-data {:description "test"
                         :domain-tagline "Test"
                         :applyTo ["**/*.clj" "**/*.cljs"]
                         :heading "Test"
                         :content "content"}
          result (ma/build-new-file-content new-file-data)]
      (is (string/includes? result "applyTo: '**/*.clj, **/*.cljs'"))))

  (testing "Preserves multiline content"
    (let [new-file-data {:description "test"
                         :domain-tagline "Test"
                         :applyTo ["**"]
                         :heading "Test"
                         :content "Line 1\nLine 2\nLine 3"}
          result (ma/build-new-file-content new-file-data)]
      (is (string/includes? result "Line 1\nLine 2\nLine 3")))))
