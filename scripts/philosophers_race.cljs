(ns philosophers-race
  (:require [promesa.core :as p]
            [joyride.core :as joyride]))

(def philosophers
  [{:name "Socrates" :color "\u001b[31m" :speed 80}   ; Red
   {:name "Plato" :color "\u001b[32m" :speed 95}      ; Green
   {:name "Aristotle" :color "\u001b[33m" :speed 110} ; Yellow
   {:name "Descartes" :color "\u001b[34m" :speed 75}  ; Blue
   {:name "Kant" :color "\u001b[35m" :speed 100}      ; Magenta
   {:name "Nietzsche" :color "\u001b[36m" :speed 90}  ; Cyan
   {:name "Wittgenstein" :color "\u001b[37m" :speed 85}]) ; White

(def reset-color "\u001b[0m")

(defn pad-right [s len]
  (let [padding (- len (count s))]
    (if (pos? padding)
      (str s (apply str (repeat padding " ")))
      s)))

(defn update-philosopher-bar [philosopher progress]
  (let [percent (int (* (/ progress 100) 100))
        filled (int (/ percent 5))
        empty (- 20 filled)
        bar (str (apply str (repeat filled "█"))
                 (apply str (repeat empty "░")))]
    (str (:color philosopher)
         (pad-right (:name philosopher) 15)
         " [" bar "] " percent "%"
         reset-color)))

(defn animate-philosophers []
  (letfn [(step [progress-map]
            (if (every? #(>= % 100) (vals progress-map))
              (p/let [_ (println "\n\n🎉 All philosophers finished thinking!")]
                (p/resolved :done))
              (p/let [;; Update each philosopher's progress
                      new-progress (into {}
                                         (map (fn [[idx prog]]
                                                (let [speed (get-in philosophers [idx :speed])
                                                      ;; Lower speed number = faster (more increment per step)
                                                      increment (/ 100.0 speed)]
                                                  [idx (min 100 (+ prog increment))]))
                                              progress-map))
                      ;; Restore cursor to saved position, then move up and redraw all bars
                      _ (print "\u001b[u")  ; Restore cursor to saved position
                      _ (print (str "\u001b[7A"  ; Move up 7 lines
                                    "\u001b[1000D")) ; Move to start
                      _ (doseq [idx (range 7)]
                          (println (update-philosopher-bar
                                    (philosophers idx)
                                    (new-progress idx))))
                      _ (print "\u001b[s")  ; Save cursor position after printing
                      _ (p/delay 100)]
                (step new-progress))))]
    (p/let [_ (println "\n🧠 Philosophers are thinking...\n")
            ;; Print initial empty bars
            _ (doseq [p philosophers]
                (println (update-philosopher-bar p 0)))
            _ (print "\u001b[s")  ; Save cursor position after initial bars
            ;; Start animation
            initial-progress (into {} (map vector (range 7) (repeat 0)))]
      (step initial-progress))))

(when (= (joyride/invoked-script) joyride/*file*)
  (animate-philosophers)
  )
