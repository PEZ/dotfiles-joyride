(ns lm-fiddle
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


(defn async-iterator-seq
  "Consumes an async generator/iterator and returns a promise of all values.
  Works with any JavaScript object that implements Symbol.asyncIterator.

  Example:
    (p/let [values (async-iterator-seq some-async-generator)]
      (prn values)) ; => [val1 val2 val3 ...]"
  [generator]
  (let [iter (.call (aget generator js/Symbol.asyncIterator) generator)
        results (atom [])]
    (p/loop []
      (p/let [v (.next iter)]
        (if (.-done v)
          @results
          (do (swap! results conj (.-value v))
              (p/recur)))))))

(defn consume-lm-response
  "Consumes a Language Model response stream and returns the complete text.

  Example:
    (p/let [response (make-lm-request model messages)
            text (consume-lm-response response)]
      (prn text))"
  [response]
  (p/let [chunks (async-iterator-seq (.-text response))]
    (apply str chunks)))