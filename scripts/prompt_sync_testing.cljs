(ns prompt-sync-testing
  (:require
   [joyride.core :as joyride]
   [prompt-sync] :reload))

(when (= (joyride/invoked-script) joyride/*file*) ; Auto-run when script is invoked
  #_(prompt-sync/main)
  (prompt-sync/main-test))