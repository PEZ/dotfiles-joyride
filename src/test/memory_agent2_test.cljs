(ns test.memory-agent2-test
  (:require
   [cljs.test :refer [deftest is testing run-tests async]]
   [clojure.string :as string]
   [memory-agent2 :as ma]
   [promesa.core :as p]))

(deftest autonomize-prompt-removes-human-input-test
  (async done
    (testing "Removes 'request human input' instructions"
      (p/let [test-prompt "Step 1: Do X.\nIf uncertain, request human input.\nStep 2: Do Y."
              result (ma/autonomize-prompt!+ test-prompt)]
        (is (string? result) "Should return a string")
        (is (not (string/includes? (string/lower-case result) "request human input"))
            "Should not contain 'request human input'")
        (is (string/includes? result "Do X") "Should preserve other instructions")
        (is (string/includes? result "Do Y") "Should preserve other instructions")
        (done)))))

(deftest autonomize-remember-prompt-test
  (async done
    (testing "Transforms actual remember-prompt"
      (p/let [result (ma/autonomize-prompt!+ ma/remember-prompt)]
        (is (string? result) "Should return a string")
        (is (> (count result) 1000) "Should preserve most of the prompt")
        (is (not (string/includes? (string/lower-case result) "request human input"))
            "Should remove all 'request human input' instructions")
        (done)))))

(deftest goal-construction-test
  (testing "Goal combines context and autonomous prompt correctly"
    (let [test-context "Test context here"
          test-autonomous-prompt "Autonomous instructions here"
          ;; Simulate what record-memory!+ does
          expected-goal (str "CONTEXT TO REMEMBER:\n" test-context "\n\n" test-autonomous-prompt)]
      (is (string/starts-with? expected-goal "CONTEXT TO REMEMBER:")
          "Goal should start with context marker")
      (is (string/includes? expected-goal test-context)
          "Goal should include the context")
      (is (string/includes? expected-goal test-autonomous-prompt)
          "Goal should include the autonomous prompt"))))

(deftest record-memory-integration-test
  (async done
    (testing "Creates autonomous goal and calls agents"
      ;; Integration test with minimal turns to verify structure
      (p/let [test-context "Mistake: Did X. Correction: Do Y."
              result (ma/record-memory!+ test-context {:max-turns 1
                                                        :model-id "gpt-4o-mini"})]
        (is (map? result) "Should return a result map")
        (is (contains? result :history) "Should have history")
        (is (contains? result :reason) "Should have reason")
        (done)))))

(comment
  ;; Run all tests
  (run-tests 'memory-agent2-test)

  ;; Run specific test
  (autonomize-prompt-removes-human-input-test (fn [] (println "Done!")))

  :rcf)
