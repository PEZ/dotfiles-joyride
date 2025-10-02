(ns timezones
  (:require ["vscode" :as vscode]
            [promesa.core :as p]
            [joyride.core :as joyride]
            [clojure.string :as str]))

(def timezones
  "Timezone configuration with domain-namespaced keywords"
  [{:timezone/label "UTC" :timezone/id "UTC"}
   {:timezone/label "New York (ET)" :timezone/id "America/New_York"}
   {:timezone/label "Los Angeles (PT)" :timezone/id "America/Los_Angeles"}
   {:timezone/label "London (GMT)" :timezone/id "Europe/London"}
   {:timezone/label "Berlin (CET)" :timezone/id "Europe/Berlin"}
   {:timezone/label "Tokyo (JST)" :timezone/id "Asia/Tokyo"}
   {:timezone/label "Sydney (AEST)" :timezone/id "Australia/Sydney"}
   {:timezone/label "São Paulo (BRT)" :timezone/id "America/Sao_Paulo"}])

(defn format-current-time-for-input
  "Format current time as YYYY-MM-DD HH:MM for easy editing"
  []
  (let [now (js/Date.)
        year (.getFullYear now)
        month (inc (.getMonth now))  ; getMonth is 0-based
        day (.getDate now)
        hours (.getHours now)
        minutes (.getMinutes now)]
    (str year "-"
         (when (< month 10) "0") month "-"
         (when (< day 10) "0") day " "
         (when (< hours 10) "0") hours ":"
         (when (< minutes 10) "0") minutes)))

(defn parse-input
  "Parse user input string to Date object, defaulting to current time"
  [input]
  (if (or (nil? input) (= input ""))
    (js/Date.)  ; current time
    (let [parsed (js/Date. input)]
      (if (js/isNaN (.getTime parsed))
        nil  ; invalid date
        parsed))))

(defn format-for-timezone
  "Format a Date object for a specific timezone using Intl.DateTimeFormat"
  [date tz]
  (.toLocaleString date "en-US"
    #js {:timeZone tz
         :year "numeric"
         :month "short"
         :day "numeric"
         :hour "numeric"
         :minute "2-digit"
         :timeZoneName "short"}))

(defn create-timezone-items
  "Create QuickPick items from timezone configs and formatted times"
  [date timezone-configs]
  (map (fn [{:timezone/keys [label id]}]
         (let [formatted-time (format-for-timezone date id)]
           #js {"label" (str label ": " formatted-time)
                "detail" id
                "timezone-data" #js {"timezone-id" id "formatted-time" formatted-time}}))
       timezone-configs))

(defn create-markdown-list
  "Generate markdown list from timezone items for copy-all functionality"
  [timezone-items]
  (->> timezone-items
       (map #(str "- " (.-label %)))
       (str/join "\n")))

(defn show-timezone-converter!
  "Main function: Show timezone converter with user input and QuickPick results"
  []
  (p/let [input (vscode/window.showInputBox
                  #js {"prompt" "Edit date and time, or press Enter to use current time"
                       "value" (format-current-time-for-input)
                       "placeHolder" "YYYY-MM-DD HH:MM format"})
          parsed-date (when input (parse-input input))]
    (if parsed-date
      (let [timezone-items (create-timezone-items parsed-date timezones)
            qp (vscode/window.createQuickPick)]

        ;; Configure the QuickPick
        (set! (.-title qp) "Timezone Conversion Results")
        (set! (.-placeholder qp) "Select a timezone to copy to clipboard, or use the button to copy all")
        (set! (.-items qp) (clj->js timezone-items))
        (set! (.-buttons qp) #js [#js {"iconPath" #js {"id" "copy"} "tooltip" "Copy all timezones to clipboard"}])

        ;; Handle item selection (copy individual timezone)
        (.onDidAccept qp
          (fn []
            (when-let [selected (first (.-selectedItems qp))]
              (let [timezone-data (.-timezone-data selected)
                    formatted-time (.-formatted-time timezone-data)]
                (p/let [_ (vscode/env.clipboard.writeText formatted-time)]
                  (vscode/window.showInformationMessage
                    (str "Copied to clipboard: " formatted-time))))
              (.hide qp))))

        ;; Handle "Copy All" button
        (.onDidTriggerButton qp
          (fn [_button]
            (let [markdown-list (create-markdown-list timezone-items)]
              (p/let [_ (vscode/env.clipboard.writeText markdown-list)]
                (vscode/window.showInformationMessage
                  (str "Copied all " (count timezone-items) " timezones to clipboard")))
              (.hide qp))))

        ;; Handle hide/escape
        (.onDidHide qp (fn [] (.dispose qp)))

        ;; Show the picker
        (.show qp))

      ;; Handle invalid date input
      (vscode/window.showErrorMessage "Invalid date format. Please try again."))))

;; Main script execution guard - only run when invoked as script
(when (= (joyride/invoked-script) joyride/*file*)
  (show-timezone-converter!))

(comment
  ;; REPL testing and exploration

  ;; Test current time formatting
  (format-current-time-for-input)
  ;=> "2025-08-26 22:54"

  ;; Test date parsing with various inputs
  (parse-input "2025-08-26 15:30")
  (parse-input "Aug 26, 2025 3:30 PM")
  (parse-input "")  ; should default to now
  (parse-input "invalid")  ; should return nil

  ;; Test timezone conversion
  (def test-date (js/Date. "2025-08-26 15:30"))
  (format-for-timezone test-date "America/New_York")
  ;=> "Aug 26, 2025, 9:30 AM EDT"

  ;; Test with all configured timezones
  (map #(assoc % :timezone/formatted-time
               (format-for-timezone test-date (:timezone/id %)))
       timezones)

  ;; Test QuickPick item creation
  (def items (create-timezone-items test-date timezones))
  (map #(.-label %) items)

  ;; Test markdown generation
  (create-markdown-list items)

  ;; Test the complete flow (requires user interaction)
  (show-timezone-converter!)

  :rcf)