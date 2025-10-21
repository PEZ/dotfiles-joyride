# lm_dispatch System Documentation

## Quick Navigation

**Quick Start:** Read [Overview](#overview), [Architecture](#architecture), and [Usage Examples](#usage-examples)
**Deep Dive:** See [Detailed Architecture](#detailed-architecture) and [Advanced Topics](#advanced-topics)
**Reference:** Check [Design Principles](#design-principles) and [Testing](#testing)

> **Note:** This document contains both a concise overview (sections 1-5) and detailed deep-dive sections (starting at "Detailed Architecture"). Choose based on your needs.

## Overview

`lm_dispatch` is an autonomous AI agent conversation system built in ClojureScript for VS Code using Joyride. It enables AI agents to work autonomously toward goals by executing multi-turn conversations with tool usage, monitoring, and cancellation support.

## Architecture

The system follows a clean separation of concerns with six main namespaces:

### 1. lm-dispatch.agent-core - Pure Conversation Engine

**Purpose**: Implements the core autonomous conversation engine without dependencies on instruction selection

**Key Concepts**:
- **Agentic Behavior**: AI agents drive conversations autonomously toward goals
- **Goal Separation**: Goals are kept separate from history and injected on each turn
- **Turn-based Flow**: Conversations proceed in turns with tool execution between turns
- **Completion Detection**: Agents signal completion via markers or natural language
- **Pure Implementation**: No instruction selection dependencies

**Main Function**: `agentic-conversation!+`

Internal function used by orchestrator for the conversation loop with options for model selection, tool configuration, turn limits, and progress tracking.

### 2. lm-dispatch.agent-orchestrator - Orchestration Layer

**Purpose**: Main entry point that optionally adds instruction file selection before delegating to core

**Main Function**: `autonomous-conversation!+`

The primary public API for creating autonomous AI conversations with optional instruction file selection.

**Options:**
- `:model-id` - LM model ID (default: "gpt-4o-mini")
- `:max-turns` - Maximum conversation turns (default: 10)
- `:tool-ids` - Vector of tool IDs to enable (default: [])
- `:progress-callback` - Function called with progress updates
- `:allow-unsafe-tools?` - Allow file write operations (default: false)
- `:caller` - String identifying who started the conversation
- `:title` - Display title for the conversation
- `:use-instruction-selection?` - Enable automatic instruction file selection (default: false)
- `:context-file-paths` - Vector of additional file paths to include as context

**Agentic System Prompt**:
The system prompt defines agent behavior to break goals into steps, use tools proactively, adapt when tools fail, never stop to ask questions, and signal completion with markers.

**Key Functions**:
- `autonomous-conversation!+` (orchestrator) - Main public API entry point
- `agentic-conversation!+` (core) - Internal conversation engine
- `build-agentic-messages` (core) - Message construction (args: history, instructions, goal)
- `continue-conversation-loop` (core) - Main recursive loop
- `execute-conversation-turn` (core) - Single turn execution
- `execute-tools-if-present!+` (core) - Tool execution
- `determine-conversation-outcome` (core) - Flow control
- `agent-indicates-completion?` (core) - Completion detection

### 3. lm-dispatch.state - Pure State Management

**Purpose**: Single source of truth for conversation state

The state atom stores all conversations, next ID counter, output channel, and sidebar slot.

**State Structure:**
```clojure
{:conversations {}           ; Map of conversation-id -> conversation-data
 :next-id 1                  ; Auto-incrementing ID counter
 :output-channel nil         ; VS Code output channel
 :sidebar-slot nil}          ; Sidebar flare slot identifier
```

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

### 4. lm-dispatch.util - VS Code LM API Utilities

**Purpose**: Low-level interaction with VS Code Language Model APIs

Provides functions for model management, tool management, message handling, request handling, tool execution, and token counting.

**Tool Safety**: By default, filters out dangerous write operations (can be overridden with `:allow-unsafe-tools? true`)

**Tool Execution:**
- 30-second timeout per tool
- Errors returned as part of results (not thrown)
- Agent can adapt based on error messages

### 5. lm-dispatch.monitor - UI & Monitoring

**Purpose**: Render conversations in sidebar and handle user interactions

Uses Joyride Flare to render live HTML in VS Code sidebar with conversation lists, status icons, real-time progress updates, cancel buttons, token usage tracking, and error display.

### 6. lm-dispatch.logging - Logging Infrastructure

**Purpose**: Manage output channel and debug logs

Creates Agent Dispatch output channel in VS Code for logging with conversation ID prefixes and debug mode support.

**Debug Mode Features:**
- `enable-debug-mode!` / `disable-debug-mode!` - Toggle debug logging
- `add-debug-log!` - Log entries to memory (when debug enabled)
- `get-debug-logs` / `get-all-debug-logs` - Retrieve logs for inspection
- `clear-debug-logs!` - Clear logs while keeping debug mode on

Debug mode stores logs in-memory for testing and detailed inspection without cluttering the output channel.

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

#### 1. Agent Core Engine (`lm-dispatch.agent-core`)

The core engine implements pure autonomous conversation loops without instruction selection dependencies:

**Conversation Flow:**
1. Build messages (goal + history + instructions)
2. Send request to language model
3. Collect streaming response with tools
4. Execute any tool calls
5. Add results to history
6. Check completion criteria
7. Either continue to next turn or finish

#### 2. Agent Orchestrator (`lm-dispatch.agent-orchestrator`)

The orchestration layer adds instruction selection and monitoring on top of the core engine:

**Orchestration Flow:**
1. Register conversation with monitoring
2. Optionally select relevant instruction files (if `:use-instruction-selection?` is true)
3. Prepare final instructions (selected + context files)
4. Delegate to core engine (`agentic-conversation!+`)
5. Log final summary

**Key Implementation Details:**

- **Goal Injection**: The goal is never stored in history. Instead, it's injected as the first message on every turn, ensuring the agent always remembers its objective.

- **Tool Call Parsing**: Uses native VS Code tool calls returned in the response stream.

- **Cancellation**: Checks for cancellation at multiple points:
  - During response streaming (every 200ms)
  - Between turns
  - During tool execution

- **Token Counting**: Tracks tokens per turn and cumulative total for cost awareness.

#### 3. State Management (`lm-dispatch.state`)

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

#### 4. Monitor & UI (`lm-dispatch.monitor`)

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

#### 5. Utilities (`lm-dispatch.util`)

Low-level VS Code API wrappers.

**Tool Safety:**
By default, filters out dangerous write operations:
- `copilot_createFile`
- `copilot_insertEdit`
- `copilot_createDirectory`
- `copilot_editNotebook`
- `copilot_runInTerminal`
- `copilot_installExtension`
- `copilot_runVscodeCommand`
- `copilot_createNewWorkspace`
- `copilot_createAndRunTask`
- `copilot_createNewJupyterNotebook`

Can be overridden with `:allow-unsafe-tools? true`

**Cancellation Support:**
All async operations accept cancellation tokens and check them periodically.

**Tool Execution:**
- 30-second timeout per tool
- Errors returned as part of results (not thrown)
- Agent can adapt based on error messages

#### 6. Logging (`lm-dispatch.logging`)

**Output Channel:**
Creates "Agent Dispatch" output channel with timestamped, conversation-prefixed logs.

**Debug Mode:**
Optional in-memory log collection for inspection and testing.

---

## Usage Examples

### Example 1: Simple File Counter

```clojure
(require '[lm-dispatch.agent-orchestrator :as orchestrator])
(require '[promesa.core :as p])

(p/let [result (orchestrator/autonomous-conversation!+
                 "Count all .cljs files in this workspace and show the total"
                 {:tool-ids ["copilot_findFiles"
                             "joyride_evaluate_code"]
                  :max-turns 5
                  :caller "file-counter"})]
  (println "Result:" result))
```

### Example 2: Code Analysis

```clojure
(orchestrator/autonomous-conversation!+
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
(orchestrator/autonomous-conversation!+
  "Generate the first 10 fibonacci numbers using REPL evaluation"
  {:model-id "gpt-4o-mini"
   :tool-ids ["joyride_evaluate_code"]
   :max-turns 15
   :caller "fibonacci-gen"})
```

### Example 4: With Instruction Selection

```clojure
(orchestrator/autonomous-conversation!+
  "Create a new Clojure namespace with TDD approach"
  {:use-instruction-selection? true  ; Auto-select relevant instruction files
   :tool-ids ["copilot_createFile" "copilot_readFile" "joyride_evaluate_code"]
   :allow-unsafe-tools? true
   :max-turns 15})
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

### Tool Call Format

The system uses the native VS Code Language Model API format for tool calls:

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

Tool calls are returned directly by the language model in the response stream and executed immediately.

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
