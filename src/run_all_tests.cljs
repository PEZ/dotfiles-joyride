;; AGENTS, please read this preamble before working with the namespace:
;; - Use interactive programming
;; - Work using TDD in the repl
#_(do (require 'run-all-tests :reload-all-all) (run-all-tests/run!+))
;; - Always prefer your structural editing tools
(ns run-all-tests
  (:require [joyride.core :as joyride]
            [promesa.core :as p]
            [cljs.test]
            ["vscode" :as vscode]))

(defn run!+
  "Find, load, and run all test files matching *_test.cljs pattern.

  Returns a promise that resolves when all tests are complete.

  Process:
  1. Finds all test files in src/test/**/*_test.cljs
  2. Loads each test file (reports errors for files that fail to load)
  3. Runs all loaded tests using cljs.test/run-all-tests

  Example:
    (run!+)"
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

          _ (when (seq failed)
              (println "\n⚠️  Failed to load" (count failed) "test file(s):")
              (doseq [{:keys [path error]} failed]
                (println "  -" path ":" error)))

          _ (println "\n✅ Loaded" (count successful) "test file(s)")
          _ (println "\n🏃 Running all tests...\n")

          _ (cljs.test/run-all-tests #"test\..*")]

    (println "\n✨ Test run complete!")))

(comment
  ;; Run all tests
  (run!+)

  ;; Test file finding
  (p/let [test-uris (vscode/workspace.findFiles "src/test/**/*_test.cljs")]
    (mapv #(.-fsPath %) test-uris))

  :rcf)
