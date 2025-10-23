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
