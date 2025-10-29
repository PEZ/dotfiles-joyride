(ns monitor.ui
  "Pure UI rendering functions - transforms data to Hiccup"
  (:require [monitor.dispatch :as dispatch]))

(defn status-icon
  "Returns codicon class string for conversation `status`."
  [status]
  (case status
    :started "codicon-debug-pause"
    :working "codicon-loading codicon-modifier-spin"
    :cancel-requested "codicon-loading codicon-modifier-spin"
    :task-complete "codicon-check"
    :max-turns-reached "codicon-clock"
    :agent-finished "codicon-info"
    :done "codicon-pass"
    :error "codicon-error"
    :cancelled "codicon-debug-stop"
    "codicon-question"))

(defn status-color
  "Returns VS Code theme color for conversation `status`."
  [status]
  (case status
    :task-complete "var(--vscode-charts-green)"
    :max-turns-reached "var(--vscode-charts-yellow)"
    :agent-finished "var(--vscode-charts-blue)"
    :done "var(--vscode-charts-green)"
    :error "var(--vscode-charts-red)"
    :working "var(--vscode-charts-blue)"
    :cancel-requested "var(--vscode-charts-orange)"
    :cancelled "var(--vscode-charts-orange)"
    nil))

(defn format-time
  "Returns HH:MM formatted string from `js-date`, or '--:--' if `nil`."
  [time-string]
  (if time-string
    (let [js-date (js/Date. time-string)
          hours (.getHours js-date)
          minutes (.getMinutes js-date)
          pad (fn [n] (if (< n 10) (str "0" n) (str n)))]
      (str (pad hours) ":" (pad minutes)))
    "--:--"))

(defn truncate-text
  "Returns `text` truncated to `max-length` with ellipsis if needed,
   or `nil` if `text` is `nil`."
  [text max-length]
  (when text
    (if (> (count text) max-length)
      (str (subs text 0 max-length) "...")
      text)))

(defn render-results-section
  "Returns Hiccup div rendering `results` section with 'View Full' button,
   or `nil` if no results."
  [conv-id results icon-color]
  (when results
    (let [summary (truncate-text results 100)
          result-color (or icon-color "var(--vscode-charts-green)")]
      [:div.results-section {:style {:color result-color}}
       [:span.results-text
        "Results: " summary]
       [:button.btn-view-results {:on {:click [:show-results conv-id]}}
        "View"]])))
(defn render-conversation
  "Returns Hiccup HTML for single conversation entry from `conv` map."
  [conv]
  (let [{:keys [id goal status model-id
                caller title
                current-turn max-turns
                started-at error-message
                results total-tokens]} conv
        status-kw (keyword status)
        icon-class (status-icon status-kw)
        icon-color (status-color status-kw)
        time-str (format-time started-at)]
    [:div.conversation-card {:replicant/key id}
     [:div.conversation-header
      [:span.conversation-title
       [:i.status-icon {:class (str "codicon " icon-class)
                        :style (when icon-color {:color icon-color})}]
       [:span.title-text
        [:span.title-id (str "[" id "] ")]
        [:span.title-label title]]]
      [:div.conversation-actions
       (when (#{:working :started} status-kw)
         [:button.btn-cancel {:on {:click [:cancel-conversation id]}}
          [:i.codicon.codicon-debug-stop]])
       [:span.conversation-time time-str]
       (when (#{:task-complete :max-turns-reached :agent-finished :cancelled :error :done} status-kw)
         [:button.btn-delete {:on {:click [:delete-conversation id]}}
          [:i.codicon.codicon-close]])]]
     [:div.conversation-meta
      current-turn "/" max-turns " | "
      [:strong "Tks: "] total-tokens " | "
      model-id " | "
      (when caller
        [:span
         [:strong "Who: "] caller])]
     [:div.conversation-goal goal]
     (when error-message
       [:div.error-message
        "Error: " error-message])
     (render-results-section id results icon-color)]))

(defn render-monitor
  "Returns complete Hiccup HTML for monitor display from `conversations` vector."
  [conversations]
  (let [sorted-convs (reverse (sort-by :id conversations))]
    [:div
     [:div.monitor-header
      [:h2 "Dispatch Monitor"]
      [:button.btn-show-logs {:on {:click [:show-logs]}}
       "Show Logs"]]
     [:div.conversations-container
      (if (empty? sorted-convs)
        [:p.empty-message "No active conversations"]
        (into [:div] (map render-conversation sorted-convs)))]]))
