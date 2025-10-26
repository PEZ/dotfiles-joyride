(ns monitor.dispatch)

;; VS Code API handle
(def vscode (js/acquireVsCodeApi))

;; Message sending to host
(defn send-to-host! [message-type data]
  (.postMessage vscode
                (clj->js {:type (name message-type)
                          :data data
                          :timestamp (.now js/Date)})))

(defn dispatch! [_ [action & args]]
  (case action
    :cancel-conversation (send-to-host! :cancel-conversation {:id (first args)})
    :delete-conversation (send-to-host! :delete-conversation {:id (first args)})
    :show-results (send-to-host! :show-results {:id (first args)})
    :show-logs (send-to-host! :show-logs {})
    :log (send-to-host! :log args)
    (send-to-host! :warn args)))

(defn log! [& messages]
  (dispatch! nil (into [:log] messages)))

(defn warn! [& messages]
  (dispatch! nil (into [:warn] messages)))