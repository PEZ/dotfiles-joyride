(ns test.memory-agent2-test
  (:require
   [cljs.test :refer [deftest is testing run-tests]]
   [clojure.string :as string]
   [memory-agent2 :as ma]))

;; Pure function tests

(deftest determine-search-directory-test
  (testing "Returns global directory when scope is :global"
    (is (= "/global/path"
           (ma/determine-search-directory :global "/workspace" "/global/path"))))

  (testing "Returns workspace directory when scope is :workspace and workspace exists"
    (is (= "/workspace"
           (ma/determine-search-directory :workspace "/workspace" "/global/path"))))

  (testing "Falls back to global when scope is :workspace but workspace is nil"
    (is (= "/global/path"
           (ma/determine-search-directory :workspace nil "/global/path"))))

  (testing "Defaults to global directory when scope is nil"
    (is (= "/global/path"
           (ma/determine-search-directory nil "/workspace" "/global/path"))))

  (testing "Defaults to global directory for invalid scope"
    (is (= "/global/path"
           (ma/determine-search-directory :invalid "/workspace" "/global/path")))))

(deftest prepare-context-test
  (testing "Prepends domain marker when domain is provided"
    (is (= ">clojure Use REPL evaluation"
           (ma/prepare-context "Use REPL evaluation" "clojure"))))

  (testing "Returns summary as-is when domain is nil"
    (is (= "Use REPL evaluation"
           (ma/prepare-context "Use REPL evaluation" nil))))

  (testing "Handles empty summary"
    (is (= ""
           (ma/prepare-context "" nil)))
    (is (= ">clojure "
           (ma/prepare-context "" "clojure")))))

(deftest parse-agent-response-test
  (testing "Parses valid response with file path and content"
    (let [response "FILE_PATH: /path/to/file.md\n---FILE_CONTENT---\nContent here\n---END_CONTENT---"
          result (ma/parse-agent-response response)]
      (is (= "/path/to/file.md" (:file-path result)))
      (is (= "Content here\n" (:content result)))))

  (testing "Parses multiline content correctly"
    (let [response "FILE_PATH: /path/to/file.md\n---FILE_CONTENT---\nLine 1\nLine 2\nLine 3\n---END_CONTENT---"
          result (ma/parse-agent-response response)]
      (is (string/includes? (:content result) "Line 1"))
      (is (string/includes? (:content result) "Line 2"))
      (is (string/includes? (:content result) "Line 3"))))

  (testing "Handles extra whitespace in file path"
    (let [response "FILE_PATH:   /path/to/file.md  \n---FILE_CONTENT---\nContent\n---END_CONTENT---"
          result (ma/parse-agent-response response)]
      (is (= "/path/to/file.md" (:file-path result)))))

  (testing "Returns nil when FILE_PATH is missing"
    (let [response "---FILE_CONTENT---\nContent\n---END_CONTENT---"
          result (ma/parse-agent-response response)]
      (is (nil? result))))

  (testing "Returns nil when content markers are missing"
    (let [response "FILE_PATH: /path/to/file.md\nSome content"
          result (ma/parse-agent-response response)]
      (is (nil? result)))))

(comment
  ;; Run all tests
  (run-tests 'test.memory-agent2-test)

  ;; Run specific test
  (determine-search-directory-test)
  (prepare-context-test)
  (parse-agent-response-test)

  :rcf)
