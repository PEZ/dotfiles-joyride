(ns test.ai-workflow.memory-agent-test
  (:require
   [cljs.test :refer [deftest is testing run-tests]]
   [clojure.string :as string]
   [ai-workflow.memory-agent :as ma]))

;; Pure function tests

(deftest determine-search-directory-test
  (testing "Returns global user-data path when scope is :global"
    (let [result (ma/determine-search-directory :global)]
      (is (string? result))
      (is (string/includes? result "prompts"))))

  (testing "Returns workspace instructions path when scope is :workspace"
    (let [result (ma/determine-search-directory :workspace)]
      (is (string? result))
      (is (string/includes? result ".github/instructions"))))

  (testing "Defaults to global directory when scope is nil"
    (let [result (ma/determine-search-directory nil)]
      (is (string? result))
      (is (string/includes? result "prompts"))))

  (testing "Defaults to global directory for invalid scope"
    (let [result (ma/determine-search-directory :invalid)]
      (is (string? result))
      (is (string/includes? result "prompts")))))



(deftest parse-agent-response-test
  (testing "Parses existing file format - returns all fields with :existing-file type"
    (let [response "{:domain \"clojure\" :file-path \"/path/to/file.md\" :file-content \"Content here\"}"
          result (ma/parse-agent-response response)]
      (is (= :existing-file (:type result)))
      (is (= "/path/to/file.md" (:file-path result)))
      (is (= "Content here" (:file-content result)))
      (is (= "clojure" (:domain result)))))

  (testing "Parses new file format - returns all fields with :new-file type"
    (let [response "{:domain \"git\" :file-path \"/path/to/file.md\" :description \"desc\" :domain-tagline \"tagline\" :applyTo [\"**\"] :heading \"heading\" :content \"content\"}"
          result (ma/parse-agent-response response)]
      (is (= :new-file (:type result)))
      (is (= "/path/to/file.md" (:file-path result)))
      (is (= "git" (:domain result)))
      (is (= "desc" (:description result)))
      (is (= "tagline" (:domain-tagline result)))
      (is (= ["**"] (:applyTo result)))
      (is (= "heading" (:heading result)))
      (is (= "content" (:content result)))))

  (testing "Returns nil when file-path is missing"
    (let [response "{:domain \"test\" :content \"Content\"}"
          result (ma/parse-agent-response response)]
      (is (nil? result))))

  (testing "Returns nil when response is not valid EDN"
    (let [response "Not valid EDN at all"
          result (ma/parse-agent-response response)]
      (is (nil? result))))

  (testing "Returns nil when response is not a map"
    (let [response "[\"not\" \"a\" \"map\"]"
          result (ma/parse-agent-response response)]
      (is (nil? result)))))

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
  (determine-search-directory-test)
  (parse-agent-response-test)
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
      (is (string/includes? result "/my/custom/dir"))
      (is (not (string/includes? result "{SEARCH_DIRECTORY}")))))

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
