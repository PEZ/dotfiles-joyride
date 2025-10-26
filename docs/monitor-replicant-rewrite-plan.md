# Monitor Replicant Rewrite Plan

## Executive Summary

Rewrite `lm-dispatch.monitor` to use Replicant for reactive UI rendering and bidirectional message passing between host (Joyride) and webview (Scittle). This eliminates full HTML regeneration on every state change, provides better separation of concerns, and enables more interactive UI features.

## Current Architecture Problems

1. **Full HTML regeneration**: Every state change calls `update-agent-monitor-flare!+` which rebuilds entire HTML
2. **No UI state**: All state lives in host, making interactive UI features difficult
3. **Tight coupling**: UI rendering mixed with state management and event handling
4. **Inefficient updates**: Small changes (like incrementing turn count) cause full re-render

## Proposed Architecture

### Data Flow

```
┌─────────────────────────────────────────────────────────────┐
│ HOST (Joyride ClojureScript)                                │
│                                                              │
│  lm-dispatch.state/!agent-state                             │
│       │                                                      │
│       ├─ Conversations: [{:agent.conversation/id 1 ...}]    │
│       ├─ Watch detects changes                              │
│       └─ Send state snapshot via message                    │
│                                                              │
│  Monitor Functions:                                          │
│  • start-monitoring-conversation!+ → registers + sends msg  │
│  • log-and-update!+ → updates state + sends msg             │
│  • cancel-conversation! → updates + sends msg               │
│  • delete-conversation! → updates + sends msg               │
│                                                              │
│  Message Handler:                                            │
│  • :cancel-conversation → cancel-conversation!              │
│  • :delete-conversation → delete-conversation!              │
│  • :show-results → open-results-document!+                  │
│  • :show-logs → logging channel                             │
└──────────────────┬──────────────────────────────────────────┘
                   │
                   │ postMessage
                   ↓
┌─────────────────────────────────────────────────────────────┐
│ WEBVIEW (Scittle + Replicant)                               │
│                                                              │
│  !monitor-state atom                                         │
│       │                                                      │
│       ├─ {:conversations [...]}                             │
│       ├─ add-watch → triggers re-render                     │
│       └─ Updated via incoming messages                      │
│                                                              │
│  UI Transform Pipeline:                                      │
│  conversations → conversations->ui-data → Hiccup → Render   │
│                                                              │
│  Replicant Dispatch:                                         │
│  • r/set-dispatch! handles action vectors                   │
│  • [:cancel-conversation id] → postMessage to host          │
│  • [:delete-conversation id] → postMessage to host          │
│  • [:show-results id] → postMessage to host                 │
│  • [:show-logs] → postMessage to host                       │
│                                                              │
│  Message Receiver:                                           │
│  • :state-update → swap! !monitor-state                     │
│  • :conversation-update → merge into state                  │
└─────────────────┬───────────────────────────────────────────┘
                   │
                   │ postMessage
                   ↓
                Back to HOST
```

## Lessons from TicTacToe

### 1. Pure State Management
```clojure
;; Game state is just data
{:next-player :x
 :size 3
 :tics {[0 0] :x, [0 1] :o}
 :victory {:player :x :path [[0 0] [1 1] [2 2]]}
 :over? true}

;; Pure functions transform state
(defn tic [game y x]
  (-> game
      (assoc-in [:tics [y x]] player)
      (assoc :next-player (next-player player))
      (maybe-conclude y x)))
```

**Applied to Monitor:**
```clojure
;; Monitor state in webview
{:conversations [{:agent.conversation/id 1
                  :agent.conversation/goal "..."
                  :agent.conversation/status :working
                  :agent.conversation/current-turn 3
                  :agent.conversation/max-turns 10
                  ...}]}
```

### 2. UI Data Transformation
```clojure
;; Separate: state → UI data
(defn game->ui-data [{:keys [size tics victory over?]}]
  {:button (when over? {:text "Start over" :on-click [:reset]})
   :board {:rows [...]}})
```

**Applied to Monitor:**
```clojure
(defn conversations->ui-data [conversations]
  {:header {:text "Dispatch Monitor"
            :show-logs-action [:show-logs]}
   :conversations
   (for [conv conversations]
     {:id (:agent.conversation/id conv)
      :title (:agent.conversation/title conv)
      :status-icon (status-icon (:agent.conversation/status conv))
      :actions (when (#{:working :started} (:agent.conversation/status conv))
                 {:cancel [:cancel-conversation (:agent.conversation/id conv)]})
      ...})})
```

### 3. Replicant Event Dispatch
```clojure
;; Events are DATA VECTORS, not functions!
{:on-click [:tic y x]}  ; Not #(tic y x)

;; Central dispatch routes actions
(r/set-dispatch!
 (fn [_ [action & args]]
   (case action
     :tic (apply swap! store game/tic args)
     :reset (start-new-game store))))
```

**Applied to Monitor:**
```clojure
;; In webview Scittle
(r/set-dispatch!
 (fn [_ [action & args]]
   (case action
     :cancel-conversation (send-to-host! :cancel-conversation (first args))
     :delete-conversation (send-to-host! :delete-conversation (first args))
     :show-results (send-to-host! :show-results (first args))
     :show-logs (send-to-host! :show-logs))))
```

### 4. Reactive Rendering
```clojure
;; Watch atom, re-render on change
(add-watch store ::render
  (fn [_ _ _ game]
    (->> (ui/game->ui-data game)
         ui/render-game
         (r/render el))))
```

**Applied to Monitor:**
```clojure
;; In webview
(add-watch !monitor-state ::render
  (fn [_ _ _ state]
    (->> (:conversations state)
         conversations->ui-data
         render-monitor
         (r/render el))))
```

## Lessons from Message Handler Examples

### Webview → Host Communication
```clojure
;; In Scittle (webview)
(def vscode (js/acquireVsCodeApi))

(defn send-to-host! [message-type data]
  (.postMessage vscode
    (clj->js {:type message-type
              :data data
              :timestamp (.now js/Date)})))

;; Usage
(send-to-host! :cancel-conversation {:id 42})
```

### Host → Webview Communication
```clojure
;; In Joyride (host)
(flare/post-message!+ :sidebar-1
  {:type :state-update
   :data {:conversations [...]}})
```

### Receiving Messages in Webview
```clojure
;; In Scittle
(defn handle-incoming-message [message-event]
  (let [message (js->clj (.-data message-event) :keywordize-keys true)
        msg-type (:type message)
        msg-data (:data message)]
    (case msg-type
      :state-update (reset! !monitor-state msg-data)
      :conversation-update (update-conversation! msg-data)
      (js/console.warn "Unknown message type:" msg-type))))

(.addEventListener js/window "message" handle-incoming-message)
```

## Implementation Plan

### Phase 1: Create Replicant UI Resources

**File: `resources/scittle/monitor/ui.cljs`**
```clojure
(ns monitor.ui
  "Pure UI rendering functions - transforms data to Hiccup")

(defn status-icon [status]
  (case status
    :started "codicon-debug-pause"
    :working "codicon-loading codicon-modifier-spin"
    :cancel-requested "codicon-loading codicon-modifier-spin"
    :task-complete "codicon-check"
    :max-turns-reached "codicon-clock"
    :agent-finished "codicon-info"
    :done "codicon-pass"
    :error "codicon-error"
    :cancelled "codicon-debug-stop"
    "codicon-question"))

(defn status-color [status]
  (case status
    :task-complete "var(--vscode-charts-green)"
    :max-turns-reached "var(--vscode-charts-yellow)"
    :agent-finished "var(--vscode-charts-blue)"
    :done "var(--vscode-charts-green)"
    :error "var(--vscode-charts-red)"
    :working "var(--vscode-charts-blue)"
    :cancel-requested "var(--vscode-charts-orange)"
    :cancelled "var(--vscode-charts-orange)"
    nil))

(defn format-time [js-date]
  (if js-date
    (let [hours (.getHours js-date)
          minutes (.getMinutes js-date)
          pad (fn [n] (if (< n 10) (str "0" n) (str n)))]
      (str (pad hours) ":" (pad minutes)))
    "--:--"))

(defn truncate-text [text max-length]
  (when text
    (if (> (count text) max-length)
      (str (subs text 0 max-length) "...")
      text)))

(defn render-results-section [conv-id results icon-color]
  (when results
    (let [summary (truncate-text results 100)
          result-color (or icon-color "var(--vscode-charts-green)")]
      [:div {:style {:color result-color
                     :font-size "0.85em"
                     :margin-top "4px"
                     :display :flex
                     :justify-content :space-between
                     :align-items :center
                     :gap "8px"}}
       [:span {:style {:flex "1" :min-width "0"}}
        "Results: " summary]
       [:button {:on {:click [:show-results conv-id]}
                 :style {:padding "2px 6px"
                         :background "var(--vscode-button-background)"
                         :color "var(--vscode-button-foreground)"
                         :border :none
                         :border-radius "2px"
                         :cursor :pointer
                         :font-size "0.8em"
                         :flex-shrink "0"}}
        "View Full"]])))

(defn render-conversation [conv]
  (let [{:agent.conversation/keys [id goal status model-id
                                   caller title
                                   current-turn max-turns
                                   started-at error-message
                                   results total-tokens]} conv
        icon-class (status-icon status)
        icon-color (status-color status)
        time-str (format-time started-at)]
    [:div {:style {:border "1px solid var(--vscode-panel-border)"
                   :padding "8px"
                   :margin "4px 0"
                   :border-radius "4px"}
           :replicant/key id}  ; Important for efficient updates!
     [:div {:style {:display :flex
                    :justify-content :space-between
                    :align-items :center
                    :margin-bottom "4px"}}
      [:span {:style {:font-weight :bold
                      :display :flex
                      :align-items :center
                      :gap "4px"
                      :flex "1"
                      :min-width "0"
                      :overflow :hidden
                      :text-overflow :ellipsis
                      :white-space :nowrap}}
       [:i {:class (str "codicon " icon-class)
            :style (merge {:padding-top "2px"
                           :flex-shrink "0"}
                          (when icon-color
                            {:color icon-color}))}]
       [:span {:style {:overflow :hidden
                       :text-overflow :ellipsis}}
        (str "[" id "] ")
        [:span {:style {:color "var(--vscode-charts-foreground)"}}
         title]]]
      [:div {:style {:display :flex
                     :align-items :center
                     :gap "4px"}}
       (when (#{:working :started} status)
         [:button {:on {:click [:cancel-conversation id]}
                   :style {:padding "2px 4px"
                           :background "transparent"
                           :border "1px solid var(--vscode-button-border)"
                           :border-radius "2px"
                           :cursor :pointer
                           :flex-shrink "0"}}
          [:i {:class "codicon codicon-debug-stop"
               :style {:color "var(--vscode-errorForeground)"
                       :font-size "14px"}}]])
       [:span {:style {:font-size "0.9em" :opacity "0.7" :flex-shrink "0"}}
        time-str]
       (when (#{:task-complete :max-turns-reached :agent-finished :cancelled :error :done} status)
         [:button {:on {:click [:delete-conversation id]}
                   :style {:padding "2px 4px"
                           :background "transparent"
                           :border "none"
                           :cursor :pointer
                           :opacity "0.6"
                           :flex-shrink "0"}}
          [:i {:class "codicon codicon-close"
               :style {:color "var(--vscode-foreground)"
                       :font-size "12px"}}]])]]
     [:div {:style {:font-size "0.9em" :margin-bottom "4px"}}
      current-turn "/" max-turns " | "
      [:strong "Tks: "] total-tokens " | "
      model-id " | "
      (when caller
        [:span
         [:strong "Who: "] caller])]
     [:div {:style {:max-height "120px"
                    :overflow-y :auto
                    :font-size "0.9em"
                    :font-family "var(--vscode-editor-font-family)"
                    :white-space :pre-wrap
                    :padding "4px"
                    :background "var(--vscode-editor-background)"
                    :border-radius "2px"}}
      goal]
     (when error-message
       [:div {:style {:color "var(--vscode-errorForeground)"
                      :font-size "0.85em"
                      :margin-top "4px"}}
        "Error: " error-message])
     (render-results-section id results icon-color)]))

(defn render-monitor [conversations]
  (let [sorted-convs (reverse (sort-by :agent.conversation/id conversations))]
    [:div
     [:div {:style {:display :flex
                    :justify-content :space-between
                    :align-items :center
                    :margin-bottom "10px"
                    :padding "0 8px"}}
      [:h2 {:style {:margin "0"}} "Dispatch Monitor"]
      [:button {:on {:click [:show-logs]}
                :style {:padding "4px 8px"
                        :background "var(--vscode-button-background)"
                        :color "var(--vscode-button-foreground)"
                        :border :none
                        :border-radius "2px"
                        :cursor :pointer}}
       "Show Logs"]]
     [:div {:style {:margin-top "10px"
                    :padding "0 8px"}}
      (if (empty? sorted-convs)
        [:p {:style {:font-style :italic :opacity "0.7"}}
         "No active conversations"]
        (into [:div] (map render-conversation sorted-convs)))]]))
```

**File: `resources/scittle/monitor/core.cljs`**
```clojure
(ns monitor.core
  "Main entry point for Replicant-based monitor"
  (:require [replicant.dom :as r]
            [monitor.ui :as ui]))

;; State atom for webview
(defonce !monitor-state (atom {:conversations []}))

;; VS Code API handle
(def vscode (js/acquireVsCodeApi))

;; Message sending
(defn send-to-host! [message-type data]
  (.postMessage vscode
    (clj->js {:type (name message-type)
              :data data
              :timestamp (.now js/Date)})))

;; Replicant dispatch - routes UI actions
(r/set-dispatch!
 (fn [_ [action & args]]
   (case action
     :cancel-conversation (send-to-host! :cancel-conversation {:id (first args)})
     :delete-conversation (send-to-host! :delete-conversation {:id (first args)})
     :show-results (send-to-host! :show-results {:id (first args)})
     :show-logs (send-to-host! :show-logs {})
     (js/console.warn "Unknown action:" action))))

;; Render function
(defn render! []
  (let [el (js/document.getElementById "app")
        state @!monitor-state]
    (->> (:conversations state)
         ui/render-monitor
         (r/render el))))

;; Watch state and re-render
(add-watch !monitor-state ::render
  (fn [_ _ _ _]
    (render!)))

;; Incoming message handler
(defn handle-incoming-message [message-event]
  (let [message (js->clj (.-data message-event) :keywordize-keys true)
        msg-type (keyword (:type message))
        msg-data (:data message)]
    (case msg-type
      :state-update (do
                      (js/console.log "📥 Received state update:" msg-data)
                      (reset! !monitor-state msg-data))
      :conversation-update (do
                             (js/console.log "📥 Conversation update:" msg-data)
                             (swap! !monitor-state
                                    (fn [state]
                                      (update state :conversations
                                              (fn [convs]
                                                (mapv #(if (= (:agent.conversation/id %)
                                                             (:agent.conversation/id msg-data))
                                                        (merge % msg-data)
                                                        %)
                                                      convs))))))
      (js/console.warn "❓ Unknown message type:" msg-type))))

;; Initialize
(defn init! []
  (js/console.log "🚀 Monitor Replicant initializing...")
  (.addEventListener js/window "message" handle-incoming-message)
  (render!)
  (js/console.log "✅ Monitor Replicant ready!"))

(init!)
```

### Phase 2: Update Host Monitor Functions

**Changes to `lm-dispatch.monitor`:**

1. **Initial flare creation with Scittle + Replicant:**
```clojure
(defn create-monitor-flare!+ []
  (when-let [slot (ensure-sidebar-slot!)]
    (flare/flare!+
     {:key slot
      :title "Agent Dispatch"
      :html [:html
             [:head
              [:script {:src "https://cdn.jsdelivr.net/npm/scittle@0.7.28/dist/scittle.js"
                        :type "application/javascript"}]
              [:script {:src "https://cdn.jsdelivr.net/npm/scittle@0.7.28/dist/scittle.replicant.js"
                        :type "application/javascript"}]
              [:script {:type "application/x-scittle"
                        :src "{joyride/user-dir}/resources/scittle/monitor/ui.cljs"}]
              [:script {:type "application/x-scittle"
                        :src "{joyride/user-dir}/resources/scittle/monitor/core.cljs"}]
              [:link {:rel "stylesheet"
                      :href "https://unpkg.com/@vscode/codicons@latest/dist/codicon.css"}]
              [:style "body { margin: 0; padding: 0; }"]]
             [:body
              [:div#app]]]
      :reveal? false
      :preserve-focus? true
      :webview-options {:enableScripts true
                        :retainContextWhenHidden true}
      :message-handler message-handler-fn})))
```

2. **Message handler:**
```clojure
(defn message-handler-fn [msg]
  (let [msg-type (keyword (.-type msg))
        msg-data (js->clj (.-data msg) :keywordize-keys true)]
    (case msg-type
      :cancel-conversation (cancel-conversation! (:id msg-data))
      :delete-conversation (delete-conversation! (:id msg-data))
      :show-results (open-results-document!+ (:id msg-data))
      :show-logs (.show (logging/get-output-channel!))
      (js/console.warn "Unknown message from webview:" msg-type))))
```

3. **State synchronization:**
```clojure
(defn send-state-to-webview!+ []
  (when-let [slot (state/get-sidebar-slot)]
    (let [conversations (state/get-all-conversations)]
      (flare/post-message!+ slot
        {:type "state-update"
         :data {:conversations conversations}}))))
```

4. **Replace full re-render with targeted updates:**
```clojure
;; OLD:
(defn log-and-update!+ [conv-id status-updates & messages]
  (logging/log-to-channel! conv-id (string/join " " messages))
  (when status-updates
    (state/update-conversation! conv-id status-updates))
  (update-agent-monitor-flare!+))  ; Full HTML rebuild!

;; NEW:
(defn log-and-update!+ [conv-id status-updates & messages]
  (logging/log-to-channel! conv-id (string/join " " messages))
  (when status-updates
    (state/update-conversation! conv-id status-updates)
    (send-state-to-webview!+)))  ; Just send updated state!
```

### Phase 3: Add State Watcher

```clojure
;; Watch agent state and sync to webview
(defn start-state-sync! []
  (add-watch state/!agent-state ::monitor-sync
    (fn [_ _ old-state new-state]
      (when (not= (:conversations old-state)
                  (:conversations new-state))
        (send-state-to-webview!+)))))
```

## Benefits

### Performance
- **Incremental updates**: Replicant only updates changed DOM elements
- **No full HTML regeneration**: Small state changes = small DOM updates
- **Efficient key-based reconciliation**: Using `:replicant/key` on conversation IDs

### Maintainability
- **Separation of concerns**: UI rendering, state management, event handling all separated
- **Pure functions**: UI transforms are testable without DOM
- **Clear data flow**: State → Transform → Render

### Extensibility
- **Local UI state**: Can add filters, sorting, search without host involvement
- **Interactive features**: Expandable conversations, inline editing, etc.
- **Animation support**: Replicant's mounting/unmounting transitions

## Testing Strategy

### Unit Tests (REPL)
```clojure
;; Test UI transforms
(def sample-conv
  {:agent.conversation/id 1
   :agent.conversation/title "Test"
   :agent.conversation/status :working
   :agent.conversation/current-turn 3
   :agent.conversation/max-turns 10})

;; Should produce valid Hiccup
(monitor.ui/render-conversation sample-conv)
```

### Integration Tests
1. Create flare in REPL
2. Send state updates via `flare/post-message!+`
3. Verify DOM updates in browser DevTools
4. Test user interactions (click cancel, delete, etc.)
5. Verify messages received in host

### Manual Testing Scenarios
- [ ] Create conversation → verify appears in UI
- [ ] Update status → verify icon changes
- [ ] Increment turn → verify counter updates
- [ ] Click cancel → verify message sent to host
- [ ] Click delete → verify conversation removed
- [ ] Show results → verify document opens
- [ ] Show logs → verify output channel opens

## Migration Path

### Step 1: Create resources (Phase 1)
- Add `resources/scittle/monitor/ui.cljs`
- Add `resources/scittle/monitor/core.cljs`
- Test in isolation with mock data

### Step 2: Add new functions alongside old (Phase 2)
- Add `create-monitor-flare!+` (Replicant version)
- Add `send-state-to-webview!+`
- Keep old `update-agent-monitor-flare!+` temporarily

### Step 3: Switch over
- Update `start-monitoring-conversation!+` to use new system
- Update `log-and-update!+` to use message passing
- Add state watcher (Phase 3)

### Step 4: Clean up
- Remove old `agent-monitor-html` function
- Remove old `conversation-html` function
- Remove old rendering code

## Open Questions

1. **Error handling**: What happens if webview crashes? Do we need fallback?
2. **State persistence**: Should webview restore from `vscode.getState()`?
3. **Scroll position**: How to maintain scroll during updates?
4. **Performance**: What's the conversation count limit before we need virtualization?

## Success Criteria

- [ ] Monitor renders without full HTML regeneration
- [ ] State updates are < 50ms (vs current ~200ms)
- [ ] All user interactions work (cancel, delete, show results, show logs)
- [ ] No regressions in functionality
- [ ] Code is more maintainable (clearer separation of concerns)
- [ ] All existing tests pass
- [ ] New unit tests for UI transforms

## References

- [Replicant TicTacToe](https://github.com/cjohansen/replicant-tic-tac-toe)
- [Joyride Flares Examples](https://github.com/BetterThanTomorrow/joyride/blob/master/examples/.joyride/src/flares_examples.cljs)
- [Replicant Documentation](https://replicant.fun)
- [Scittle Documentation](https://github.com/babashka/scittle)
