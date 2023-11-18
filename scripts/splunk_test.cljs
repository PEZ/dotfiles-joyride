(ns splunk-test
  (:require ["vscode" :as vscode]
            [joyride.core :as joyride]
            [promesa.core :as p]))


(-> (vscode/extensions.getExtension "splunk.splunk")
    ;; Force the Splunk extension to activate
    (.activate)
    ;; The promise will resolve with the extension's API as the result
    (p/then (fn [_api]
              (.appendLine (joyride/output-channel) "Splunk activated.")
                ;; In `splunk` the Splunk extension
                ;; is required, which will work fine
                ;; since now Splunk is active.
              (require '[splunk] :reload)))
    (p/catch (fn [error]
               (vscode/window.showErrorMessage (str "Requiring Splunk failed: " error)))))