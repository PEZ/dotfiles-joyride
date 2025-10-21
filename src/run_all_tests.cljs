;; AGENTS, please read this preamble before working with the namespace:
;; - Use interactive programming
;; - Work using TDD in the repl
#_(do (require 'run-all-tests :reload) (run-all-tests/run!+))
;; - Always prefer your structural editing tools

(ns run-all-tests
  (:require [clojure.string :as string]
            [cljs.test]
            [promesa.core :as p]
            ["vscode" :as vscode]))

(defn- uri->ns-symbol [uri]
  (-> uri
      (vscode/workspace.asRelativePath)
      (string/split "/")
      (->> (drop 1)
           (string/join "."))
      (string/replace "_" "-")
      (string/replace #"\.clj[cs]$" "")
      symbol))

(defn- glob->ns-symbols [glob]
  (p/let [uris (vscode/workspace.findFiles glob)]
    (map uri->ns-symbol uris)))

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
  (p/let [test-nss (glob->ns-symbols "src/test/**/*_test.cljs")]
    (doseq [test-ns test-nss]
      (require test-ns :reload))
    (apply cljs.test/run-tests test-nss)))

(comment
  (run!+)
  :rcf)
