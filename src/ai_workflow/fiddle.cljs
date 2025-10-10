(ns ai-workflow.fiddle
  (:require ["vscode" :as vscode]
            [promesa.core :as p]))

(def tool-id "joyride_hello_tool")

(defonce ^:private *tool (atom nil))

(defn register! []
  (when-let [d @*tool] (.dispose d))
  (reset! *tool
          (vscode/lm.registerTool
           tool-id
           #js {:describe
                (fn []
                  #js {:name tool-id
                       :description "Just a test really"
                       :inputSchema #js {:type "object"
                                         :required #js ["greetee"]
                                         :properties #js {:greetee #js {:type "string"
                                                                        :description "Who to say hello to"}
                                                          :mode #js {:type "string"
                                                                     :enum #js ["cheerful" "strict" "contemplating"]}}}})
                :invoke
                (fn [params token]
                  (try
                    (let [greetee (or (aget params "greetee") "")
                          mode    (or (aget params "mode") "default")]
                      (-> (js/Promise.resolve
                           #js {:status "ok"
                                :summary (str "Hello, " greetee " (" mode ")!")})
                          (.catch (fn [e]
                                    (js/console.error "Invoke error" e)
                                    #js {:status "error" :summary (str e)}))))
                    (catch :default e
                      (js/Promise.resolve
                       #js {:status "error" :summary (str e)}))))})))

(defn unregister! []
  (when-let [d @*tool] (.dispose d) (reset! *tool nil)))

(comment
  (register!)
  (-> (vscode/lm.invokeTool tool-id #js {:greetee "Mr Clojurian" :mode "cheerful"})
      (.then prn)
      (.catch prn))

  ;; Inspect available tools (should include ours)
  (doseq [t (.-tools vscode/lm)]
    (def t t)
    (println "Tool:" (.-name t)))

  (unregister!)
  :rcf)