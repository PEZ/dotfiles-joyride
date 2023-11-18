(ns calva-api
  (:require ["vscode" :as vscode]
            ["ext://betterthantomorrow.calva$v0" :as calva]
            [joyride.core :as joyride]
            [promesa.core :as p]
            [editor-utils]))

(def oc (joyride.core/output-channel))

#_(p/let [splunk-ext (vscode/extensions.getExtension "splunk.splunk")]
  (def splunk-ext splunk-ext)
  (.activate splunk-ext))

(require '["ext://splunk.splunk" :as splunk-api])

(require '["vscode" :as vscode]
         '["ext://betterthantomorrow.calva$v0" :as calva]
         '[joyride.core :as joyride]
         '[promesa.core :as p])
(require '[clojure.string :as string])

(comment
  (def some-code "(string/replace \"\\\"foo\\\"\" #\"\\\"\" \"'\" )")
  (p/let [current-form (second (calva/ranges.currentForm))
          result (calva/evaluateCode "clj" some-code)]
    (println result)))

(defn evaluate-in-session+ [session-key code]
  (p/let [result (calva/repl.evaluateCode
                  session-key
                  code
                  #js {:stdout #(.append oc %)
                       :stderr #(.append oc (str "Error: " %))})]
    (.-result result)))

(defn clj-evaluate+ [code]
  (evaluate-in-session+ "clj" code))

(defn cljs-evaluate+ [code]
  (evaluate-in-session+ "cljs" code))

(defn evaluate+
  "Evaluates `code` in whatever the current session is."
  [code]
  (evaluate-in-session+ (calva/repl.currentSessionKey) code))

(defn evaluate-selection+ []
  (p/let [code (editor-utils/current-selection-text)
          result (.-result (evaluate+ code))]
    result))

;; Utils for REPL-ing Joyride code, when connected to a project REPL.

(defn joyride-eval-current-form+ []
  (vscode/commands.executeCommand "joyride.runCode" (second (calva/ranges.currentForm))))

(defn joyride-eval-top-level-form+ []
  (vscode/commands.executeCommand "joyride.runCode" (second (calva/ranges.currentTopLevelForm))))

;; Bind to some nice keyboard shortcuts, e.g. like so:
;;  {
;;      "key": "cmd+ctrl+enter",
;;      "command": "joyride.runCode",
;;      "args": "(calva-api/joyride-eval-current-form)",
;;  },
;;  {
;;      "key": "cmd+alt+enter",
;;      "command": "joyride.runCode",
;;      "args": "(calva-api/joyride-eval-top-level-form)",
;;  },

;; Convenience function making it easier to restart clojure-lsp

(defn restart-clojure-lsp []
  (p/do (vscode/commands.executeCommand "calva.clojureLsp.stop")
        (vscode/commands.executeCommand "calva.clojureLsp.start")))

(defn ignore-current-form []
  (p/let [[range text] (calva/ranges.currentEnclosingForm)]
    (calva/editor.replace vscode/window.activeTextEditor range (str "#_" text))))

(comment
  (calva/repl.nReplConnect "localhost", 58486, calva/repl.ConnectSequenceGeneric)

  (vscode/commands.executeCommand "calva.connectNonProjectREPL"))