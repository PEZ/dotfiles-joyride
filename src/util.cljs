(ns util)

#_{:clj-kondo/ignore [:redefined-var]}
(defmacro time
  "Evaluates expr and prints the time it took.  Returns the value of
 expr."
  [expr]
  `(let [start# (js/performance.now)
         ret# ~expr]
     (prn (str "Elapsed time: " (- (js/performance.now) start#) " msecs"))
     ret#))