(ns markdown-paste-provider
  "Add Markdown formatting options to VS Code's 'Paste As...' command"
  (:require
   ["vscode" :as vscode]
   [clojure.string :as s]
   [joyride.core :as joyride]))

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

(defn format-as-bold
  "Wrap text in markdown bold formatting"
  [text]
  (str "**" text "**"))

(defn format-as-italic
  "Wrap text in markdown italic formatting"
  [text]
  (str "*" text "*"))

(defn format-as-code
  "Wrap text in markdown inline code formatting"
  [text]
  (str "`" text "`"))

(defn format-as-code-block
  "Wrap text in markdown code block formatting"
  [text]
  (str "```\n" text "\n```"))

(defn format-as-quote
  "Format text as markdown blockquote"
  [text]
  (->> (s/split-lines text)
       (map #(str "> " %))
       (s/join "\n")))

(defn format-as-link
  "Format text as markdown link (assumes text is a URL)"
  [text]
  (if (re-find #"^https?://" text)
    (str "[" text "](" text ")")
    (str "[link text](" text ")")))

(defn create-markdown-paste-edits
  "Create multiple paste edit options for markdown formatting"
  [text]
  (let [formats [{:formatter format-as-bold
                  :title "Paste as Markdown Bold"
                  :kind vscode/DocumentDropOrPasteEditKind.Empty}
                 {:formatter format-as-italic
                  :title "Paste as Markdown Italic"
                  :kind vscode/DocumentDropOrPasteEditKind.Empty}
                 {:formatter format-as-code
                  :title "Paste as Markdown Inline Code"
                  :kind vscode/DocumentDropOrPasteEditKind.Empty}
                 {:formatter format-as-code-block
                  :title "Paste as Markdown Code Block"
                  :kind vscode/DocumentDropOrPasteEditKind.Empty}
                 {:formatter format-as-quote
                  :title "Paste as Markdown Quote"
                  :kind vscode/DocumentDropOrPasteEditKind.Empty}
                 {:formatter format-as-link
                  :title "Paste as Markdown Link"
                  :kind vscode/DocumentDropOrPasteEditKind.Empty}]]
    (->> formats
         (map (fn [{:keys [formatter title kind]}]
                (new vscode/DocumentPasteEdit
                     (formatter text)
                     title
                     kind)))
         (into-array))))

(defn create-markdown-paste-provider
  "Create a paste provider that offers markdown formatting options"
  []
  #js {:provideDocumentPasteEdits
       (fn [_document _ranges dataTransfer _context _token]
         (when-let [text (.get dataTransfer "text/plain")]
           (when (and text (not (s/blank? text)))
             (create-markdown-paste-edits text))))})

(defn register-markdown-paste-provider!
  "Register the markdown paste provider with VS Code"
  []
  (clear-disposables!) ; Clear any existing providers
  (let [provider (create-markdown-paste-provider)
        disposable (vscode/languages.registerDocumentPasteEditProvider
                    "*"  ; Apply to all file types
                    provider
                    #js {:providedPasteEditKinds #js [vscode/DocumentDropOrPasteEditKind.Empty]
                         :pasteMimeTypes #js ["text/plain"]})]
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

  ;; Test individual formatters
  (format-as-bold "Hello World")
  (format-as-italic "Hello World")
  (format-as-code "console.log('hello')")
  (format-as-code-block "function test() {\n  return 'hello';\n}")
  (format-as-quote "This is a quote\nwith multiple lines")
  (format-as-link "https://github.com")

  :rcf)
