;; AGENTS, please read this preamble before working with the namespace:
;; - Use interactive programming
;; - Work using TDD in the repl
#_(do (require 'run-all-tests :reload-all) (run-all-tests/run!+))
;; - Always prefer your structural editing tools

(ns test.lm-dispatch.monitor-test
  (:require
   [lm-dispatch.monitor :as monitor]
   [cljs.test :refer [deftest is testing]]))

(deftest status-icons
  (testing "Status icon returns correct codicon classes"
    (is (= "codicon-debug-pause" (monitor/status-icon :started)))
    (is (= "codicon-loading codicon-modifier-spin" (monitor/status-icon :working)))
    (is (= "codicon-check" (monitor/status-icon :task-complete)))
    (is (= "codicon-clock" (monitor/status-icon :max-turns-reached)))
    (is (= "codicon-info" (monitor/status-icon :agent-finished)))
    (is (= "codicon-pass" (monitor/status-icon :done)))
    (is (= "codicon-error" (monitor/status-icon :error)))
    (is (= "codicon-debug-stop" (monitor/status-icon :cancelled)))
    (is (= "codicon-question" (monitor/status-icon :unknown-status)))))

(deftest time-formatting
  (testing "Format time as HH:MM"
    (let [date (js/Date. 2025 9 15 14 5)]  ; Oct 15, 2025, 14:05
      (is (= "14:05" (monitor/format-time date))))

    (let [date (js/Date. 2025 9 15 9 7)]   ; Oct 15, 2025, 09:07
      (is (= "09:07" (monitor/format-time date)))))

  (testing "Handles nil date"
    (is (= "--:--" (monitor/format-time nil)))))

(comment
  ;; Run tests
  (cljs.test/run-tests 'lm-dispatch.monitor-test)
  :rcf)
