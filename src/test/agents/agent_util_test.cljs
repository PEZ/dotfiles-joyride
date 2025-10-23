;; AGENTS, please read this preamble before working with the namespace:
;; - Use interactive programming
;; - Work using TDD in the repl
#_(do (require 'run-all-tests :reload) (run-all-tests/run!+))
;; - Always prefer your structural editing tools

(ns test.agents.agent-util-test
  (:require
   [cljs.test :refer [deftest is testing run-tests]]
   [clojure.string :as string]
   [agents.agent-util :as agent-util]))

(deftest extract-description-from-content-test
  (testing "Extracts descriptions from instruction files"
    (testing "Should extract description from frontmatter"
      (let [content "---\ndescription: 'Test description here'\napplyTo: '**/*.clj'\n---\n\n# Content"]
        (is (= "Test description here"
               (agent-util/extract-description-from-content content)))))

    (testing "Should handle missing description"
      (let [content "---\napplyTo: '**/*.clj'\n---\n\n# Content"]
        (is (nil? (agent-util/extract-description-from-content content)))))

    (testing "Should handle malformed frontmatter"
      (let [content "# Just a heading\n\nNo frontmatter"]
        (is (nil? (agent-util/extract-description-from-content content)))))))

(deftest format-description-listing-test
  (testing "Formats description listing for prompt"
    (testing "Should format as EDN in code block"
      (let [descriptions [{:file "clojure-memory.instructions.md"
                           :description "Clojure best practices"}
                          {:file "git-workflow-memory.instructions.md"
                           :description "Git workflow patterns"}]
            result (agent-util/format-description-listing descriptions)]
        (is (string/includes? result "```clojure"))
        (is (string/includes? result ":file \"clojure-memory.instructions.md\""))
        (is (string/includes? result ":description \"Clojure best practices\""))
        (is (string/includes? result "```\n"))))

    (testing "Should return nil for empty descriptions"
      (let [result (agent-util/format-description-listing [])]
        (is (nil? result))))))

(deftest normalize-scope-test
  (testing "Normalizes keyword scope values"
    (is (= :global (agent-util/normalize-scope :global)))
    (is (= :workspace (agent-util/normalize-scope :workspace))))

  (testing "Normalizes string scope values"
    (is (= :global (agent-util/normalize-scope "global")))
    (is (= :global (agent-util/normalize-scope "user")))
    (is (= :workspace (agent-util/normalize-scope "workspace")))
    (is (= :workspace (agent-util/normalize-scope "ws"))))

  (testing "Defaults to global for invalid or nil values"
    (is (= :global (agent-util/normalize-scope nil)))
    (is (= :global (agent-util/normalize-scope "invalid")))
    (is (= :global (agent-util/normalize-scope 123)))))

(deftest file-path->uri-string-test
  (testing "Converts filesystem path to URI string"
    (let [path "/Users/test/file.md"
          result (agent-util/file-path->uri-string path)]
      (is (string/starts-with? result "file://"))
      (is (string/includes? result "/Users/test/file.md"))))

  (testing "Returns URI string unchanged (idempotent)"
    (let [uri "file:///Users/test/file.md"
          result (agent-util/file-path->uri-string uri)]
      (is (= uri result))))

  (testing "Handles different path formats"
    (let [path "/path/to/memory.instructions.md"
          result (agent-util/file-path->uri-string path)]
      (is (string/starts-with? result "file://"))
      (is (string/includes? result "/path/to/memory.instructions.md")))))

(comment
  ;; Run all tests
  (run-tests 'test.agents.agent-util-test)

  ;; Run specific tests
  (extract-description-from-content-test)
  (format-description-listing-test)
  (normalize-scope-test)
  (file-path->uri-string-test)

  :rcf)

(deftest find-message-with-marker-test
  (testing "Finds message containing marker searching backwards"
    (let [messages [{:role :assistant :content "First message"}
                    {:role :assistant :content "Second message"}
                    {:role :assistant :content "Third with ---MARKER---"}]
          result (agent-util/find-message-with-marker messages "---MARKER---")]

      (is (= "Third with ---MARKER---" result)
          "Should find the message containing marker")))

  (testing "Returns most recent message when multiple match"
    (let [messages [{:role :assistant :content "First with ---END---"}
                    {:role :assistant :content "Second"}
                    {:role :assistant :content "Third with ---END---"}]
          result (agent-util/find-message-with-marker messages "---END---")]

      (is (= "Third with ---END---" result)
          "Should return most recent (last) matching message")))

  (testing "Returns nil when marker not found"
    (let [messages [{:role :assistant :content "No marker here"}
                    {:role :assistant :content "None here either"}]
          result (agent-util/find-message-with-marker messages "---MARKER---")]

      (is (nil? result)
          "Should return nil when marker not found")))

  (testing "Handles empty message list"
    (let [result (agent-util/find-message-with-marker [] "---MARKER---")]

      (is (nil? result)
          "Should return nil for empty list")))

  (testing "Handles messages with nil content"
    (let [messages [{:role :assistant :content nil}
                    {:role :assistant :content "Has ---MARKER---"}]
          result (agent-util/find-message-with-marker messages "---MARKER---")]

      (is (= "Has ---MARKER---" result)
          "Should skip nil content and find marker"))))

(deftest extract-marked-content-test
  (testing "Extracts content when markers are present"
    (let [agent-result {:history [{:role :assistant
                                   :content "Working...\n---BEGIN REPORT---\n## Achievement\nDone!\n---END REPORT---\n~~~GOAL~~~"}]}
          result (agent-util/extract-marked-content agent-result "---BEGIN REPORT---" "---END REPORT---")]

      (is (= "## Achievement\nDone!" (:content result))
          "Should extract content between markers")
      (is (nil? (:extraction-failed result))
          "Should not mark extraction as failed")))

  (testing "Returns debug info when markers are missing"
    (let [agent-result {:history [{:role :assistant :content "No markers"}
                                  {:role :assistant :content "Still none"}]}
          result (agent-util/extract-marked-content agent-result "---BEGIN X---" "---END X---")]

      (is (:extraction-failed result)
          "Should mark extraction as failed")
      (is (nil? (:content result))
          "Should not include content")
      (is (map? (:debug-info result))
          "Should include debug-info map")
      (is (= 2 (get-in result [:debug-info :total-messages]))
          "Should count total messages")
      (is (= 2 (get-in result [:debug-info :assistant-messages]))
          "Should count assistant messages")
      (is (false? (get-in result [:debug-info :has-end-marker?]))
          "Should indicate end marker not found")))

  (testing "Handles empty history"
    (let [agent-result {:history []}
          result (agent-util/extract-marked-content agent-result "---BEGIN---" "---END---")]

      (is (:extraction-failed result))
      (is (= 0 (get-in result [:debug-info :total-messages])))
      (is (= 0 (get-in result [:debug-info :assistant-messages])))))

  (testing "Finds content in last message when multiple exist"
    (let [agent-result {:history [{:role :assistant :content "First message"}
                                  {:role :tool-results :processed-results ["result"]}
                                  {:role :assistant :content "---BEGIN DATA---\nFinal\n---END DATA---"}]}
          result (agent-util/extract-marked-content agent-result "---BEGIN DATA---" "---END DATA---")]

      (is (= "Final" (:content result)))
      (is (nil? (:extraction-failed result)))))

  (testing "Handles special regex characters in markers"
    (let [agent-result {:history [{:role :assistant :content "Text\n{BEGIN}\nContent\n{END}\nMore"}]}
          result (agent-util/extract-marked-content agent-result "{BEGIN}" "{END}")]

      (is (= "Content" (:content result))
          "Should handle curly braces in markers")))

  (testing "Works with EDN results (memory-keeper style)"
    (let [agent-result {:history [{:role :assistant
                                   :content "---BEGIN RESULTS---\n{:domain \"test\"}\n---END RESULTS---"}]}
          result (agent-util/extract-marked-content agent-result "---BEGIN RESULTS---" "---END RESULTS---")]

      (is (= "{:domain \"test\"}" (:content result))))))
