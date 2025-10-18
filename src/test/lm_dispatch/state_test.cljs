; AGENTS, please:
; - remember interactive programming
; - consider TDD in the repl
; - prefer your structural editing tools

(ns test.lm-dispatch.state-test
  (:require
   [lm-dispatch.state :as state]
   [cljs.test :refer [deftest is testing]]))

; To run all tests:
#_(do (require 'run-all-tests :reload) (run-all-tests/run!+))

(deftest conversation-registration
  (testing "Register conversation creates unique IDs"
    (let [id1 (state/register-conversation!
               {:agent.conversation/goal "First task"
                :agent.conversation/model-id "gpt-4o-mini"
                :agent.conversation/max-turns 5
                :agent.conversation/caller "test"})
          id2 (state/register-conversation!
               {:agent.conversation/goal "Second task"
                :agent.conversation/model-id "claude-sonnet-4"
                :agent.conversation/max-turns 10})]
      (is (number? id1) "First ID should be a number")
      (is (number? id2) "Second ID should be a number")
      (is (not= id1 id2) "IDs should be unique")))

  (testing "Conversation has required fields"
    (let [id (state/register-conversation!
              {:agent.conversation/goal "Test goal"
               :agent.conversation/model-id "test-model"
               :agent.conversation/max-turns 3
               :agent.conversation/caller "tester"
               :agent.conversation/title "Test title"})
          conv (state/get-conversation id)]
      (is (= "Test goal" (:agent.conversation/goal conv)))
      (is (= "test-model" (:agent.conversation/model-id conv)))
      (is (= 3 (:agent.conversation/max-turns conv)))
      (is (= "tester" (:agent.conversation/caller conv)))
      (is (= "Test title" (:agent.conversation/title conv)))
      (is (= :started (:agent.conversation/status conv)))
      (is (= 0 (:agent.conversation/current-turn conv)))
      (is (= 0 (:agent.conversation/total-tokens conv)))
      (is (false? (:agent.conversation/cancelled? conv)))
      (is (some? (:agent.conversation/started-at conv)))))

  (testing "Missing caller is preserved as nil"
    (let [id (state/register-conversation!
              {:agent.conversation/goal "Anonymous task"
               :agent.conversation/model-id "model"
               :agent.conversation/max-turns 5})
          conv (state/get-conversation id)]
      (is (nil? (:agent.conversation/caller conv))))))

(deftest conversation-updates
  (testing "Update conversation status"
    (let [id (state/register-conversation!
              {:agent.conversation/goal "Update test"
               :agent.conversation/model-id "model"
               :agent.conversation/max-turns 5})]
      (state/update-conversation! id {:agent.conversation/status :working
                                      :agent.conversation/current-turn 2})
      (let [conv (state/get-conversation id)]
        (is (= :working (:agent.conversation/status conv)))
        (is (= 2 (:agent.conversation/current-turn conv))))))

  (testing "Update preserves other fields"
    (let [id (state/register-conversation!
              {:agent.conversation/goal "Preserve test"
               :agent.conversation/model-id "model"
               :agent.conversation/max-turns 5
               :agent.conversation/caller "tester"})]
      (state/update-conversation! id {:agent.conversation/current-turn 3})
      (let [conv (state/get-conversation id)]
        (is (= "Preserve test" (:agent.conversation/goal conv)))
        (is (= "model" (:agent.conversation/model-id conv)))
        (is (= "tester" (:agent.conversation/caller conv)))
        (is (= 3 (:agent.conversation/current-turn conv)))))))

(deftest conversation-cancellation
  (testing "Mark conversation as cancelled"
    (let [id (state/register-conversation!
              {:agent.conversation/goal "Cancellation test"
               :agent.conversation/model-id "model"
               :agent.conversation/max-turns 5})]
      (state/mark-conversation-cancelled! id)
      (let [conv (state/get-conversation id)]
        (is (true? (:agent.conversation/cancelled? conv)))
        (is (= :cancelled (:agent.conversation/status conv)))))))

(deftest conversation-retrieval
  (testing "Get all conversations"
    (let [id1 (state/register-conversation!
               {:agent.conversation/goal "First"
                :agent.conversation/model-id "model1"
                :agent.conversation/max-turns 5})
          id2 (state/register-conversation!
               {:agent.conversation/goal "Second"
                :agent.conversation/model-id "model2"
                :agent.conversation/max-turns 10})
          all-convs (state/get-all-conversations)]
      (is (>= (count all-convs) 2) "Should have at least 2 conversations")
      (is (some #(= id1 (:agent.conversation/id %)) all-convs))
      (is (some #(= id2 (:agent.conversation/id %)) all-convs)))))

(deftest sidebar-slot-management
  (testing "Get and set sidebar slot"
    (let [initial-slot (state/get-sidebar-slot)]
      (state/set-sidebar-slot! :sidebar-3)
      (is (= :sidebar-3 (state/get-sidebar-slot)))
      ;; Restore initial state if it existed
      (when initial-slot
        (state/set-sidebar-slot! initial-slot)))))

(comment
  ;; Run tests
  (cljs.test/run-tests 'lm-dispatch.state-test)

  :rcf)
