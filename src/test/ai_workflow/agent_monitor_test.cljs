(ns ai-workflow.agent-monitor-test
  (:require
   [ai-workflow.agent-monitor :as monitor]
   [cljs.test :refer [deftest is testing]]))

(deftest conversation-registration
  (testing "Register conversation creates unique IDs"
    (let [id1 (monitor/register-conversation!
               {:agent.conversation/goal "First task"
                :agent.conversation/model-id "gpt-4o-mini"
                :agent.conversation/max-turns 5
                :agent.conversation/caller "test"})
          id2 (monitor/register-conversation!
               {:agent.conversation/goal "Second task"
                :agent.conversation/model-id "claude-sonnet-4"
                :agent.conversation/max-turns 10})]
      (is (number? id1) "First ID should be a number")
      (is (number? id2) "Second ID should be a number")
      (is (not= id1 id2) "IDs should be unique")))

  (testing "Conversation has required fields"
    (let [id (monitor/register-conversation!
              {:agent.conversation/goal "Test goal"
               :agent.conversation/model-id "test-model"
               :agent.conversation/max-turns 3
               :agent.conversation/caller "tester"})
          conv (monitor/get-conversation id)]
      (is (= "Test goal" (:agent.conversation/goal conv)))
      (is (= "test-model" (:agent.conversation/model-id conv)))
      (is (= 3 (:agent.conversation/max-turns conv)))
      (is (= "tester" (:agent.conversation/caller conv)))
      (is (= :started (:agent.conversation/status conv)))
      (is (= 0 (:agent.conversation/current-turn conv)))
      (is (some? (:agent.conversation/started-at conv)))))

  (testing "Missing caller defaults to Unknown"
    (let [id (monitor/register-conversation!
              {:agent.conversation/goal "Anonymous task"
               :agent.conversation/model-id "model"
               :agent.conversation/max-turns 5})
          conv (monitor/get-conversation id)]
      (is (= "Unknown" (:agent.conversation/caller conv))))))

(deftest conversation-updates
  (testing "Update conversation status"
    (let [id (monitor/register-conversation!
              {:agent.conversation/goal "Update test"
               :agent.conversation/model-id "model"
               :agent.conversation/max-turns 5})]
      (monitor/update-conversation! id {:agent.conversation/status :working
                                        :agent.conversation/current-turn 2})
      (let [conv (monitor/get-conversation id)]
        (is (= :working (:agent.conversation/status conv)))
        (is (= 2 (:agent.conversation/current-turn conv))))))

  (testing "Update preserves other fields"
    (let [id (monitor/register-conversation!
              {:agent.conversation/goal "Preserve test"
               :agent.conversation/model-id "model"
               :agent.conversation/max-turns 5
               :agent.conversation/caller "tester"})]
      (monitor/update-conversation! id {:agent.conversation/current-turn 3})
      (let [conv (monitor/get-conversation id)]
        (is (= "Preserve test" (:agent.conversation/goal conv)))
        (is (= "model" (:agent.conversation/model-id conv)))
        (is (= "tester" (:agent.conversation/caller conv)))
        (is (= 3 (:agent.conversation/current-turn conv)))))))

(deftest status-icons
  (testing "Status icon mapping"
    (is (= "⏸️" (monitor/status-icon :started)))
    (is (= "⚙️" (monitor/status-icon :working)))
    (is (= "✅" (monitor/status-icon :done)))
    (is (= "❌" (monitor/status-icon :error)))
    (is (= "❓" (monitor/status-icon :unknown-status)))))

(deftest time-formatting
  (testing "Format time as HH:MM"
    (let [date (js/Date. 2025 9 15 14 5)]  ; Oct 15, 2025, 14:05
      (is (= "14:05" (monitor/format-time date))))

    (let [date (js/Date. 2025 9 15 9 7)]   ; Oct 15, 2025, 09:07
      (is (= "09:07" (monitor/format-time date))))))

(deftest conversation-retrieval
  (testing "Get all conversations"
    (let [id1 (monitor/register-conversation!
               {:agent.conversation/goal "First"
                :agent.conversation/model-id "model1"
                :agent.conversation/max-turns 5})
          id2 (monitor/register-conversation!
               {:agent.conversation/goal "Second"
                :agent.conversation/model-id "model2"
                :agent.conversation/max-turns 10})
          all-convs (monitor/get-all-conversations)]
      (is (>= (count all-convs) 2) "Should have at least 2 conversations")
      (is (some #(= id1 (:agent.conversation/id %)) all-convs))
      (is (some #(= id2 (:agent.conversation/id %)) all-convs)))))

(comment
  ;; Run tests
  (cljs.test/run-tests 'ai-workflow.agent-monitor-test)

  :rcf)
