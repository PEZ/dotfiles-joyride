(ns markdown-paste-provider
  "Add Markdown formatting options to VS Code's 'Paste As...' command"
  (:require
   ["turndown" :as TurndownService]
   ["vscode" :as vscode]
   [clojure.string :as s]
   [joyride.core :as joyride]
   [promesa.core :as p]))

;; Keep tally on VS Code disposables we register
(defonce !db (atom {:disposables []}))

;; To make the script re-runnable we dispose of
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

(defn convert-to-markdown
  "Intelligently convert clipboard content to markdown using turndown"
  [dataTransfer]
  (def dataTransfer dataTransfer) ; For debugging in REPL
  (p/let [html (some-> dataTransfer
                       (.get "text/html")
                       (.asString))
          plain-text (some-> dataTransfer
                             (.get "text/plain")
                             (.asString))
          turndown-service (TurndownService.)]
    (def html html)
    (def plain-text plain-text)
    (def turndown-service turndown-service)
    (if (and html (not (s/blank? html)))
      (.turndown turndown-service html)
      plain-text)))

(comment
  (p/let [html (some-> dataTransfer
                       (.get "text/html")
                       (.asString))]
    (def html html))
  (joyride.core/js-properties (some-> dataTransfer
                                      (.get "text/html")))

  :rcf)

(defn create-markdown-paste-edits
  "Create a single markdown paste edit with intelligent formatting"
  [dataTransfer]
  (let [markdown-content (convert-to-markdown dataTransfer)]
    #js [(new vscode/DocumentPasteEdit
              markdown-content
              "Paste as Markdown"
              vscode/DocumentDropOrPasteEditKind.Text)]))

(defn create-markdown-paste-provider
  "Create a paste provider that intelligently converts rich text to markdown"
  []
  #js {:provideDocumentPasteEdits
       (fn [_document _ranges dataTransfer _context _token]
         (when-let [raw-text (.get dataTransfer "text/plain")]
           (let [text (if (string? raw-text) raw-text (str raw-text))]
             (when (and text (not (s/blank? text)))
               (create-markdown-paste-edits dataTransfer)))))})

(defn register-markdown-paste-provider!
  "Register the markdown paste provider with VS Code"
  []
  (clear-disposables!) ; Clear any existing providers
  (let [provider (create-markdown-paste-provider)
        disposable (vscode/languages.registerDocumentPasteEditProvider
                    "*"  ; Apply to all file types
                    provider
                    #js {:providedPasteEditKinds #js [vscode/DocumentDropOrPasteEditKind.Text]
                         :pasteMimeTypes #js ["text/plain" "text/html"]})]
    (push-disposable! disposable)

    (vscode/window.showInformationMessage
     "Markdown paste provider registered! Use Ctrl+Shift+V (Cmd+Shift+V on Mac) after copying text to see markdown formatting options.")

    disposable))

(defn deactivate!
  "Remove all markdown paste providers"
  []
  (clear-disposables!)
  (vscode/window.showInformationMessage
   "Markdown paste providers removed."))

(defn status
  "Show current status of markdown paste providers"
  []
  (let [count (count (:disposables @!db))]
    {:active? (pos? count)
     :providers-count count
     :disposables (:disposables @!db)}))

(defn main
  "Main function to register the markdown paste provider"
  []
  (register-markdown-paste-provider!))

;; Auto-run when script is invoked
(when (= (joyride/invoked-script) joyride/*file*)
  (main))

(comment
  ;; Manual management examples:

  ;; Check current status
  (status)

  ;; Register the provider
  (register-markdown-paste-provider!)

  ;; Remove all providers
  (deactivate!)

  ;; Check what disposables are registered
  (:disposables @!db)

  :rcf)
