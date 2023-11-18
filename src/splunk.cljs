(ns splunk
  (:require ["vscode" :as vscode]
            ["ext://splunk.splunk" :as splunk-api]
            [joyride.core :as joyride]
            [promesa.core :as p]))

(.appendLine (joyride/output-channel) "splunk api required?")
(.appendLine (joyride/output-channel) (js-keys splunk-api))
(comment
  splunk-api
  :rcf)

