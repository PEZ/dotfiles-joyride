(ns test.ai-workflow.memory-agent-test
  (:require
   [cljs.test :refer [deftest is testing run-tests]]
   [clojure.string :as string]
   [ai-workflow.memory-agent :as ma]))

(deftest append-memory-section-test
  (testing "Appends new section to existing content"
    (let [existing "---\ndescription: 'Test'\napplyTo: '**'\n---\n\n# Test\n\n## Old Section\n\nOld content"
          result (ma/append-memory-section
                  {:existing-content existing
                   :heading "New Section"
                   :content "New content here"})]
      (is (string/includes? result "## Old Section"))
      (is (string/includes? result "## New Section"))
      (is (string/includes? result "New content here"))))

  (testing "Updates applyTo frontmatter when provided"
    (let [existing "---\ndescription: 'Test'\napplyTo: '**/*.clj'\n---\n\n# Test"
          result (ma/append-memory-section
                  {:existing-content existing
                   :heading "New Section"
                   :content "Content"
                   :applyTo ["**/*.clj" "**/*.cljs"]})]
      (is (string/includes? result "applyTo: '**/*.clj, **/*.cljs'"))
      (is (string/includes? result "## New Section"))))

  (testing "Leaves frontmatter unchanged when applyTo not provided"
    (let [existing "---\ndescription: 'Test'\napplyTo: '**/*.clj'\n---\n\n# Test"
          result (ma/append-memory-section
                  {:existing-content existing
                   :heading "New Section"
                   :content "Content"})]
      (is (string/includes? result "applyTo: '**/*.clj'"))
      (is (not (string/includes? result "**/*.cljs"))))))

(deftest trim-heading-from-content-test
  (testing "Removes H2 heading when content starts with same heading"
    (let [result (ma/trim-heading-from-content
                  "Avoid Shadowing Built-ins"
                  "## Avoid Shadowing Built-ins\n\nContent here")]
      (is (= "Content here" result))
      (is (not (string/includes? result "##")))))

  (testing "Handles extra whitespace after heading"
    (let [result (ma/trim-heading-from-content
                  "My Heading"
                  "## My Heading\n\n\nContent with blank lines")]
      (is (= "Content with blank lines" result))))

  (testing "Leaves content unchanged when heading doesn't match"
    (let [result (ma/trim-heading-from-content
                  "Expected Heading"
                  "## Different Heading\n\nContent")]
      (is (= "## Different Heading\n\nContent" result))))

  (testing "Leaves content unchanged when no heading present"
    (let [result (ma/trim-heading-from-content
                  "Some Heading"
                  "Just plain content without heading")]
      (is (= "Just plain content without heading" result))))

  (testing "Handles H3 and higher headings - leaves them alone"
    (let [result (ma/trim-heading-from-content
                  "Main Heading"
                  "### Subsection\n\nContent")]
      (is (= "### Subsection\n\nContent" result)))))

(deftest append-memory-section-prevents-duplicate-heading-test
  (testing "Prevents duplicate headings when agent includes heading in content"
    (let [existing "---\ndescription: 'Test'\n---\n\n# Test\n\n## First"
          result (ma/append-memory-section
                  {:existing-content existing
                   :heading "New Section"
                   :content "## New Section\n\nActual content"})]
      ;; Should only have ONE instance of "## New Section"
      (is (= 1 (count (re-seq #"## New Section" result))))
      (is (string/includes? result "Actual content"))))

  (testing "Works normally when content doesn't start with heading"
    (let [existing "---\ndescription: 'Test'\n---\n\n# Test"
          result (ma/append-memory-section
                  {:existing-content existing
                   :heading "New Section"
                   :content "Just content without heading"})]
      (is (= 1 (count (re-seq #"## New Section" result))))
      (is (string/includes? result "Just content without heading")))))

(comment
  ;; Run all tests
  (run-tests 'test.ai-workflow.memory-agent-test)

  ;; Run specific test
  (append-memory-section-test)
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

(deftest agent-misidentifies-existing-as-new-test
  (testing "When agent says :new-file true but file exists"
    (let [existing-content "---\ndescription: 'Old'\napplyTo: '**/*.clj'\n---\n\n# Old\n\n## Section 1\n\nOld content"
          parsed-as-new {:new-file true
                         :domain "test"
                         :file-path "/fake/path.md"
                         :heading "New Section"
                         :content "New content"
                         :applyTo ["**/*.cljs"]}]
      ;; Simulate the fixed logic
      (testing "Should append instead of overwrite"
        (let [result (ma/append-memory-section
                      {:existing-content existing-content
                       :heading (:heading parsed-as-new)
                       :content (:content parsed-as-new)
                       :applyTo nil})] ; Should ignore applyTo
          (is (string/includes? result "## Section 1")
              "Should preserve existing sections")
          (is (string/includes? result "## New Section")
              "Should add new section")
          (is (string/includes? result "applyTo: '**/*.clj'")
              "Should preserve original applyTo (not update to .cljs)")
          (is (not (string/includes? result "**/*.cljs"))
              "Should not include agent's suggested applyTo"))))))

(deftest extract-file-descriptions-test
  (testing "Extracts descriptions from instruction files"
    (testing "Should extract description from frontmatter"
      (let [content "---\ndescription: 'Test description here'\napplyTo: '**/*.clj'\n---\n\n# Content"]
        (is (= "Test description here"
               (ma/extract-description-from-content content)))))

    (testing "Should handle missing description"
      (let [content "---\napplyTo: '**/*.clj'\n---\n\n# Content"]
        (is (nil? (ma/extract-description-from-content content)))))

    (testing "Should handle malformed frontmatter"
      (let [content "# Just a heading\n\nNo frontmatter"]
        (is (nil? (ma/extract-description-from-content content)))))))

(deftest format-description-listing-test
  (testing "Formats description listing for prompt"
    (testing "Should format non-empty descriptions"
      (let [descriptions [{:file "clojure-memory.instructions.md"
                           :description "Clojure best practices"}
                          {:file "git-workflow-memory.instructions.md"
                           :description "Git workflow patterns"}]
            result (ma/format-description-listing descriptions "/test/dir")]
        (is (string/includes? result "## Available Memory Files"))
        (is (string/includes? result "clojure-memory.instructions.md"))
        (is (string/includes? result "Clojure best practices"))
        (is (string/includes? result "git-workflow-memory.instructions.md"))
        (is (string/includes? result "Git workflow patterns"))
        (is (string/includes? result "/test/dir/clojure-memory.instructions.md"))))

    (testing "Should return empty string for empty descriptions"
      (let [result (ma/format-description-listing [] "/test/dir")]
        (is (= "" result))))))

(deftest integration-description-listing-test
  (testing "Integration: description listing in goal prompt"
    (testing "Should include file descriptions in prompt"
      (let [test-dir "/fake/dir"
            descriptions [{:file "clojure-memory.instructions.md"
                           :description "Clojure patterns"}
                          {:file "git-workflow-memory.instructions.md"
                           :description "Git workflows"}]
            listing (ma/format-description-listing descriptions test-dir)
            prompt (ma/build-goal-prompt {:ma/summary "Test"
                                          :ma/domain nil
                                          :ma/search-dir test-dir})
            full-goal (str prompt listing)]
        (is (string/includes? full-goal "## Available Memory Files"))
        (is (string/includes? full-goal "clojure-memory.instructions.md"))
        (is (string/includes? full-goal "Review the \"Available Memory Files\" section"))
        (is (not (string/includes? full-goal "Search all `{SEARCH-DIRECTORY}/*.instructions.md`"))
            "Should not contain old search instruction")))))

(deftest extract-edn-from-response-test
  (testing "Extracts EDN from wrapped response"
    (testing "Should extract from BEGIN/END markers"
      (let [response "Some preamble text\n---BEGIN RESULTS---\n{:domain \"test\" :file-path \"/test.md\"}\n---END RESULTS---\nSome trailing text"
            result (ma/extract-edn-from-response response)]
        (is (= {:domain "test" :file-path "/test.md"} result))))

    (testing "Should handle response with just EDN"
      (let [response "{:domain \"test\" :file-path \"/test.md\"}"
            result (ma/extract-edn-from-response response)]
        (is (= {:domain "test" :file-path "/test.md"} result))))

    (testing "Should return nil for invalid EDN"
      (let [response "---BEGIN RESULTS---\nNot valid EDN\n---END RESULTS---"
            result (ma/extract-edn-from-response response)]
        (is (nil? result))))))

(deftest extract-edn-from-multi-turn-conversation-test
  (testing "Extracts EDN from multiple agent messages"
    (testing "Should find EDN in middle message when agent continues in later turns"
      (let [;; Simulate multi-turn conversation
            msg1 "Let me analyze this..."
            msg2 "~~~CONTINUING~~~\n\n---BEGIN RESULTS---\n{:domain \"test\" :file-path \"/test.md\" :heading \"Test\" :content \"Content\"}\n---END RESULTS---"
            msg3 "~~~GOAL-ACHIEVED~~~"
            combined (str msg1 "\n\n" msg2 "\n\n" msg3)
            result (ma/extract-edn-from-response combined)]
        (is (= {:domain "test" :file-path "/test.md" :heading "Test" :content "Content"} result))))

    (testing "Should handle EDN in first message"
      (let [msg1 "---BEGIN RESULTS---\n{:domain \"test\" :file-path \"/test.md\"}\n---END RESULTS---"
            msg2 "Task complete!"
            combined (str msg1 "\n\n" msg2)
            result (ma/extract-edn-from-response combined)]
        (is (= {:domain "test" :file-path "/test.md"} result))))

    (testing "Should handle EDN in last message"
      (let [msg1 "Analyzing..."
            msg2 "---BEGIN RESULTS---\n{:domain \"test\" :file-path \"/test.md\"}\n---END RESULTS---"
            combined (str msg1 "\n\n" msg2)
            result (ma/extract-edn-from-response combined)]
        (is (= {:domain "test" :file-path "/test.md"} result))))

    (testing "Should return nil when no EDN in any message"
      (let [msg1 "Analyzing..."
            msg2 "Still working..."
            msg3 "Done!"
            combined (str msg1 "\n\n" msg2 "\n\n" msg3)
            result (ma/extract-edn-from-response combined)]
        (is (nil? result))))))
