;; AGENTS, please read this preamble before working with the namespace:
;; - Use interactive programming
;; - Work using TDD in the repl
;; - Always prefer your structural editing tools

(ns test.lm-dispatch.util-test
  (:require
   [lm-dispatch.util :as util]
   [cljs.test :refer [deftest is testing]]))

;; To run all tests:
#_(do (require 'run-all-tests :reload) (run-all-tests/run!+))

(deftest create-chat-message-test
  (testing "Creates proper JS message objects with correct role strings"
    (let [system-msg (util/create-chat-message {:role :system :content "System prompt"})
          user-msg (util/create-chat-message {:role :user :content "User input"})
          assistant-msg (util/create-chat-message {:role :assistant :content "Assistant response"})]

      (is (= "system" (.-role system-msg))
          "System role should be 'system' string")
      (is (= "System prompt" (.-content system-msg))
          "System content should match input")

      (is (= "user" (.-role user-msg))
          "User role should be 'user' string")
      (is (= "User input" (.-content user-msg))
          "User content should match input")

      (is (= "assistant" (.-role assistant-msg))
          "Assistant role should be 'assistant' string")
      (is (= "Assistant response" (.-content assistant-msg))
          "Assistant content should match input"))))

(deftest build-message-chain-test
  (testing "Builds message chain with system prompt first"
    (let [messages [{:role :user :content "First user message"}
                    {:role :assistant :content "First assistant response"}]
          chain (util/build-message-chain {:system-prompt "System instructions"
                                           :messages messages})]

      (is (= 3 (count chain))
          "Should have 3 messages: system + 2 user messages")
      (is (= "system" (.-role (first chain)))
          "First message should be system")
      (is (= "System instructions" (.-content (first chain)))
          "System message should have correct content")
      (is (= "user" (.-role (second chain)))
          "Second message should be user")
      (is (= "assistant" (.-role (nth chain 2)))
          "Third message should be assistant")))

  (testing "Builds message chain with nil system prompt"
    (let [messages [{:role :user :content "User message"}]
          chain (util/build-message-chain {:system-prompt nil
                                           :messages messages})]

      (is (= 2 (count chain))
          "Should have system message (with nil content) + user message")
      (is (= "system" (.-role (first chain)))
          "First message should be system")
      (is (nil? (.-content (first chain)))
          "System message should have nil content")
      (is (= "user" (.-role (second chain)))
          "Second message should be user"))))

(deftest filter-available-tools-test
  (testing "Filters out unsafe write tools"
    (let [mock-tools #js [#js {:name "copilot_readFile"}
                          #js {:name "copilot_createFile"}
                          #js {:name "copilot_findFiles"}
                          #js {:name "copilot_insertEdit"}
                          #js {:name "copilot_editNotebook"}
                          #js {:name "joyride_evaluate_code"}]
          filtered (util/filter-available-tools mock-tools)]

      (is (= 3 (count filtered))
          "Should keep only safe read-only tools")

      (let [filtered-names (set (map #(.-name %) filtered))]
        (is (contains? filtered-names "copilot_readFile")
            "Should keep readFile")
        (is (contains? filtered-names "copilot_findFiles")
            "Should keep findFiles")
        (is (contains? filtered-names "joyride_evaluate_code")
            "Should keep joyride_evaluate_code")
        (is (not (contains? filtered-names "copilot_createFile"))
            "Should filter out createFile")
        (is (not (contains? filtered-names "copilot_insertEdit"))
            "Should filter out insertEdit")
        (is (not (contains? filtered-names "copilot_editNotebook"))
            "Should filter out editNotebook"))))

  (testing "Returns empty when all tools are unsafe"
    (let [mock-tools #js [#js {:name "copilot_createFile"}
                          #js {:name "copilot_insertEdit"}]
          filtered (util/filter-available-tools mock-tools)]

      (is (= 0 (count filtered))
          "Should return empty array when all tools are unsafe"))))
