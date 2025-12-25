;; Flare utilities for Joyride sidebar and webview management
;;
;; Keybindings:
;; {
;;   "key": "ctrl+alt+j ctrl+alt+b",
;;   "command": "joyride.runCode",
;;   "args": "(do (require '[flares] :reload) (flares/prompt-and-open-url-in-sidebar!+))"
;; },
;; {
;;   "key": "ctrl+alt+j alt+b",
;;   "command": "joyride.runCode",
;;   "args": "(do (require '[flares] :reload) (flares/prompt-and-open-url-as-panel!+))"
;; },
;; {
;;   "key": "ctrl+alt+j ctrl+shift+f",
;;   "command": "joyride.runCode",
;;   "args": "(do (require '[flares] :reload) (flares/show-flares-picker!+))"
;; },

(ns flares
  "Flare utilities for Joyride sidebar and webview management."
  (:require
   [joyride.core :as joyride]
   [joyride.flare :as flare]
   [promesa.core :as p]
   ["vscode" :as vscode]))

(defonce !state (atom {:state/slot->url {}}))

(def ^:private all-sidebar-slots
  #{:sidebar-1 :sidebar-2 :sidebar-3 :sidebar-4 :sidebar-5})

(defn find-free-sidebar-slot
  "Returns first available sidebar slot, or nil if all are occupied."
  []
  (let [occupied-slots (set (keys (flare/ls)))
        free-slots (apply disj all-sidebar-slots occupied-slots)]
    (first free-slots)))

(defn- localhost?
  "Returns true if `url` starts with localhost or 127.0.0.1."
  [url]
  (boolean (re-matches #"(?:localhost|127\.0\.0\.1)(?::\d+)?(?:/.*)?" url)))

(defn- normalize-url
  "Prepends https:// to `url` if it doesn't start with http:// or https://.
   Uses http:// for localhost and 127.0.0.1."
  [url]
  (cond
    (re-matches #"https?://.*" url) url
    (localhost? url) (str "http://" url)
    :else (str "https://" url)))

(defn- valid-url-input?
  "Returns true if `value` looks like a valid URL, bare domain, or localhost."
  [value]
  (or (empty? value)
      (some? (re-matches #"https?://.*" value))
      (localhost? value)
      (some? (re-matches #"[a-zA-Z0-9][-a-zA-Z0-9]*(?:\.[a-zA-Z0-9][-a-zA-Z0-9]*)+(?:/.*)?" value))))

(def ^:private ws-state (.-workspaceState (joyride/extension-context)))
(def ^:private history-key "flares.url-history")
(def ^:private last-picked-key "flares.last-picked-url")
(def ^:private max-history 10)

(defn- get-url-history []
  (or (js->clj (.get ws-state history-key)) []))

(defn- get-last-picked []
  (.get ws-state last-picked-key))

(defn- add-to-history!+ [url]
  (let [history (get-url-history)
        new-history (->> (cons url (remove #{url} history))
                         (take max-history)
                         vec)]
    (p/do
      (.update ws-state history-key (clj->js new-history))
      (.update ws-state last-picked-key url)
      new-history)))

(defn- url->quick-pick-item [url & {:keys [is-typed?]}]
  #js {:label url
       :description (when is-typed? "⏎ Open this URL")
       :iconPath (vscode/ThemeIcon. (if is-typed? "globe" "history"))
       :url url
       :is-typed? is-typed?})

(defn- build-url-picker-items [typed-value history]
  (let [typed-valid? (and (seq typed-value) (valid-url-input? typed-value))
        typed-item (when typed-valid?
                     (url->quick-pick-item typed-value :is-typed? true))
        history-items (->> history
                           (remove #(and typed-valid? (= % typed-value)))
                           (map url->quick-pick-item))]
    (if typed-item
      (into [typed-item] history-items)
      (into [] history-items))))

(defn- show-url-picker!+
  "Shows a URL picker with history. Returns promise of selected URL or nil."
  []
  (let [history (get-url-history)
        last-picked (get-last-picked)
        picker (vscode/window.createQuickPick)
        initial-items (build-url-picker-items "" history)]

    (set! (.-items picker) (into-array initial-items))
    (set! (.-title picker) "Open URL")
    (set! (.-placeholder picker) "Type URL or select from history")
    (set! (.-matchOnDescription picker) false)
    (set! (.-ignoreFocusOut picker) true)

    (when-let [last-item (some #(when (= (.-url %) last-picked) %) initial-items)]
      (set! (.-activeItems picker) #js [last-item]))

    (p/create
     (fn [resolve _reject]
       (.onDidChangeValue picker
                          (fn [value]
                            (let [new-items (build-url-picker-items value history)
                                  items-array (into-array new-items)]
                              (set! (.-items picker) items-array)
                              (when (seq new-items)
                                (set! (.-activeItems picker) #js [(first new-items)])))))

       (.onDidAccept picker
                     (fn []
                       (when-let [selected (first (.-selectedItems picker))]
                         (let [url (.-url selected)]
                           (.hide picker)
                           (resolve url)))))

       (.onDidHide picker (fn [] (resolve nil)))
       (.show picker)))))

(defn- open-url+! [url key]
  (p/let [normalized-url (normalize-url url)
          _ (flare/flare!+  {:key key
                             :url normalized-url
                             :title (or (second (re-find #"https?://(?:www\.)?([^/]+)" normalized-url))
                                        "Browser")
                             :reveal? true})]
    (add-to-history!+ normalized-url)
    (swap! !state assoc-in [:state/slot->url key] normalized-url)))

(defn open-url-in-sidebar!+
  "Returns promise of opening `url` in an available sidebar flare slot.
   Prepends https:// if not present. Returns the slot keyword on success,
   nil if no slots available."
  [url]
  (if-let [slot (find-free-sidebar-slot)]
    (p/let [_ (open-url+! url slot)]
      slot)
    (do
      (vscode/window.showWarningMessage "No free sidebar slots available")
      nil)))

(defn prompt-and-open-url-in-sidebar!+
  "Shows URL picker with history and opens selected URL in sidebar.
   Returns the slot keyword on success, nil if cancelled or no slots available."
  []
  (p/let [url (show-url-picker!+)]
    (when url
      (p/let [result (open-url-in-sidebar!+ url)]
        result))))

(defn open-url-as-panel!+
  "Returns promise of opening `url` as an editor panel flare.
   Prepends https:// if not present. Returns the panel key on success."
  [url]
  (let [normalized-url (normalize-url url)
        panel-key (keyword (str "panel-" (second (re-find #"https?://(?:www\.)?([^/]+)" normalized-url))))]
    (p/let [_ (open-url+! url panel-key)]
      panel-key)))

(defn prompt-and-open-url-as-panel!+
  "Shows URL picker with history and opens selected URL as panel.
   Returns the panel key on success, nil if cancelled."
  []
  (p/let [url (show-url-picker!+)]
    (when url
      (p/let [result (open-url-as-panel!+ url)]
        result))))

(defn- sidebar-slot?
  "Returns true if `slot` is a sidebar slot keyword."
  [slot]
  (boolean (re-matches #"sidebar-\d+" (name slot))))

(defn- flare-type
  "Returns :sidebar or :panel based on the `slot`."
  [slot]
  (if (sidebar-slot? slot) :sidebar :panel))

(defn- flare->quick-pick-item
  "Converts a flare entry `[slot flare-info]` to a QuickPickItem with close button."
  [[slot {:keys [view]}]]
  (def slot slot)
  (let [title (.-title view)
        kind (flare-type slot)
        visible? (.-visible view)
        buttons #js [#js {:iconPath (vscode/ThemeIcon. "close")
                          :tooltip "Close"
                          :action "close"}]]
    (when (get-in @!state [:state/slot->url slot])
      (.unshift buttons #js {:iconPath (vscode/ThemeIcon. "refresh")
                             :tooltip "Reload"
                             :action "reload"}))
    #js {:label (or title (name slot))
         :description (str (name kind)
                           (when-not visible? " (hidden)"))
         :iconPath (vscode/ThemeIcon.
                    (if (= kind :sidebar) "layout-sidebar-right" "window"))
         :slot slot
         :buttons buttons}))

(defn- make-action-items
  "Returns the permanent action items (separator + open URL options)."
  []
  [#js {:label ""
        :kind vscode/QuickPickItemKind.Separator}
   #js {:label "Open URL in Sidebar..."
        :iconPath (vscode/ThemeIcon. "layout-sidebar-right")
        :description ""
        :action :open-url-sidebar}
   #js {:label "Open URL as Panel..."
        :iconPath (vscode/ThemeIcon. "window")
        :description ""
        :action :open-url-panel}])

(defn- build-picker-items
  "Builds all picker items: flare items + action items."
  []
  (let [flare-items (mapv flare->quick-pick-item (flare/ls))
        action-items (make-action-items)]
    (into flare-items action-items)))

(defn show-flares-picker!+
  "Returns promise of showing a quick pick for managing flares.
   Select to reveal, click close button to close. Includes Open URL actions."
  []
  (let [items (build-picker-items)
        picker (vscode/window.createQuickPick)]
    (set! (.-items picker) (into-array items))
    (set! (.-title picker) "Flares")
    (set! (.-placeholder picker) "Select to reveal, click × to close")
    (set! (.-matchOnDescription picker) true)
    (set! (.-ignoreFocusOut picker) true)

    (p/create
     (fn [resolve _reject]
       (.onDidAccept picker
                     (fn []
                       (when-let [selected (first (.-selectedItems picker))]
                         (if-let [action (.-action selected)]
                           (do
                             (.hide picker)
                             (case action
                               :open-url-sidebar (prompt-and-open-url-in-sidebar!+)
                               :open-url-panel (prompt-and-open-url-as-panel!+)))
                           (let [slot (.-slot selected)
                                 view (:view (get (flare/ls) slot))]
                             (when view
                               (.show view true)))))
                       (.hide picker)
                       (resolve nil)))

       (.onDidTriggerItemButton picker
                                (fn [event]
                                  (let [slot (-> event .-item .-slot)
                                        action (-> event .-button .-action)]
                                    (case action
                                      "close" (do (flare/close! slot)
                                                  (swap! !state update :state/slot->url dissoc slot))
                                      "reload" (open-url+! (get-in @!state [:state/slot->url slot]) slot)
                                      :nop)
                                    (set! (.-items picker) (into-array (build-picker-items))))))

       (.onDidHide picker (fn [] (resolve nil)))
       (.show picker)))))

(comment
  (find-free-sidebar-slot)
  (open-url-in-sidebar!+ "https://clojure.org")
  (open-url-as-panel!+ "https://github.com")
  (prompt-and-open-url-in-sidebar!+)
  (prompt-and-open-url-as-panel!+)
  (show-flares-picker!+)
  (flare/ls)
  (flare/close-all!)
  :rcf)
