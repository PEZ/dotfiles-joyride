(ns user-activate
  (:require ["vscode" :as vscode]
            pastedown
            [joyride.core :as joyride]
            [promesa.core :as p]))

;; Keep tally on VS Code disposables we register
(defonce !db (atom {:disposables []}))

;; To make the activation script re-runnable we dispose of
;; event handlers and such that we might have registered
;; in previous runs.
(defn- clear-disposables! []
  (run! (fn [disposable]
          (.dispose disposable))
        (:disposables @!db))
  (swap! !db assoc :disposables []))

;; Pushing the disposables on the extension context's
;; subscriptions will make VS Code dispose of them when the
;; Joyride extension is deactivated.
(defn- push-disposable! [disposable]
  (swap! !db update :disposables conj disposable)
  (-> (joyride/extension-context)
      .-subscriptions
      (.push disposable)))

(defn- my-main []
  (println "Hello World 2, from my-main in user_activate.cljs script")
  (clear-disposables!) ;; Any disposables add with `push-disposable!`
  ;; will be cleared now. You can push them anew.

  (push-disposable! (#_{:clj-kondo/ignore [:unresolved-symbol]}
                     #_(requiring-resolve 'highlight-thousands/activate!)))
  (pastedown/activate!)
  (vscode/commands.executeCommand "github.copilot.debug.showChatLogView" true))

(when (= (joyride/invoked-script) joyride/*file*)
  (println "Hello World 1, from my-main in user_activate.cljs script")
  (my-main))

(comment
  (js-keys (second (:disposables @!db)))
  :rcf)

"🎉"

;; For more examples see:
;;   https://github.com/BetterThanTomorrow/joyride/tree/master/examples



