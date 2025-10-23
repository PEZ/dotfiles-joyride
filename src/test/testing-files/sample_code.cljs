;; Sample ClojureScript file for testing
;; This file is used by instructions_util_test.cljs
;; DO NOT MODIFY - tests depend on specific line numbers and content

(ns test-sample.sample-code
  (:require
   ["vscode" :as vscode]  ; Line 7 - contains "vscode"
   [promesa.core :as p]   ; Line 8
   [clojure.string :as string]))  ; Line 9 - contains "vscode" in require block (lines 7-9)

;; Lines 7-9 contain the require block with "vscode" on line 7
;; This is used by the enrich-editor-context-full-test

(defn sample-function
  "A simple sample function for testing."
  [x y]
  (+ x y))

(defn another-function
  "Another function to make this file more realistic."
  [data]
  (when data
    (string/upper-case data)))
