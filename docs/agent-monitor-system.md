# Agent Monitor System

The Agent Monitor System provides visual tracking and logging for autonomous AI agent conversations.

## Architecture

### Components

1. **State Management** (`!agent-state` atom)
   - Centralized conversation tracking
   - Auto-incrementing conversation IDs
   - Output channel singleton

2. **Output Channel** ("Sub Agents")
   - ID-prefixed logging: `[conv-id] message`
   - Persistent across conversation lifecycle
   - Accessible via "Show Logs" button in flare

3. **Flare UI** (Sidebar slot 1)
   - Real-time conversation list
   - Shows: ID, status, goal, model, turn progress, caller
   - Newest conversations at top
   - Scrollable goal text (max-height: 60px)

4. **Conversation Lifecycle**
   - **Statuses**: `:started` ⏸️ → `:working` ⚙️ → `:done` ✅ or `:error` ❌
   - Metadata: timestamps, turn counts, error messages

## API

### Starting a Conversation

```clojure
(require '[ai-workflow.agent-monitor :as monitor])
(require '[promesa.core :as p])

(p/let [conv-id (monitor/start-monitoring-conversation!+
                 {:agent.conversation/goal "Task description"
                  :agent.conversation/model-id "gpt-4o-mini"
                  :agent.conversation/max-turns 10
                  :agent.conversation/caller "script-name"})]
  ;; Use conv-id for logging
  conv-id)
```

### Logging with Updates

```clojure
;; Log only
(monitor/log-and-update!+ conv-id "Processing step 1..." nil)

;; Log and update status
(p/let [_ (monitor/log-and-update!+
           conv-id
           "🔧 Executing tools"
           {:agent.conversation/status :working
            :agent.conversation/current-turn 3})]
  :updated)

;; Complete with error
(p/let [_ (monitor/log-and-update!+
           conv-id
           "❌ Task failed"
           {:agent.conversation/status :error
            :agent.conversation/current-turn 5
            :agent.conversation/error-message "API timeout"})]
  :error-logged)
```

### Direct State Access

```clojure
;; Get specific conversation
(monitor/get-conversation conv-id)

;; Get all conversations
(monitor/get-all-conversations)

;; Manually update flare
(monitor/update-agent-monitor-flare!+)
```

## Integration with Autonomous Agents

The monitor system is designed to integrate with `autonomous-conversation!+`:

1. Register conversation before starting agent
2. Replace stdout `println` calls with `log-to-agent-channel!`
3. Update conversation status at key points:
   - Start: `:started`
   - First turn: `:working`
   - Completion: `:done` or `:error`
4. Update flare after status changes

### Integration Points

```clojure
;; In autonomous-conversation!+
(p/let [conv-id (monitor/start-monitoring-conversation!+
                 {:agent.conversation/goal goal
                  :agent.conversation/model-id model-id
                  :agent.conversation/max-turns max-turns
                  :agent.conversation/caller (or caller "autonomous-agent")})]
  ;; Pass conv-id through conversation loop
  ;; Use for logging instead of println
  )

;; Replace println calls with
(monitor/log-to-agent-channel! conv-id "🤖 AI Agent says: ...")

;; Update status at turn start
(monitor/update-conversation! conv-id
                              {:agent.conversation/current-turn turn-count
                               :agent.conversation/status :working})
(monitor/update-agent-monitor-flare!+)
```

## UI Behavior

### Status Icons
- ⏸️ **Started**: Conversation registered, not yet working
- ⚙️ **Working**: Active processing
- ✅ **Done**: Successfully completed
- ❌ **Error**: Failed with error

### Flare Features
- **Auto-refresh**: Updates on state changes
- **Sorted display**: Newest conversations first
- **Compact metadata**: Caller, model, turn progress in one line
- **Scrollable goals**: Long goals don't break layout
- **Error display**: Red error messages for failed tasks
- **Show Logs button**: Opens output channel with all conversation logs

## Testing

Tests in `src/test/ai_workflow/agent_monitor_test.cljs` cover:
- Conversation registration with unique IDs
- Status updates and field preservation
- Default values (caller → "Unknown")
- Status icon mapping
- Time formatting
- Conversation retrieval

Run tests:
```clojure
(require '[cljs.test :refer [run-tests]])
(run-tests 'ai-workflow.agent-monitor-test)
```

## Future Enhancements

Potential improvements:
- Clear completed conversations button
- Filter by status/caller
- Conversation history export
- Click conversation to see detailed view
- Pause/cancel running conversations
