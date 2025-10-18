; AGENTS, please:
; - remember interactive programming
; - consider TDD in the repl
; - prefer your structural editing tools
(ns run-all-tests
  (:require [joyride.core :as joyride]
            [promesa.core :as p]
            [cljs.test]
            ["vscode" :as vscode]))

; Can we use vscode/workspace.fs to glob tests files? Then load them, then use cljs.test/run- ... whatever runs all loaded teests.

(comment
   ; Returns a promise
  (joyride/load-file "src/test/lm_dispatch/state_test.cljs")
  )

(defn run-all-tests!+
  "Find, load, and run all test files matching *_test.cljs pattern.

  Returns a promise that resolves when all tests are complete.

  Process:
  1. Finds all test files in src/test/**/*_test.cljs
  2. Loads each test file (reports errors for files that fail to load)
  3. Runs all loaded tests using cljs.test/run-all-tests

  Example:
    (run-all-tests!+)"
  []
  (p/let [;; Find all test files
          test-uris (vscode/workspace.findFiles "src/test/**/*_test.cljs")
          test-paths (mapv #(.-fsPath %) test-uris)

          _ (println "\n🧪 Found" (count test-paths) "test files")

          ;; Load each test file, capturing errors
          load-results (p/all
                        (for [path test-paths]
                          (p/catch
                           (p/let [_ (joyride/load-file path)]
                             {:success true :path path})
                           (fn [error]
                             {:success false
                              :path path
                              :error (.-message error)}))))

          successful (filter :success load-results)
          failed (remove :success load-results)

          ;; Report any load failures
          _ (when (seq failed)
              (println "\n⚠️  Failed to load" (count failed) "test file(s):")
              (doseq [{:keys [path error]} failed]
                (println "  -" path ":" error)))

          _ (println "\n✅ Loaded" (count successful) "test file(s)")
          _ (println "\n🏃 Running all tests...\n")

          ;; Run all tests matching test.* namespace pattern
          _ (cljs.test/run-all-tests #"test\..*")]

    (println "\n✨ Test run complete!")))

(comment
  ;; Run all tests
  (run-all-tests!+)

  ;; Test file finding
  (p/let [test-uris (vscode/workspace.findFiles "src/test/**/*_test.cljs")]
    (mapv #(.-fsPath %) test-uris))

  :rcf)
