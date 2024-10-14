(ns numbers
  (:require ["vscode" :as vscode]
            [joyride.core :as joyride]))

(def decoration-type (vscode.window.createTextEditorDecorationType
                      #js {:backgroundColor "rgba(255, 0, 0, 0.5)"
                           :isWholeLine true}))

(defonce !state (atom {:state/hightlight-timer nil
                       :state/disposables []}))

(defn find-numbers [document]
  (let [text (.getText document)
        regex #"\d{1,3}(?=(\d{3})*(?!\d))"]
    (loop [matches (keep (fn [match]
                           (when (= 3 (count (first match)))
                             (first match)))
                         (re-seq regex text))
           start-pos 0
           results []]
      (if (empty? matches)
        results
        (let [match (first matches)
              start (.indexOf text match start-pos)
              end (+ start (count match))
              range {:range (vscode.Range. (.positionAt document start)
                                           (.positionAt document end))
                     :text match}]
          (recur (rest matches) end (conj results range)))))))

(comment
  123456789
  12345
  123
  1234
  ; The 111 should match at line 44, character 3
  1111

  (->> (find-numbers (-> vscode/window .-activeTextEditor .-document))
       first
       :range
       .-start
       ((fn ([p] [(.-line p) (.-character p)]))))
  ;;=> [44 2]
  :rcf)

(defn find-numbers [document]
  (let [text (.getText document)
        regex #"\d{4,}"]
    (loop [matches (re-seq regex text)
           start-pos 0
           results []]
      (if (empty? matches)
        results
        (let [match (first matches)
              start (.indexOf text match start-pos)
              end (+ start (count match))
              groups (reverse (partition-all 3 (reverse match)))
              ranges (map (fn [group]
                            (let [group-start (+ start (- (count match) (count (apply str group))))
                                  group-end (+ group-start (count group))]
                              {:range (vscode.Range. (.positionAt document group-start)
                                                     (.positionAt document group-end))
                               :text (apply str group)}))
                          groups)]
          (recur (rest matches) end (concat results ranges)))))))

(defn clear-highlights! []
  (.setDecorations (-> vscode/window .-activeTextEditor) decoration-type #js []))

(defn highlight! []
  (clear-highlights!)
  (let [current-selection (-> vscode/window .-activeTextEditor .-selection)]
    (.setDecorations (-> vscode/window .-activeTextEditor) decoration-type #js [current-selection])))

(defn schedule-highlight! []
  (when-let [timeout (:state/hightlight-timer @!state)]
    (js/clearTimeout timeout))
  (swap! !state
         assoc
         :state/hightlight-timer
         (js/setTimeout
          (fn []
            (highlight!))
          16)))

(defn ^:export deactivate! []
  (doseq [disposable (:state/disposables @!state)]
    (.dispose disposable))
  (swap! !state dissoc :state/disposables)
  (when-let [timeout (:state/hightlight-timer @!state)]
    (js/clearTimeout timeout)
    (swap! !state dissoc :state/hightlight-timer))
  (clear-highlights!))

(defn ^:export activate! []
  (schedule-highlight!)
  (swap! !state update :state/disposables conj (vscode/workspace.onDidChangeTextDocument schedule-highlight!))
  (swap! !state update :state/disposables conj (vscode/window.onDidChangeActiveTextEditor schedule-highlight!)))

(when (= (joyride/invoked-script) joyride/*file*)
  (activate!))

(comment
  (deactivate!)
  (activate!)
  :rcf)
