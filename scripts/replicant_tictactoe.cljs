(ns replicant-tictactoe
  (:require [flare-lab]
            [joyride.core :as joyride]
            :reload-all))

(when (= (joyride/invoked-script) joyride/*file*) ; Auto-run when script is invoked
  #_(prompt-sync/main)
  (flare-lab/replicant-ttt))