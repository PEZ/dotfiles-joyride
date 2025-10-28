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
      [:div {:style {:color result-color
                     :font-size "0.85em"
                     :margin-top "4px"
                     :display :flex
                     :justify-content :space-between
                     :align-items :center
                     :gap "8px"}}
       [:span {:style {:flex "1" :min-width "0"}}
        "Results: " summary]
       [:button {:on {:click [:show-results conv-id]}
                 :style {:padding "2px 6px"
                         :background "var(--vscode-button-background)"
                         :color "var(--vscode-button-foreground)"
                         :border :none
                         :border-radius "2px"
                         :cursor :pointer
                         :font-size "0.8em"
                         :flex-shrink "0"}}
        "View Full"]])))

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
    [:div {:style {:border "1px solid var(--vscode-panel-border)"
                   :padding "8px"
                   :margin "4px 0"
                   :border-radius "4px"}
           :replicant/key id}
     [:div {:style {:display :flex
                    :justify-content :space-between
                    :align-items :center
                    :margin-bottom "4px"}}
      [:span {:style {:font-weight :bold
                      :display :flex
                      :align-items :center
                      :gap "4px"
                      :flex "1"
                      :min-width "0"
                      :overflow :hidden
                      :text-overflow :ellipsis
                      :white-space :nowrap}}
       [:i {:class (str "codicon " icon-class)
            :style (merge {:padding-top "2px"
                           :flex-shrink "0"}
                          (when icon-color
                            {:color icon-color}))}]
       [:span {:style {:overflow :hidden
                       :text-overflow :ellipsis}}
        (str "[" id "] ")
        [:span {:style {:color "var(--vscode-charts-foreground)"}}
         title]]]
      [:div {:style {:display :flex
                     :align-items :center
                     :gap "4px"}}
       (when (#{:working :started} status-kw)
         [:button {:on {:click [:cancel-conversation id]}
                   :style {:padding "2px 4px"
                           :background "transparent"
                           :border "1px solid var(--vscode-button-border)"
                           :border-radius "2px"
                           :cursor :pointer
                           :flex-shrink "0"}}
          [:i {:class "codicon codicon-debug-stop"
               :style {:color "var(--vscode-errorForeground)"
                       :font-size "14px"}}]])
       [:span {:style {:font-size "0.9em" :opacity "0.7" :flex-shrink "0"}}
        time-str]
       (when (#{:task-complete :max-turns-reached :agent-finished :cancelled :error :done} status-kw)
         [:button {:on {:click [:delete-conversation id]}
                   :style {:padding "2px 4px"
                           :background "transparent"
                           :border "none"
                           :cursor :pointer
                           :opacity "0.6"
                           :flex-shrink "0"}}
          [:i {:class "codicon codicon-close"
               :style {:color "var(--vscode-foreground)"
                       :font-size "12px"}}]])]]
     [:div {:style {:font-size "0.9em" :margin-bottom "4px"}}
      current-turn "/" max-turns " | "
      [:strong "Tks: "] total-tokens " | "
      model-id " | "
      (when caller
        [:span
         [:strong "Who: "] caller])]
     [:div {:style {:max-height "120px"
                    :overflow-y :auto
                    :font-size "0.9em"
                    :font-family "var(--vscode-editor-font-family)"
                    :white-space :pre-wrap
                    :padding "4px"
                    :background "var(--vscode-editor-background)"
                    :border-radius "2px"}}
      goal]
     (when error-message
       [:div {:style {:color "var(--vscode-errorForeground)"
                      :font-size "0.85em"
                      :margin-top "4px"}}
        "Error: " error-message])
     (render-results-section id results icon-color)]))

(defn render-monitor
  "Returns complete Hiccup HTML for monitor display from `conversations` vector."
  [conversations]
  (let [sorted-convs (reverse (sort-by :id conversations))]
    [:div
     [:div {:style {:display :flex
                    :justify-content :space-between
                    :align-items :center
                    :margin-bottom "10px"
                    :padding "0 8px"}}
      [:h2 {:style {:margin "0"}} "Dispatch Monitor"]
      [:button {:on {:click [:show-logs]}
                :style {:padding "4px 8px"
                        :background "var(--vscode-button-background)"
                        :color "var(--vscode-button-foreground)"
                        :border :none
                        :border-radius "2px"
                        :cursor :pointer}}
       "Show Logs"]]
     [:div {:style {:margin-top "10px"
                    :padding "0 8px"}}
      (if (empty? sorted-convs)
        [:p {:style {:font-style :italic :opacity "0.7"}}
         "No active conversations"]
        (into [:div] (map render-conversation sorted-convs)))]]))
