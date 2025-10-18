# lm_dispatch System Documentation

## Overview

`lm_dispatch` is an autonomous AI agent conversation system built in ClojureScript for VS Code using Joyride. It enables AI agents to work autonomously toward goals by executing multi-turn conversations with tool usage, monitoring, and cancellation support.

## Architecture

The system follows a clean separation of concerns with five main namespaces:

### 1. lm-dispatch.agent - Core Agent Logic

**Purpose**: Implements the autonomous conversation engine

**Key Concepts**:
- **Agentic Behavior**: AI agents drive conversations autonomously toward goals
- **Goal Separation**: Goals are kept separate from history and injected on each turn
- **Turn-based Flow**: Conversations proceed in turns with tool execution between turns
- **Completion Detection**: Agents signal completion via markers or natural language

**Main Function**: `autonomous-conversation!+`

The primary entry point for creating autonomous AI conversations with options for model selection, tool configuration, turn limits, and progress tracking.

**Agentic System Prompt**:
The system prompt defines agent behavior to break goals into steps, use tools proactively, adapt when tools fail, never stop to ask questions, and signal completion with markers.

**Key Functions**:
- `autonomous-conversation!+` - Main entry point
- `build-agentic-messages` - Message construction
- `continue-conversation-loop` - Main recursive loop
- `execute-conversation-turn` - Single turn execution
- `execute-tools-if-present!+` - Tool execution
- `determine-conversation-outcome` - Flow control
- `agent-indicates-completion?` - Completion detection

### 2. lm-dispatch.state - Pure State Management

**Purpose**: Single source of truth for conversation state

The state atom stores all conversations, next ID counter, output channel, and sidebar slot.

**Status Values**:
- :started - Conversation registered
- :working - Agent actively processing
- :cancel-requested - User requested cancellation
- :task-complete - Agent completed goal
- :max-turns-reached - Hit turn limit
- :agent-finished - Agent finished naturally
- :cancelled - Successfully cancelled
- :error - Error occurred
- :done - Completed (final)

**Key Functions**:
- `register-conversation!` - Creates new conversation with generated ID
- `update-conversation!` - Merges updates into existing conversation
- `mark-conversation-cancelled!` - Cancels conversation and triggers token
- `get-conversation` - Query by ID
- `get-all-conversations` - Query all

### 3. lm-dispatch.monitor - UI & Monitoring

**Purpose**: Render conversations in sidebar and handle user interactions

Uses Joyride Flare to render live HTML in VS Code sidebar with conversation lists, status icons, real-time progress updates, cancel buttons, token usage tracking, and error display.

### 4. lm-dispatch.util - VS Code LM API Utilities

**Purpose**: Low-level interaction with VS Code Language Model APIs

Provides functions for model management, tool management, message handling, request handling, tool execution, and token counting.

### 5. lm-dispatch.logging - Logging Infrastructure

**Purpose**: Manage output channel and debug logs

Creates Agent Dispatch output channel in VS Code for logging with conversation ID prefixes and debug mode support.

## Design Principles

1. **Goal Separation** - Goals never stored in history, injected on each turn
2. **Pure State Management** - All state in single atom, pure functions for updates
3. **Promesa Promises** - All async operations use Promesa
4. **Cancellation Support** - Every async operation supports cancellation
5. **Tool Safety** - Default filter removes write operations
6. **Monitoring by Default** - Every conversation automatically monitored in UI
7. **Token Awareness** - Track token usage per turn and total
8. **Interactive Development** - Designed for REPL-driven development

## Testing

Tests located in src/test/lm_dispatch/ with agent_test.cljs, state_test.cljs, util_test.cljs, and monitor_test.cljs.

Run all tests: (require 'run-all-tests :reload) (run-all-tests/run!+)

## Conclusion

lm_dispatch provides a robust, testable, and user-friendly system for autonomous AI agent conversations in VS Code with clean architecture, comprehensive monitoring, and safety features.


## Detailed Architecture

### System Components

#### 1. Agent Engine (`lm-dispatch.agent`)

The agent engine is the heart of the system, implementing autonomous conversation loops:

**Conversation Flow:**
1. Register conversation with monitoring
2. Initialize turn counter and history
3. Build messages (goal + history)
4. Send request to language model
5. Collect streaming response with tools
6. Execute any tool calls
7. Add results to history
8. Check completion criteria
9. Either continue to next turn or finish

**Key Implementation Details:**

- **Goal Injection**: The goal is never stored in history. Instead, it's injected as the first message on every turn, ensuring the agent always remembers its objective.

- **Tool Call Parsing**: Supports both native VS Code tool calls and EDN format fallback for models without native support.

- **Cancellation**: Checks for cancellation at multiple points:
  - During response streaming (every 200ms)
  - Between turns
  - During tool execution

- **Token Counting**: Tracks tokens per turn and cumulative total for cost awareness.

#### 2. State Management (`lm-dispatch.state`)

Pure state management with a single atom containing all conversation data.

**Conversation Lifecycle:**
1. `register-conversation!` - Creates new entry with ID
2. `update-conversation!` - Updates status, turn count, tokens
3. `mark-conversation-cancelled!` - Triggers cancellation
4. Final status set (`:task-complete`, `:cancelled`, etc.)

**State Queries:**
- Get individual conversations by ID
- Get all conversations
- Access output channel and sidebar slot

#### 3. Monitor & UI (`lm-dispatch.monitor`)

Real-time HTML UI rendered in VS Code sidebar using Joyride Flare.

**UI Features:**
- Live status updates
- Turn progress (3/10)
- Token usage display
- Cancel buttons for active conversations
- Error messages
- Scrollable goal text
- Timestamp display

**Interaction Flow:**
1. User clicks cancel button
2. Message posted from webview
3. `cancel-conversation!` handler called
4. Token source cancelled
5. State updated
6. UI re-rendered

#### 4. Utilities (`lm-dispatch.util`)

Low-level VS Code API wrappers.

**Tool Safety:**
By default, filters out dangerous write operations:
- `copilot_createFile`
- `copilot_insertEdit`
- `copilot_runInTerminal`
- `copilot_runVscodeCommand`
- etc.

Can be overridden with `:allow-unsafe-tools? true`

**Cancellation Support:**
All async operations accept cancellation tokens and check them periodically.

**Tool Execution:**
- 30-second timeout per tool
- Errors returned as part of results (not thrown)
- Agent can adapt based on error messages

#### 5. Logging (`lm-dispatch.logging`)

**Output Channel:**
Creates "Agent Dispatch" output channel with timestamped, conversation-prefixed logs.

**Debug Mode:**
Optional in-memory log collection for inspection and testing.

---

## Usage Examples

### Example 1: Simple File Counter

```clojure
(require '[lm-dispatch.agent :as agent])
(require '[promesa.core :as p])

(p/let [result (agent/autonomous-conversation!+ 
                 "Count all .cljs files in this workspace and show the total"
                 {:tool-ids ["copilot_findFiles" 
                             "joyride_evaluate_code"]
                  :max-turns 5
                  :caller "file-counter"})]
  (println "Result:" result))
```

### Example 2: Code Analysis

```clojure
(agent/autonomous-conversation!+ 
  "Analyze the lm_dispatch system and create a summary of its key components"
  {:model-id "claude-sonnet-4"
   :tool-ids ["copilot_readFile" 
              "copilot_findFiles"
              "copilot_searchCodebase"]
   :max-turns 20
   :progress-callback (fn [step] 
                        (println "Progress:" step))
   :caller "code-analyzer"
   :title "Architecture Analysis"})
```

### Example 3: REPL Fibonacci Generator

```clojure
(agent/autonomous-conversation!+ 
  "Generate the first 10 fibonacci numbers using REPL evaluation"
  {:model-id "gpt-4o-mini"
   :tool-ids ["joyride_evaluate_code"]
   :max-turns 15
   :caller "fibonacci-gen"})
```

### Example 4: Interactive Tool Selection

```clojure
(require '[lm-dispatch.ui :as ui])

;; Show tool picker with pre-selected tools
(p/let [selected-tools (ui/tools-picker+ 
                         #{"joyride_evaluate_code" 
                           "copilot_readFile"
                           "copilot_findFiles"})]
  (agent/autonomous-conversation!+ 
    "Your custom goal here"
    {:tool-ids selected-tools
     :max-turns 15
     :title "Custom Task"}))
```

### Example 5: Monitor Management

```clojure
(require '[lm-dispatch.monitor :as monitor])
(require '[lm-dispatch.state :as state])

;; Reveal the monitor sidebar
(monitor/reveal-dispatch-monitor!+)

;; Start a monitored conversation manually
(p/let [conv-id (monitor/start-monitoring-conversation!+
                  {:agent.conversation/goal "Find all TODO comments"
                   :agent.conversation/model-id "gpt-4o"
                   :agent.conversation/max-turns 10
                   :agent.conversation/caller "todo-finder"
                   :agent.conversation/title "TODO Search"})]
  (println "Monitoring conversation" conv-id))

;; Query all conversations
(state/get-all-conversations)

;; Get specific conversation details
(state/get-conversation 1)
```

---

## Advanced Topics

### Cancellation Implementation

The cancellation system uses VS Code's `CancellationTokenSource`:

1. Token created when conversation starts
2. Stored in conversation state
3. Passed to all async operations
4. Polled every 200ms during streaming
5. Checked between turns
6. When triggered, operations reject with "Cancelled" error

**Code Example:**
```clojure
;; In continue-conversation-loop
(if (:agent.conversation/cancelled? (state/get-conversation conv-id))
  (do
    (logging/log-to-channel! conv-id "🛑 Conversation cancelled by user")
    (format-completion-result final-history :cancelled turn-result))
  (continue-to-next-turn))
```

### Token Counting Strategy

Tokens counted at the start of each turn:

```clojure
(p/let [messages (build-agentic-messages history instructions goal)
        turn-tokens (util/count-message-tokens!+ model-id messages)
        current-total (:agent.conversation/total-tokens conv)
        new-total (+ current-total turn-tokens)]
  (state/update-conversation! conv-id 
    {:agent.conversation/total-tokens new-total})
  ...)
```

This provides:
- Per-turn cost visibility
- Cumulative total tracking
- Early warning if approaching limits

### Tool Call Formats

**Native VS Code Format:**
```javascript
// LM returns tool calls in response stream
{
  callId: "call-123",
  name: "copilot_readFile",
  input: {
    filePath: "/path/to/file",
    startLine: 1,
    endLine: 10
  }
}
```

**EDN Fallback Format:**
```clojure
"BEGIN-TOOL-CALL
{:name "copilot_readFile" 
 :input {:filePath "/path/to/file" 
         :startLine 1 
         :endLine 10}}
END-TOOL-CALL"

;; Parsed by extract-tool-call-edn and parse-tool-calls
;; Automatically augmented with unique :callId
```

### Error Recovery

The agent can adapt to errors:

```clojure
;; Tool result with error
{:call-id "call-123"
 :tool-name "copilot_readFile"
 :error "File not found: /invalid/path"}

;; Agent receives this and can:
;; 1. Try a different path
;; 2. Use a different tool
;; 3. Report the issue in natural language
```

---

## Testing Strategy

### Unit Tests

Located in `src/test/lm_dispatch/`:

**`agent_test.cljs`:**
- Message building logic
- Completion detection
- History management
- Outcome determination

**`state_test.cljs`:**
- Conversation registration
- State updates
- Cancellation
- Query functions

**`util_test.cljs`:**
- Message creation
- Tool filtering
- Message chain building

**`monitor_test.cljs`:**
- UI rendering
- Event handling

### Running Tests

```clojure
;; Run all tests
(require 'run-all-tests :reload)
(run-all-tests/run!+)

;; Run specific namespace tests
(require 'cljs.test)
(cljs.test/run-tests 'test.lm-dispatch.agent-test)
```

### REPL-Driven Testing

Each namespace includes extensive comment blocks with test expressions:

```clojure
(comment
  ;; Test conversation
  (p/let [result (autonomous-conversation!+ 
                   "Count to 3"
                   {:tool-ids ["joyride_evaluate_code"]
                    :max-turns 3})]
    (def test-result result))
  
  ;; Inspect result
  test-result
  
  :rcf) ;; Rich Comment Form marker
```

---

## Design Principles Explained

### 1. Goal Separation

**Problem:** If goals are in history, they can get lost in long conversations.

**Solution:** Keep goal separate, inject it as the first message every turn.

**Benefit:** Agent always has context, never forgets objective.

### 2. Pure State Management

**Problem:** Side effects scattered across codebase make testing hard.

**Solution:** All state in one atom, pure functions for updates.

**Benefit:** Easy to test, easy to inspect, predictable behavior.

### 3. Promesa Promises

**Problem:** Callback hell and complex async coordination.

**Solution:** Use Promesa's `p/let` for readable async code.

**Benefit:** Composable, chainable, cancellable.

### 4. Cancellation Support

**Problem:** Long-running operations can't be stopped.

**Solution:** Thread cancellation tokens through all async calls.

**Benefit:** Responsive UI, user control, resource cleanup.

### 5. Tool Safety

**Problem:** Agent could accidentally modify or delete files.

**Solution:** Filter out write tools by default, explicit opt-in required.

**Benefit:** Safe experimentation, reduced risk.

### 6. Monitoring by Default

**Problem:** Hard to track what agents are doing.

**Solution:** Auto-register all conversations with UI monitoring.

**Benefit:** Visibility, debugging, progress tracking.

### 7. Token Awareness

**Problem:** Users don't know how much conversations cost.

**Solution:** Count and display tokens per turn and total.

**Benefit:** Cost transparency, usage optimization.

### 8. Interactive Development

**Problem:** Long feedback cycles slow development.

**Solution:** REPL-first design with extensive comment blocks.

**Benefit:** Fast iteration, immediate feedback.

---

## Conclusion

The `lm_dispatch` system demonstrates how to build robust, autonomous AI agents in VS Code using ClojureScript and Joyride. Its clean architecture makes it easy to understand, test, and extend.

**Key Achievements:**
- ✅ Autonomous goal-directed conversations
- ✅ Real-time UI monitoring
- ✅ Robust cancellation support
- ✅ Token usage tracking
- ✅ Tool safety by default
- ✅ Comprehensive error handling
- ✅ Interactive REPL development
- ✅ Clean separation of concerns

**Future Enhancements:**
- Conversation history persistence
- Multi-agent coordination
- Streaming UI updates
- Custom tool registration
- Conversation templates
- Cost budgets and limits

For questions, issues, or contributions, explore the source code with its extensive inline documentation and comment blocks showing real-world usage patterns.
