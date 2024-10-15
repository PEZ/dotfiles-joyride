;; Joyride thousands highlighter
;; The end goal here is to help humans read long numbers by highlighting groups of thousands
;; First we need to find the groups of thousands. We only want to highlight _odd_ groups
;; of thousands, starting with the most significant group to the least significant group.
;; The xxx below are the groups of thousands we want to highlight on the numbers on the line above them.

;; Example data
;; These are column numbers, we use letters to not match them with our find-numbers function
;bcdefghijabcdefghijabcdefghijabcdefghijabcdefghijabcdefghijabcdefghijabcdefghijabc
; This is some test data, line numbers are on the left
;10  1111111222111333444 :foo 555666 :bar 123 :baz 1234
;11   xxx   xxx   xxx         xxx                   xxx
;12 222222 33333 --- 4444444
;13 xxx      xxx      xxx

(ns highlight-thousands
  (:require ["vscode" :as vscode]
            [joyride.core :as joyride]))

(def ^:private decoration-type #js {:fontWeight "600"})

(defonce ^:private !state (atom {:state/hightlight-timer nil
                                 :state/decoration-type nil
                                 :state/disposables []}))

(defn- long-number-ranges
  "Finds the long numbers (more than 3 digits) returns their ranges as tuples."
  [text]
  (when-let [matches (re-seq #"\d{4,}" text)]
    (-> (reduce (fn [{:keys [p] :as acc} match]
                  (let [match-start (.indexOf text match p)
                        match-end (+ match-start (count match))
                        range [match-start match-end]]
                    (-> acc
                        (update :ranges conj range)
                        (assoc :p match-end))))
                {:ranges [] :p 0}
                matches)
        :ranges)))

(comment
  (long-number-ranges (-> vscode/window .-activeTextEditor .-document .getText))
  ;;=> [[664 683] [689 695] [710 714] [775 781] [782 787] [792 799]]
  :rcf)

(defn- group-thousands
  "Takes a range and returns the thousands for odd-numbered groups from the most significant to least significant."
  [[start end]]
  (let [length (- end start)]
    (map (fn [i]
           [i (+ i 3)])
         (range (+ start (mod length 3)) end 6))))

(comment
  (group-thousands [0 10])
  ;;=> ([1 4] [7 10])
  (group-thousands [1 8])
  ;;=> ([2 5])
  :rcf)

(defn- text->thousands-groups [text]
  (->> text
       long-number-ranges
       (mapcat group-thousands)))

(comment
  (text->thousands-groups (vscode/window.activeTextEditor.document.getText))
  ;;=> ([665 668] [671 674] [677 680] [689 692] [711 714] [775 778] [784 787] [793 796])
  :rcf)

(defn- document->thousands-ranges [document]
  (->> (.getText document)
       text->thousands-groups
       (map (fn [[start end]]
              (vscode/Range. (.positionAt document start)
                             (.positionAt document end))))))

(comment
  (->> (document->thousands-ranges (-> vscode/window.activeTextEditor.document))
       (map #(.-start %))
       (map (fn ([p] [(.-line p) (.-character p)]))))
  #_([10 6] [10 12] [10 18] [10 30] [10 52] [12 4] [12 13] [12 22])
  :rcf)

(defn- clear-highlights! []
  (.setDecorations (-> vscode/window .-activeTextEditor) (:state/decoration-type @!state) #js []))

(defn- highlight! []
  (clear-highlights!)
  (let [editor (-> vscode/window .-activeTextEditor)
        ranges (document->thousands-ranges (.-document editor))]
    (.setDecorations editor (:state/decoration-type @!state) (into-array ranges))))

(defn- schedule-highlight! []
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
  (swap! !state dissoc :state/decoration-type)
  (when-let [timeout (:state/hightlight-timer @!state)]
    (js/clearTimeout timeout)
    (swap! !state dissoc :state/hightlight-timer)))

(defn ^:export activate! []
  (schedule-highlight!)
  (let [decoration-type-disposable (vscode/window.createTextEditorDecorationType decoration-type)]
    (swap! !state assoc :state/decoration-type decoration-type-disposable)
    (swap! !state update :state/disposables conj decoration-type-disposable))
  (swap! !state update :state/disposables conj (vscode/workspace.onDidChangeTextDocument schedule-highlight!))
  (swap! !state update :state/disposables conj (vscode/window.onDidChangeActiveTextEditor schedule-highlight!)))

(when (= (joyride/invoked-script) joyride/*file*)
  (activate!))

(comment
  (deactivate!)
  (activate!)
  :rcf)
