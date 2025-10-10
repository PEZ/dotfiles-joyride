(ns memory-agent2
  "Autonomous memory recording agent using agentic workflow"
  (:require
   [promesa.core :as p]
   [joyride.core :as joy]
   [ai-workflow2.agents :as agents]
   [ai-workflow2.chat-util :as util]
   ["vscode" :as vscode]))

(def agent-model "grok-code-fast-1")

(defn user-data-uri [relative-path]
  (let [path (js/require "path")
        global-storage-path (-> (joy/extension-context)
                                .-globalStorageUri .-fsPath)
        user-dir (.join path global-storage-path ".." "..")
        full-path (.join path user-dir relative-path)]
    (vscode/Uri.file full-path)))

(def remember-prompt
  "# Memory Recording Agent

You record lessons learned into domain-specific memory files.

## Your Task

Given a lesson to remember (format: `>domain Lesson text` or just `Lesson text`), you must:
1. Find or create the appropriate memory file
2. Add the lesson to it
3. Done

## File Paths

- **Global memory files**: Use tool to find files matching pattern `*-memory.instructions.md` in your search
- **Domain-specific**: `{domain}-memory.instructions.md`
- **Universal**: `memory.instructions.md`

Examples:
- `>clojure Use REPL not println` → `clojure-memory.instructions.md`
- `>git Rebase with --autostash` → `git-workflow-memory.instructions.md`
- `Always check types` → `memory.instructions.md`

## Action Steps

1. **Search** for existing memory files using `copilot_findFiles` with pattern `*-memory.instructions.md`
2. **Determine absolute path**: Use results from findFiles to get workspace root (e.g., `/Users/user/workspace/`)
3. **Read** relevant file if it exists using `copilot_readFile`
4. **Write**:
   - Use `copilot_insertEdit` to add to existing file
   - Use `copilot_createFile` with ABSOLUTE path to create new file (e.g., `/Users/pez/.config/joyride/clojure-memory.instructions.md`)

## File Format

```markdown
---
description: 'Brief domain description'
applyTo: '**/*.{ext}'
---

# Domain Memory

Succinct tagline about this domain's patterns.

## Lesson Title

Concrete, actionable guidance. Show correct patterns with code examples when relevant.
```

## Critical Rules

- **ACT immediately** - Don't overthink, use tools right away
- **Search first** - Always check what files exist before creating
- **Use absolute paths** - Tool copilot_createFile requires absolute file paths like `/Users/pez/.config/joyride/clojure-memory.instructions.md`
- **Be concise** - Memory entries should be scannable and actionable
- **Extract patterns** - Generalize from specific instances
- **Focus on correct patterns** - Show what TO do, not what to avoid

Work efficiently. Execute tool calls. Complete the task.")

(defn async-iterator-seq
  "Consumes an async generator/iterator and returns a promise of all values.
  Works with any JavaScript object that implements Symbol.asyncIterator.

  Example:
    (p/let [values (async-iterator-seq some-async-generator)]
      (prn values)) ; => [val1 val2 val3 ...]"
  [generator]
  (let [iter (.call (aget generator js/Symbol.asyncIterator) generator)
        results (atom [])]
    (p/loop []
      (p/let [v (.next iter)]
        (if (.-done v)
          @results
          (do (swap! results conj (.-value v))
              (p/recur)))))))

(defn consume-lm-response
  "Consumes a Language Model response stream and returns the complete text.

  Example:
    (p/let [response (make-lm-request model messages)
            text (consume-lm-response response)]
      (prn text))"
  [response]
  (p/let [chunks (async-iterator-seq (.-text response))]
    (apply str chunks)))

(def autonomization-instructions
  "You are transforming a prompt to be fully autonomous. Make these specific changes:

1. Find any text that says 'request human input' - replace it with 'make best judgment based on available information'
2. Find any text that says 'ask the user' - replace it with 'make best judgment based on available information'
3. Keep ALL other text exactly as it appears in the original
4. Return the COMPLETE transformed prompt with all sections intact

Prompt to transform:")

(defn autonomize-prompt!+
  "Transforms a prompt to be fully autonomous by removing human input instructions.

  Returns a promise of the autonomous prompt string."
  [prompt]
  (p/let [model (util/get-model-by-id!+ agent-model)]
    (if model
      (let [full-prompt (str autonomization-instructions "\n\n" prompt)
            messages [(vscode/LanguageModelChatMessage.User full-prompt)]
            token-source (vscode/CancellationTokenSource.)]
        (p/let [response (.sendRequest model
                                       (clj->js messages)
                                       #js {}
                                       (.-token token-source))]
          (consume-lm-response response)))
      (throw (js/Error. "No language model available")))))

(defn record-memory!+
  "Records a memory using an autonomous agent workflow.

  Takes context and optional configuration, returns a promise of the agent result.

  Args:
    context - String containing the lesson/mistake/pattern to remember
    opts - Optional map with keys:
      :model-id - Model to use (default: 'grok-code-fast-1')
      :max-turns - Maximum conversation turns (default: 15)
      :load-joyride-context? - Load Joyride guide for agent (default: false)
      :progress-callback - Function to call with progress updates

  Returns:
    Promise of {:history [...] :reason :keyword :final-response {...}}"
  ([context]
   (record-memory!+ context {}))

  ([context {:keys [model-id max-turns progress-callback load-joyride-context?]
             :or {model-id agent-model
                  max-turns 15
                  load-joyride-context? false  ; Disabled by default - not needed for file ops
                  progress-callback #(println "📝" %)}}]
   (p/let [;; Step 1: Optionally load Joyride environment context
           joyride-context (when load-joyride-context?
                             (vscode/lm.invokeTool
                              "joyride_basics_for_agents"
                              #js {:input #js {}}))
           joyride-text (when joyride-context
                          (util/extract-tool-result-content joyride-context))

           ;; Step 2: Create goal with optional Joyride context
           goal (if joyride-text
                  (str "# JOYRIDE ENVIRONMENT CONTEXT\n\n"
                       joyride-text
                       "\n\n---\n\n"
                       "# YOUR TASK\n\n"
                       "CONTEXT TO REMEMBER:\n"
                       context
                       "\n\n"
                       remember-prompt)
                  (str "CONTEXT TO REMEMBER:\n" context "\n\n" remember-prompt))

           ;; Step 3: Define tools for memory recording (file operations + todo list)
           tool-ids ["copilot_findFiles"
                     "copilot_readFile"
                     "copilot_findTextInFiles"
                     "copilot_createFile"
                     "copilot_insertEdit"  ; For editing existing files
                     "manage_todo_list"]

           ;; Step 4: Call the autonomous agent with unsafe tools enabled for file operations
           result (agents/autonomous-conversation!+
                   goal
                   {:model-id model-id
                    :max-turns max-turns
                    :tool-ids tool-ids
                    :allow-unsafe-tools? true  ; Enable file creation/editing tools
                    :progress-callback progress-callback})]

     result)))

(comment
  ;; Basic usage - autonomous memory recording
  (record-memory!+
   ">clojure Mistake: Used println for debugging. Correction: Use REPL evaluation of subexpressions.")

  ;; With custom options
  (record-memory!+
   "Discovery: Threading macros improve readability in data pipelines."
   {:model-id "claude-sonnet-4"
    :max-turns 15
    :progress-callback vscode/window.showInformationMessage})

  ;; Get result and inspect
  (p/let [result (record-memory!+ "Context here...")]
    (println "Reason:" (:reason result))
    (println "History entries:" (count (:history result))))

  :rcf)
