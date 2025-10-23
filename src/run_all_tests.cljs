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
  Returns a promise that resolves when all tests are complete."
  []
  (p/let [test-nss (glob->ns-symbols "src/test/**/*_test.cljs")]
    (let [loaded (reduce (fn [acc test-ns]
                           (try
                             (require test-ns :reload)
                             (conj acc test-ns)
                             (catch js/Error e
                               (println (str "❌ Failed to load test namespace: " test-ns ": "
                                             (.-message e)))
                               acc)))
                         []
                         test-nss)]
      (apply cljs.test/run-tests loaded))))

(comment
  (run!+)
  :rcf)
