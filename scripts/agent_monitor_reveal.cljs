(ns agent-monitor-reveal
  (:require [ai-workflow.agent-monitor :as agent-monitor]
            [joyride.core :as joyride]
            :reload-all))

(when (= (joyride/invoked-script) joyride/*file*)
  (agent-monitor/reveal-agent-monitor!+))