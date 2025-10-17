(ns agent-monitor-reveal
  (:require [ai-workflow.dispatch-monitor :as dispatch-monitor]
            [joyride.core :as joyride]
            :reload-all))

(when (= (joyride/invoked-script) joyride/*file*)
  (dispatch-monitor/reveal-dispatch-monitor!+))