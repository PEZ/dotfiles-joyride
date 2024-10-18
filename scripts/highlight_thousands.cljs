;; Joyride thousands highlighter
;; The end goal here is to help humans read long numbers by highlighting groups of thousands
;; First we need to find the groups of thousands. We only want to highlight _odd_ groups
;; of thousands, starting with the least significant group to the most significant group.
;; We also consider the most significant group of thousands when it is not three digits long.

; Here is some test data, line numbers are on the left
; The xxx are the groups of thousands we want to highlight on the numbers on the line above them.
;08  1111111222111333444 :foo 555666 :bar 123 :baz 1234
;09  x   xxx   xxx   xxx         xxx                xxx
;10 222222 33333 --- 4444444
;11    xxx   xxx     x   xxx

(ns highlight-thousands
  (:require ["vscode" :as vscode]
            [joyride.core :as joyride]))

(def ^:private decoration-type #js {:fontWeight "600"
                                    ;; You can go quite crazy
                                    ;:fontStyle "bold; text-shadow: 0 0 5px #fff, 0 0 10px #fff, 0 0 20px #fff, 0 0 40px #0ff, 0 0 80px #0ff, 0 0 90px #0ff, 0 0 100px #0ff, 0 0 150px #0ff;"
                                    })

(defonce ^:private !state (atom {:state/hightlight-timer nil
                                 :state/decoration-type nil
                                 :state/disposables []}))

(defn- long-number-ranges
  "Finds the long numbers (more than 3 digits) returns their ranges as tuples."
  [text]
  (when-let [matches (re-seq #"\d{4,}" text)]
    (-> (reduce (fn [{:keys [p] :as acc} match]
                  (let [match-start (.indexOf text match p)
                        match-end (+ match-start (count match))]
                    (-> acc
                        (update :ranges conj [match-start match-end])
                        (assoc :p match-end))))
                {:ranges [] :p 0}
                matches)
        :ranges)))

(comment
  (long-number-ranges (-> vscode/window .-activeTextEditor .-document .getText))
  :rcf)

(defn- group-thousands
  "Takes a range and returns the thousands for odd-numbered groups
   right to left (from the least significant to most significant).
   Also considering when the most significant group is not three digits long."
  [[start end]]
  (map (fn [i]
         [(max start (- i 3)) i])
       (range end start -6)))

12345678901234567890122
'XX4567890
(comment
  (group-thousands [0 23])
  #_([20 23] [14 17] [8 11] [2 5])
  (group-thousands [3 10])
  #_([7 10] [3 4])
  :rcf)

(defn- text->thousands-groups [text]
  (->> text
       long-number-ranges
       (mapcat group-thousands)))

(comment
  (text->thousands-groups (vscode/window.activeTextEditor.document.getText))
  #_([574 577] [568 571] [562 565] [558 559] [586 589] [605 608] [672 675] [678 681] [690 693]
     [686 687] [2347 2350] [2341 2344] [2358 2361] [2354 2355])
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
  #_([8 21] [8 15] [8 9] [8 5] [8 33] [8 52] [10 7] [10 13] [10 25] [10 21] [53 7] [53 1]
     [54 7] [54 3] [69 110] [69 115] [69 122] [69 127] [69 134] [69 139] [69 146] [69 151])
  :rcf)

(defn- clear-highlights! []
  (when-let [editor (-> vscode/window .-activeTextEditor)]
    (.setDecorations editor (:state/decoration-type @!state) #js [])))

(defn- highlight! []
  (clear-highlights!)
  (when-let [editor (-> vscode/window .-activeTextEditor)]
    (let [ranges (document->thousands-ranges (.-document editor))]
      (.setDecorations editor (:state/decoration-type @!state) (into-array ranges)))))

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
    (swap! !state dissoc :state/hightlight-timer)
    (.appendLine (joyride/output-channel) "Highlighting thousands deactivated!")))

(defn ^:export activate! []
  (deactivate!)
  (let [decoration-type-disposable (vscode/window.createTextEditorDecorationType decoration-type)]
    (swap! !state assoc :state/decoration-type decoration-type-disposable)
    (swap! !state update :state/disposables conj decoration-type-disposable))
  (swap! !state update :state/disposables conj (vscode/workspace.onDidChangeTextDocument schedule-highlight!))
  (swap! !state update :state/disposables conj (vscode/window.onDidChangeActiveTextEditor schedule-highlight!))
  (schedule-highlight!)
  (.appendLine (joyride/output-channel) "Highlighting thousands activated!")
  #js {:dispose deactivate!})

(when (= (joyride/invoked-script) joyride/*file*)
  (activate!))

(comment
  (deactivate!)
  (activate!)
  :rcf)
