(ns gh-diff
  "View GitHub commit diffs in VS Code's diff view.

   Usage:
   - Call (view-github-commit!+) to enter a commit URL interactively
   - Call (show-commit-file-picker!+ url) with a commit URL directly"
  (:require ["vscode" :as vscode]
            [promesa.core :as p]
            [clojure.string :as str]))

;; Virtual document scheme for GitHub diffs
(def ^:private github-scheme "gh-diff")

(defn ^:private get-github-token!+ []
  (p/let [session (vscode/authentication.getSession
                    "github"
                    #js []
                    #js {:createIfNone true})]
    (.-accessToken session)))

;; Memoized fetch to avoid abusing GitHub
(def ^:private fetch-url!+
  (memoize
   (fn [url]
     (p/let [resp (js/fetch url)
             content (.text resp)]
       content))))

;; Memoized commit info fetch with authentication
(def ^:private fetch-commit-info!+
  (memoize
   (fn [owner repo sha]
     (p/let [token (get-github-token!+)
             resp (js/fetch (str "https://api.github.com/repos/" owner "/" repo "/commits/" sha)
                            #js {:headers #js {"Authorization" (str "token " token)}})
             data (.json resp)]
       (js->clj data :keywordize-keys true)))))

;; Text document content provider that fetches content on-demand
;; URI format: gh-diff:owner/repo/sha/filepath
(def ^:private provider
  #js {:provideTextDocumentContent
       (fn [uri]
         (let [path (.-path uri)
               parts (str/split path #"/")
               owner (first parts)
               repo (second parts)
               sha (nth parts 2)
               filepath (str/join "/" (drop 3 parts))
               url (str "https://raw.githubusercontent.com/" owner "/" repo "/" sha "/" filepath)]
           (fetch-url!+ url)))})

;; Register the content provider (only once)
(defonce ^:private _registration
  (vscode/workspace.registerTextDocumentContentProvider github-scheme provider))

(defn parse-github-commit-url
  "Parse a GitHub commit URL into owner, repo, and SHA components.
   Handles both direct commit URLs and PR commit URLs.
   Returns nil if the URL is not a valid GitHub commit URL."
  [url]
  (or
   ;; Direct commit URL: /owner/repo/commit/sha
   (when-let [[_ owner repo sha] (re-find #"github\.com/([^/]+)/([^/]+)/commit/([a-f0-9]+)" url)]
     {:owner owner :repo repo :sha sha})
   ;; PR commit URL: /owner/repo/pull/NUMBER/commits/sha
   (when-let [[_ owner repo sha] (re-find #"github\.com/([^/]+)/([^/]+)/pull/\d+/commits/([a-f0-9]+)" url)]
     {:owner owner :repo repo :sha sha})))

(defn show-commit-diff!+
  "Open a diff view for a specific file in a GitHub commit.

   Args:
   - commit-url: GitHub commit URL
   - file-index: Index of the file in the commit's file list

   Returns a promise that resolves when the diff view is opened."
  [commit-url file-index]
  (p/let [{:keys [owner repo sha]} (parse-github-commit-url commit-url)

          ;; Fetch commit data from GitHub API (memoized)
          commit-clj (fetch-commit-info!+ owner repo sha)

          parent-sha (-> commit-clj :parents first :sha)
          files (:files commit-clj)
          file-data (nth files file-index)
          filepath (:filename file-data)

          ;; Create virtual document URIs with full path (owner/repo/sha/filepath)
          ;; The provider will fetch content on-demand
          left-uri (vscode/Uri.parse (str github-scheme ":" owner "/" repo "/" parent-sha "/" filepath))
          right-uri (vscode/Uri.parse (str github-scheme ":" owner "/" repo "/" sha "/" filepath))]

    ;; Open diff view - content will be fetched by provider
    ;; preserveFocus keeps focus on the picker when navigating
    (vscode/commands.executeCommand "vscode.diff"
                                     left-uri
                                     right-uri
                                     (str filepath " (commit " (subs sha 0 7) ")")
                                     #js {:preserveFocus true})))

(defn show-commit-file-picker!+
  "Show a persistent file picker for all files changed in a GitHub commit.
   Opens diff views as you navigate through items (arrow keys).
   Picker stays open in background while viewing diffs.

   Args:
   - commit-url: GitHub commit URL

   Returns a promise."
  [commit-url]
  (p/let [{:keys [owner repo sha]} (parse-github-commit-url commit-url)

          ;; Fetch commit data (memoized)
          commit-clj (fetch-commit-info!+ owner repo sha)

          files (:files commit-clj)
          file-names (mapv :filename files)

          ;; Create a persistent quick pick
          picker (vscode/window.createQuickPick)]

    ;; Configure the picker to stay open
    (set! (.-items picker) (clj->js (mapv #(js-obj "label" %) file-names)))
    (set! (.-placeholder picker) (str "Navigate to view files (" (count files) " files changed) - Enter to close"))
    (set! (.-canSelectMany picker) false)
    (set! (.-ignoreFocusOut picker) true)  ;; Stay open when clicking elsewhere

    ;; Open diff when navigating through items (arrow keys)
    (.onDidChangeActive picker
                        (fn [items]
                          (when-let [selected (first items)]
                            (let [label (.-label selected)
                                  file-idx (.indexOf file-names label)]
                              (show-commit-diff!+ commit-url file-idx)))))

    ;; Close on accept (pressing Enter)
    (.onDidAccept picker (fn [] (.hide picker)))

    ;; Clean up when hidden
    (.onDidHide picker (fn [] (.dispose picker)))

    ;; Show the picker
    (.show picker)))

(defn view-github-commit!+
  "Interactively view a GitHub commit diff.
   Prompts for a commit URL, then shows a file picker for the changed files.

   Returns a promise."
  []
  (p/let [url (vscode/window.showInputBox
               #js {:prompt "Enter GitHub commit URL"
                    :placeHolder "https://github.com/owner/repo/commit/sha"
                    :validateInput (fn [value]
                                     (when (and (seq value)
                                               (not (parse-github-commit-url value)))
                                       "Please enter a valid GitHub commit URL"))})]
    (when (and url (parse-github-commit-url url))
      (show-commit-file-picker!+ url))))

(comment
  ;; Try it out!
  (view-github-commit!+)

  ;; Or directly with a URL
  (show-commit-file-picker!+ "https://github.com/microsoft/vscode/commit/d92c1a8aa7c097181ab04d3fc0365bc2923d12ec")

  ;; Or view a specific file (index 0)
  (show-commit-diff!+ "https://github.com/microsoft/vscode/commit/d92c1a8aa7c097181ab04d3fc0365bc2923d12ec" 0)

  (show-commit-file-picker!+ "https://github.com/BetterThanTomorrow/calva/pull/2968/commits/fa7d18062645a23c61fbaa0ff8bd6613e31ebe9e")

  (show-commit-file-picker!+ "https://github.com/BetterThanTomorrow/calva/pull/2968/commits/2a167be92b4a898586547b76fa48cfb7efe6ce3c")
  )