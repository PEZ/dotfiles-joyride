;; npm install turndown turndown-plugin-gfm

(ns pastedown
  "Add Markdown formatting options to VS Code's 'Paste As...' command"
  (:require
   ["turndown" :as TurndownService]
   ["turndown-plugin-gfm" :as gfm]
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

(defn- clean-list-content
  "Clean content but don't double-indent nested lists that are already indented"
  [content indent-spaces]
  (let [indent (apply str (repeat indent-spaces " "))]
    (-> content
        (.replace #"^\n+" "")              ; remove leading newlines
        (.replace #"\n+$" "\n")            ; keep single trailing newline
        ;; Only indent lines that don't already start with spaces (nested lists)
        (.replace #"\n(?! )" (str "\n" indent)))))  ; indent nested content        ; indent nested with single space

(defn- get-list-prefix
  "Returns the prefix for a list item (bullet or number)"
  [node options]
  (let [parent (.-parentNode node)]
    (if (= (.-nodeName parent) "OL")
      ;; Ordered list - calculate the number
      (let [start (.getAttribute parent "start")
            children (.-children parent)
            index (.call (.-indexOf js/Array.prototype) children node)
            num (if start (+ (js/parseInt start) index) (inc index))]
        (str num ". "))
      ;; Unordered list - use bullet marker
      (str (.-bulletListMarker options) " "))))

(defn- get-leading-indent
  "Get fixed indent: 2 spaces for bullet lists, 3 for numbered lists"
  [node]
  (when-let [parent (.-parentNode node)]
    (when (or (= (.-nodeName parent) "OL") (= (.-nodeName parent) "UL"))
      (when-let [grandparent (.-parentNode parent)]
        (when (= (.-nodeName grandparent) "LI")
          (let [gg-parent (.-parentNode grandparent)]
            (if (= (.-nodeName gg-parent) "OL")
              3  ; parent is in numbered list
              2)))))))

(defn- list-item-replacement
  "Replacement function for list items with proper 2/3 space nesting alignment"
  [content node options]
  (let [prefix (get-list-prefix node options)
        leading-indent-count (or (get-leading-indent node) 0)
        leading-indent (apply str (repeat leading-indent-count " "))
        total-indent-spaces (+ leading-indent-count (count prefix))
        cleaned-content (clean-list-content content total-indent-spaces)
        has-next (.-nextSibling node)
        needs-trailing-nl (and has-next (not (.test #"\n$" cleaned-content)))
        trailing-nl (if needs-trailing-nl "\n" "")]
    (str leading-indent prefix cleaned-content trailing-nl)))

(defn convert-to-markdown
  "Intelligently convert clipboard content to markdown using turndown with full GFM support"
  [dataTransfer]
  (let [html-item (.get dataTransfer "text/html")
        plain-item (.get dataTransfer "text/plain")]
    (p/let [html (when html-item (.asString html-item))
            plain-text (when plain-item (.asString plain-item))
            turndown-service (TurndownService. #js {:headingStyle "atx"
                                                    :hr "---"
                                                    :bulletListMarker "-"
                                                    :codeBlockStyle "fenced"
                                                    :fence "```"
                                                    :emDelimiter "*"
                                                    :strongDelimiter "**"})]
      ;; Add full GFM support (tables, strikethrough, task lists, highlighted code blocks)
      (.use turndown-service (.-gfm gfm))
      ;; Add custom list item rule with minimal spacing
      (.addRule turndown-service "listItem"
                #js {:filter "li"
                     :replacement list-item-replacement})
      (cond
        (and html (not (s/blank? html)))
        (.turndown turndown-service html)

        ;; If it looks like a URL, format as link
        (and plain-text (re-find #"^https?://" plain-text))
        (str "[" plain-text "](" plain-text ")")

        :else
        (str plain-text)))))

(defn create-markdown-paste-edits
  "Create a single markdown paste edit with intelligent formatting"
  [dataTransfer]
  ;; Return a promise since convert-to-markdown is now async
  (p/let [markdown-content (convert-to-markdown dataTransfer)]
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

    (println "📋 Markdown paste provider registered! Use Ctrl+Shift+V (Cmd+Shift+V on Mac) after copying text to see markdown formatting options.")

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

(defn activate!
  "Main function to register the markdown paste provider"
  []
  (register-markdown-paste-provider!))

;; Auto-run when script is invoked
(when (= (joyride/invoked-script) joyride/*file*)
  (activate!))

(comment
  ;; Manual management examples:

  ;; Check current status
  (status)

  ;; Register the provider
  (activate!)

  ;; Remove all providers
  (deactivate!)

  ;; Check what disposables are registered
  (:disposables @!db)

  :rcf)
