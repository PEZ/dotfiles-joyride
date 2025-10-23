# Agent System

This directory contains autonomous agents built on the LM agent dispatch system. Each agent is designed for a specific workflow and uses the `agent-orchestrator` for execution.

## Available Agents

### 1. Memory Keeper Agent (`memory-keeper`)

Records domain-specific learnings into persistent memory instruction files. The agent analyzes lessons, determines appropriate domains, and either creates new memory files or appends to existing ones.

#### Usage

```clojure
(require '[agents.memory-keeper :as mk])
(require '[promesa.core :as p])

;; Basic usage - global memory with domain hint
(p/let [result (mk/record-memory!+
                {:summary "Use REPL evaluation of subexpressions instead of println for debugging"
                 :domain "clojure"})]
  result)
;; => {:success true :file-uri "file:///path/to/clojure-memory.instructions.md"}

;; Without domain hint - agent determines domain automatically
(p/let [result (mk/record-memory!+
                {:summary "Always verify API responses before assuming success"})]
  result)

;; Workspace-scoped memory
(p/let [result (mk/record-memory!+
                {:summary "Threading macros improve readability in data pipelines"
                 :domain "clojure"
                 :scope :workspace})]
  result)

;; With instruction selector
(p/let [result (mk/record-memory!+
                {:summary "Prefer structural editing tools over string replacement"
                 :domain "clojure"
                 :instructions :instructions-selector
                 :caller "my-script"})]
  result)
```

#### Configuration Options

- `:summary` - String describing the lesson learned (required)
- `:domain` - Optional string for domain hint (e.g., 'clojure', 'git-workflow')
- `:scope` - Keyword or string: `:global`/`:workspace` or `"global"`/`"workspace"`/`"ws"` (default: `:global`)
- `:model-id` - Optional model override (default: 'grok-code-fast-1')
- `:max-turns` - Optional turn limit override (default: 15)
- `:caller` - Optional, but encouraged, identifier of who's recording the memory
- `:progress-callback` - Optional progress function
- `:instructions` - Instructions as string, vector of paths, or `:instructions-selector`
- `:context-file-paths` - Vector of additional instruction file paths to include as context
- `:editor-context/file-path` - Optional: Current editor file path
- `:editor-context/selection-start-line` - Optional: Selection start line (0-based)
- `:editor-context/selection-end-line` - Optional: Selection end line (0-based)

#### Return Value

Success:
```clojure
{:success true
 :file-uri "file:///path/to/domain-memory.instructions.md"}
```

Already exists:
```clojure
{:success true
 :memory-already-existed? true
 :message "Memory already covered..."
 :domain "clojure"
 :file-uri "file:///path/to/clojure-memory.instructions.md"}
```

Failure:
```clojure
{:success false
 :error "Error message"
 :error-type :write-failed  ; or :file-not-found, :parse-failed
 :file-uri "file:///path/to/file.md"}
```

### 2. Interactive Clojure Agent (`clojure-interactive`)

Guides REPL-first TDD workflows. The agent develops solutions in the REPL, verifies them thoroughly, then produces a structured report for Copilot to present to the user and eventually commit to files.

#### Usage

```clojure
(require '[agents.clojure-interactive :as ci])
(require '[promesa.core :as p])

;; Basic usage
(p/let [result (ci/interactively-do!+
                {:task "Create a function to calculate fibonacci numbers with memoization"
                 :title "Building Fibonacci"})]
  ;; Access the report
  (println (:report-raw result))
  result)

;; With custom configuration
(p/let [result (ci/interactively-do!+
                {:task "Refactor the data processing pipeline to use transducers"
                 :model-id "claude-opus-4"
                 :max-turns 40
                 :instructions :instructions-selector
                 :title "Refactoring Pipeline"})]
  result)

;; With custom goal prompt
(p/let [custom-goal "Implement a data validator with comprehensive test coverage..."
        result (ci/interactively-do!+
                {:task "N/A"  ; task is part of custom goal
                 :goal custom-goal
                 :title "Data Validator"})]
  result)
```

#### Configuration Options

- `:task` - String describing the programming task (required unless `:goal` provided)
- `:model-id` - Optional model override (default: 'claude-haiku-4.5')
- `:max-turns` - Optional turn limit override (default: 30)
- `:tool-ids` - Optional tool suite override (default: full suite of 20 tools)
- `:instructions` - Optional instructions override (default: `:instructions-selector`)
- `:title` - Optional progress title (default: 'Interactive Programming')
- `:progress-callback` - Optional progress function
- `:goal` - Optional complete goal prompt override (defaults to built prompt)
- Additional keys passed through to agent-orchestrator

#### Default Tool Suite

The agent has access to:
- **Clojure REPL tools**: `clojure_evaluate_code`, `clojure_symbol_info`, `clojuredocs_info`, `clojure_repl_output_log`
- **Backseat Driver tools**: `replace_top_level_form`, `insert_top_level_form`, `clojure_create_file`, `clojure_append_code`, `clojure_balance_brackets`
- **Joyride tools**: `joyride_evaluate_code`
- **Copilot tools**: `copilot_readFile`, `copilot_searchCodebase`, `copilot_findFiles`, `copilot_findTextInFiles`, `copilot_listDirectory`, `copilot_getErrors`, `copilot_readProjectStructure`, `copilot_getChangedFiles`, `copilot_getDocInfo`, `copilot_githubRepo`

Note: `human_intelligence` is deliberately excluded - the agent produces a report for human review instead of interrupting the workflow.

#### REPL-First Workflow

The agent follows this workflow:

1. **Develop in REPL** - Build solution incrementally, testing each piece
2. **Use TDD** - Define tests with `clojure.test` in the REPL
3. **Test subexpressions** - Evaluate building blocks rather than using `println`
4. **Verify thoroughly** - Test with multiple cases and edge conditions
5. **Stay in REPL** - Complete verification before considering file modifications
6. **Produce report** - Generate structured report for Copilot and user

#### Structured Report Format

The agent produces a five-section report wrapped in markers:

```
---BEGIN INTERACTIVE REPORT---

## 1. Achievement Summary
- Clear description of what has been implemented
- Key design decisions and architectural choices
- Any deviations from requirements with rationale

## 2. REPL State Reconstruction
Complete sequence of REPL evaluations to recreate working state:
- All namespace requires
- All function definitions
- All test definitions
- All data setup
- Ordered top-to-bottom for easy restoration

## 3. Verification Instructions for Copilot
- Specific REPL expressions to verify functionality
- Expected results for each verification step
- Edge cases or boundary conditions to check

## 4. User Verification Handoff
- Instructions for Copilot to present achievements
- Specific prompts for user to test implementation
- **EXPLICIT**: "STOP and wait for user feedback"
- Clear: "Do not proceed with file commits until user confirms"

## 5. Structural Editing Guide for Copilot
Key principles reminder:
- Plan all edits before executing
- Work bottom-to-top within files
- Use replace_top_level_form, insert_top_level_form, clojure_create_file
- Read tool documentation if uncertain
- Reference complete REPL state (Section 2)
- Exact details depend on user feedback

---END INTERACTIVE REPORT---
```

#### Return Value

```clojure
{;; Full agent result
 :history [...]          ; Complete conversation history
 :model-id "..."         ; Model used
 :turn-count 15          ; Number of turns taken

 ;; Report extraction
 :report-raw "..."       ; Raw report string (if extraction succeeds)
 :report {:raw "..."}    ; Parsed report map (if extraction succeeds)
 :report-extraction-failed true  ; Present if report extraction failed
}
```

#### User Verification Handoff Workflow

After the agent completes:

1. **Copilot receives report** - Gets structured five-section report
2. **Copilot presents to user** - Shows achievement summary and instructions
3. **User tests implementation** - Tries suggested verification steps
4. **User provides feedback** - Confirms working or requests changes
5. **Only then** - Copilot proceeds with structural editing to commit files

This ensures:
- User validates solution before file modifications
- Changes are intentional and understood
- No surprise file edits without verification

#### Example Report

```
---BEGIN INTERACTIVE REPORT---

## 1. Achievement Summary

Implemented fibonacci function with memoization using Clojure's built-in `memoize`.
The solution handles edge cases (n=0, n=1) and uses simple recursive approach that's
easy to understand and maintain.

Key decisions:
- Used `memoize` over manual memoization for simplicity
- Added comprehensive tests covering base cases and larger values
- Included performance comparison with unmemoized version

## 2. REPL State Reconstruction

```clojure
(ns math.fibonacci)

(defn fib-slow [n]
  (cond
    (<= n 0) 0
    (= n 1) 1
    :else (+ (fib-slow (- n 1)) (fib-slow (- n 2)))))

(def fib (memoize fib-slow))

;; Tests
(require '[clojure.test :refer [deftest is testing]])

(deftest fib-test
  (testing "Base cases"
    (is (= 0 (fib 0)))
    (is (= 1 (fib 1))))
  (testing "Small values"
    (is (= 1 (fib 2)))
    (is (= 2 (fib 3)))
    (is (= 5 (fib 5)))
    (is (= 8 (fib 8))))
  (testing "Larger values"
    (is (= 6765 (fib 20)))))
```

## 3. Verification Instructions for Copilot

Evaluate these to verify:

```clojure
;; Basic functionality
(fib 10)  ; => 55

;; Edge cases
(fib 0)   ; => 0
(fib 1)   ; => 1

;; Run tests
(clojure.test/run-tests 'math.fibonacci)
;; Should show: 0 failures, 0 errors

;; Performance (should be fast due to memoization)
(time (fib 35))
;; Should complete in milliseconds
```

## 4. User Verification Handoff

**For Copilot**: Present this to the user:

"I've implemented a memoized fibonacci function. To verify it works:

1. Try calculating some fibonacci numbers: `(fib 10)` should return 55
2. Run the tests to verify edge cases work correctly
3. Check that larger values like `(fib 35)` complete quickly (memoization working)

**STOP** - Please test these and let me know if the implementation works correctly.

**Do not proceed with file commits until user confirms the implementation works correctly.**"

## 5. Structural Editing Guide for Copilot

Once user approves, use structural editing to commit:

Key principles:
- Plan edits: Create `src/math/fibonacci.cljs` with namespace, function, and tests
- Use `clojure_create_file` for new file
- Reference Section 2 for complete REPL state to include
- Ensure proper indentation and bracket balance

The exact editing plan will depend on user feedback about:
- File location preferences
- Whether to separate tests into separate file
- Any modifications requested to implementation

---END INTERACTIVE REPORT---
```

## Shared Utilities (`agent-util`)

Common utilities used by multiple agents:

### Path Helpers

```clojure
(require '[agents.agent-util :as au])

;; User-level instructions directory
(au/user-data-instructions-path)
;; => "/Users/user/Library/Application Support/Code/User/prompts"

(au/user-data-instructions-path "clojure.instructions.md")
;; => "/Users/user/Library/.../prompts/clojure.instructions.md"

;; Workspace-level instructions (requires active workspace)
(au/workspace-instructions-path)
;; => "/path/to/workspace/.github/instructions"

(au/workspace-instructions-path "memory.instructions.md")
;; => "/path/to/workspace/.github/instructions/memory.instructions.md"
```

### File Operations

```clojure
;; Read file or return nil if doesn't exist
(p/let [content (au/read-existing-file!+ "/path/to/file.md")]
  (if content
    (println "File exists")
    (println "File not found")))

;; List all .instructions.md files in directory
(p/let [files (au/list-instruction-files!+ "/path/to/dir")]
  files)
;; => ["clojure.instructions.md" "git-workflow-memory.instructions.md"]

;; Build map of file descriptions
(p/let [descriptions (au/build-file-descriptions-map!+ "/path/to/dir")]
  descriptions)
;; => [{:file "/full/path/clojure.instructions.md"
;;      :description "Clojure best practices"}
;;     {:file "/full/path/git.instructions.md"
;;      :description "Git workflow patterns"}]

;; Format for prompts
(def descriptions [{:file "a.md" :description "Desc A"}])
(au/format-description-listing descriptions)
;; => "```clojure\n[{:file \"a.md\" :description \"Desc A\"}]\n```\n"
```

### Utilities

```clojure
;; Normalize scope
(au/normalize-scope :workspace)    ; => :workspace
(au/normalize-scope "workspace")   ; => :workspace
(au/normalize-scope "ws")          ; => :workspace
(au/normalize-scope "global")      ; => :global
(au/normalize-scope "user")        ; => :global
(au/normalize-scope nil)           ; => :global (default)

;; Convert path to URI string
(au/file-path->uri-string "/Users/test/file.md")
;; => "file:///Users/test/file.md"

(au/file-path->uri-string "file:///already/uri")
;; => "file:///already/uri" (idempotent)

;; Extract description from frontmatter
(def content "---\ndescription: 'My description'\n---\n\n# Content")
(au/extract-description-from-content content)
;; => "My description"
```

## Design Principles

### Agent Independence

Each agent is self-contained with:
- Clear single responsibility
- Well-defined configuration options
- Predictable return values
- Comprehensive error handling

### Shared Infrastructure

Common concerns handled by `agent-util`:
- Path resolution (user vs workspace)
- File operations (with error handling)
- Scope normalization
- Data extraction and formatting

### Orchestrator Integration

All agents use `agent-orchestrator/autonomous-conversation!+`:
- Consistent configuration approach
- Standard conversation history
- Built-in instruction file selection
- Progress callback support

## Testing

Run all agent tests:

```clojure
(do (require 'run-all-tests :reload) (run-all-tests/run!+))
```

Run specific agent tests:

```clojure
;; Memory keeper tests
(require '[test.agents.memory-keeper-test :as mkt])
(clojure.test/run-tests 'test.agents.memory-keeper-test)

;; Interactive agent tests
(require '[test.agents.clojure-interactive-test :as cit])
(clojure.test/run-tests 'test.agents.clojure-interactive-test)

;; Agent util tests
(require '[test.agents.agent-util-test :as aut])
(clojure.test/run-tests 'test.agents.agent-util-test)
```

## Error Handling

### Memory Keeper

- **Parse failed**: Agent didn't return expected EDN format
- **File not found**: Tried to append to non-existent file
- **Write failed**: File system operation failed

Check `:success` field and `:error-type` for specific failure:

```clojure
(p/let [result (mk/record-memory!+ {...})]
  (if (:success result)
    (println "Recorded to" (:file-uri result))
    (println "Failed:" (:error result) "Type:" (:error-type result))))
```

### Interactive Agent

- **Report extraction failed**: Agent didn't wrap report in expected markers

Check `:report-extraction-failed` field:

```clojure
(p/let [result (ci/interactively-do!+ {...})]
  (if (:report-raw result)
    (println "Report:" (:report-raw result))
    (println "No report extracted - check :history for agent messages")))
```

## Future Enhancements

Potential additions to the agent system:

1. **Code Review Agent** - Analyzes code for improvements and best practices
2. **Refactoring Agent** - Suggests and implements refactorings
3. **Documentation Agent** - Generates or improves documentation
4. **Test Generation Agent** - Creates comprehensive test suites
5. **Performance Analysis Agent** - Identifies performance bottlenecks

Each would follow the same patterns:
- Clear configuration defaults
- Use shared `agent-util` utilities
- Integration with `agent-orchestrator`
- Comprehensive test coverage
- Documentation in this README
