# Memory Agent System - Implementation Guide

**Date Created:** 2025-10-10
**Status:** Production Ready
**Purpose:** Enable AI agents to offload memory creation to background LLM requests without polluting main chat context

## Quick Start

1. Copy the implementation code below to `~/.config/joyride/src/memory_agent.cljs`
2. Test from REPL: `(require '[memory-agent :as ma]) (ma/create-and-copy-memory! "Test context")`
3. Use in your workflow when agents make mistakes or discover patterns

## The Problem

When AI agents make mistakes or discover patterns, you want to capture them as "memories" (instructions) for future sessions. But doing this in the current chat:
- **Consumes valuable context** from the main task
- **Distracts** from the primary work
- **Requires manual** copy/paste/formatting

## The Solution

Let the AI agent spawn a **background LLM request** that:
- ✅ Creates memory entries without touching main chat context
- ✅ Runs asynchronously via Language Model API
- ✅ Returns formatted markdown for `.instructions.md` files
- ✅ Offers interactive save/copy options

## How It Works

```
Main Chat Agent → Detects pattern → Tells user to run:
                                     (ma/create-and-handle-memory! "context")
                                              ↓
                                     Background LLM request
                                              ↓
                                     Formatted memory entry
                                              ↓
                                     User: Copy/Show/Append
```

## Technical Foundation

### The Core Challenge

The VS Code Language Model API returns async iterators for streaming responses. In SCI (Joyride's ClojureScript interpreter), you can't use `for await...of` directly.

### The Solution: Borkdude's Async Iterator Abstraction

Thanks to Borkdude (creator of SCI), we have an elegant solution:

```clojure
(defn async-iterate
  "Consumes any async iterator and returns a promise of all values"
  [generator]
  (let [iter (.call (aget generator js/Symbol.asyncIterator) generator)
        results (atom [])]
    (p/loop []
      (p/let [v (.next iter)]
        (if (.-done v)
          @results
          (do (swap! results conj (.-value v))
              (p/recur)))))))
```

**Key insights:**
1. Extract iterator: `(aget generator js/Symbol.asyncIterator)`
2. Bind context: `.call method object`
3. Use promesa's `p/loop` for async iteration
4. Collect results in an atom

## Complete Implementation

Copy this entire namespace to: `~/.config/joyride/src/memory_agent.cljs`

```clojure
(ns memory-agent
  "Background memory creation agent using Language Model API"
  (:require
   [promesa.core :as p]
   ["vscode" :as vscode]))

;; ============================================================================
;; Core Async Iterator Utilities
;; ============================================================================

(defn async-iterate
  "Consumes an async generator/iterator and returns a promise of all values.
  Works with any JavaScript object that implements Symbol.asyncIterator.

  Example:
    (p/let [values (async-iterate some-async-generator)]
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

;; ============================================================================
;; Language Model API Integration
;; ============================================================================

(defn consume-lm-response
  "Consumes a Language Model response stream and returns the complete text.

  Example:
    (p/let [response (make-lm-request model messages)
            text (consume-lm-response response)]
      (prn text))"
  [response]
  (p/let [chunks (async-iterate (.-text response))]
    (apply str chunks)))

(defn select-model
  "Selects a language model. Returns first available model or nil.

  Options:
    :vendor - e.g., \"copilot\"
    :family - e.g., \"gpt-4o\", \"claude-sonnet-4\", \"gpt-4o-mini\"

  Example:
    (p/let [[model] (select-model {:vendor \"copilot\" :family \"gpt-4o\"})]
      (when model
        (prn \"Model ID:\" (.-id model))))"
  [{:keys [vendor family] :or {vendor "copilot" family "gpt-4o"}}]
  (vscode/lm.selectChatModels #js {:vendor vendor :family family}))

;; ============================================================================
;; Memory Creation Agent
;; ============================================================================

(defn create-memory-from-context!
  "Spawns an LLM request to create a memory entry from mistake/correction context.

  Returns a promise of:
    {:success true :memory \"...\"} on success
    {:error \"...\"} on failure

  Args:
    context - String containing the mistake/correction/pattern to remember
    opts - Optional map:
      :model-family - Model to use (default: \"gpt-4o\")
      :format - How to format the memory (default: instructions-file)

  Example:
    (p/let [result (create-memory-from-context!
                     \"Mistake: Did X. Correction: Do Y instead.\")]
      (when (:success result)
        (prn (:memory result))))"
  ([context] (create-memory-from-context! context {}))
  ([context {:keys [model-family] :or {model-family "gpt-4o"}}]
   (p/let [[model] (select-model {:family model-family})]
     (if model
       (let [memory-prompt (str "You are a memory creation assistant for AI coding agents.

Task: Create a concise, actionable memory entry for a Copilot instructions file.

Context provided:
" context "

Format your response as a brief markdown entry (2-4 sentences) that captures:
1. What the mistake/discovery was
2. The correct approach
3. When this pattern applies

Keep it concise and actionable. Do not include file paths or metadata headers.")
             messages [(vscode/LanguageModelChatMessage.User memory-prompt)]
             token-source (vscode/CancellationTokenSource.)]
         (p/let [response (.sendRequest model
                                        (clj->js messages)
                                        #js {}
                                        (.-token token-source))
                 result (consume-lm-response response)]
           {:success true
            :memory result
            :model-id (.-id model)}))
       {:error "No language model available"}))))

;; ============================================================================
;; User Interaction Workflows
;; ============================================================================

(defn create-and-show-memory!
  "Creates memory and shows it in a new editor for review/editing.

  Example:
    (create-and-show-memory! \"Context about mistake...\")"
  [context]
  (p/let [result (create-memory-from-context! context)]
    (if (:success result)
      (p/do
        (p/let [doc (vscode/workspace.openTextDocument
                      #js {:content (:memory result)
                           :language "markdown"})]
          (vscode/window.showTextDocument doc))
        result)
      (do
        (vscode/window.showErrorMessage
          (str "Failed to create memory: " (:error result)))
        result))))

(defn create-and-copy-memory!
  "Creates memory and copies it to clipboard.

  Example:
    (create-and-copy-memory! \"Context about mistake...\")"
  [context]
  (p/let [result (create-memory-from-context! context)]
    (if (:success result)
      (p/do
        (vscode/env.clipboard.writeText (:memory result))
        (vscode/window.showInformationMessage "📋 Memory copied to clipboard!")
        result)
      (do
        (vscode/window.showErrorMessage
          (str "Failed to create memory: " (:error result)))
        result))))

(defn create-and-handle-memory!
  "Complete workflow: create memory and offer user actions.

  Presents user with options:
    - Copy to Clipboard
    - Show in Editor
    - Append to File

  Example:
    (create-and-handle-memory!
      \"Mistake: Used X. Correction: Use Y instead.\")"
  [context]
  (p/let [result (create-memory-from-context! context)]
    (if (:success result)
      (p/let [choice (vscode/window.showInformationMessage
                       "✨ Memory entry created! What next?"
                       "Copy to Clipboard"
                       "Show in Editor"
                       "Append to File")]
        (case choice
          "Copy to Clipboard"
          (p/do
            (vscode/env.clipboard.writeText (:memory result))
            (vscode/window.showInformationMessage "📋 Copied to clipboard!")
            result)

          "Show in Editor"
          (p/let [doc (vscode/workspace.openTextDocument
                        #js {:content (:memory result)
                             :language "markdown"})]
            (vscode/window.showTextDocument doc)
            result)

          "Append to File"
          (p/let [file-uri (vscode/window.showOpenDialog
                             #js {:canSelectMany false
                                  :filters #js {:Markdown #js ["md"]}
                                  :openLabel "Append Memory To"})]
            (if (seq file-uri)
              (let [uri (first file-uri)]
                (p/let [content (vscode/workspace.fs.readFile uri)
                        text (js/TextDecoder. "utf-8")
                        existing (.decode text content)
                        new-content (str existing "\n\n" (:memory result))]
                  (vscode/workspace.fs.writeFile
                    uri
                    (.encode (js/TextEncoder.) new-content))
                  (vscode/window.showInformationMessage "✅ Memory appended!")
                  result))
              result))

          nil))
      (do
        (vscode/window.showErrorMessage
          (str "Failed to create memory: " (:error result)))
        result))))

(defn append-to-instructions-file!
  "Appends memory to a specific instructions file (fire-and-forget).

  Args:
    memory - The memory text to append
    file-path - Absolute path or workspace-relative path

  Example:
    (p/let [result (create-memory-from-context! context)]
      (when (:success result)
        (append-to-instructions-file!
          (:memory result)
          \".github/instructions/memory.instructions.md\")))"
  [memory file-path]
  (p/let [workspace-folders vscode/workspace.workspaceFolders]
    (when (seq workspace-folders)
      (let [workspace-path (-> workspace-folders first .-uri .-fsPath)
            full-path (if (js/require "path").isAbsolute file-path)
                       file-path
                       (js/require "path").join workspace-path file-path)
            uri (vscode/Uri.file full-path)]
        (p/let [content (vscode/workspace.fs.readFile uri)
                text (js/TextDecoder. "utf-8")
                existing (.decode text content)
                new-content (str existing "\n\n" memory)]
          (vscode/workspace.fs.writeFile
            uri
            (.encode (js/TextEncoder.) new-content))
          (vscode/window.showInformationMessage
            (str "✅ Memory appended to " file-path)))))))

;; ============================================================================
;; Export Public API
;; ============================================================================

(def public-api
  {:async-iterate async-iterate
   :consume-lm-response consume-lm-response
   :select-model select-model
   :create-memory-from-context! create-memory-from-context!
   :create-and-show-memory! create-and-show-memory!
   :create-and-copy-memory! create-and-copy-memory!
   :create-and-handle-memory! create-and-handle-memory!
   :append-to-instructions-file! append-to-instructions-file!})

(comment
  ;; Quick test
  (require '[memory-agent :as ma])

  ;; Simple usage
  (ma/create-and-copy-memory!
    "Mistake: Did X. Correction: Do Y instead.")

  ;; Interactive workflow
  (ma/create-and-handle-memory!
    "Discovery: Found new pattern Z that improves performance.")

  ;; Background append
  (p/let [result (ma/create-memory-from-context! "Context...")]
    (when (:success result)
      (ma/append-to-instructions-file!
        (:memory result)
        ".github/instructions/patterns.instructions.md")))

  :rcf)
```

## Usage Guide

### Quick Test from REPL

```clojure
;; 1. Load the namespace
(require '[memory-agent :as ma] :reload)

;; 2. Test with simple copy
(ma/create-and-copy-memory!
  "Mistake: Did X. Correction: Do Y instead.")
;; Result: Memory copied to clipboard!

;; 3. Test interactive workflow
(ma/create-and-handle-memory!
  "Discovery: Found pattern Z that improves performance.")
;; Result: Dialog with Copy/Show/Append options
```

### Common Workflows

#### 1. Quick Copy (Most Common)
```clojure
(ma/create-and-copy-memory!
  "Mistake: Used replace_string_in_file without enough context.
   Correction: Always include 3-5 lines before and after.")
```

#### 2. Review Before Saving
```clojure
(ma/create-and-show-memory!
  "Pattern: When working with drag-and-drop, use state machine...")
;; Opens in new editor for review/editing
```

#### 3. Auto-Append to File
```clojure
(p/let [result (ma/create-memory-from-context!
                 "Discovery: Borkdude's async-iterate works everywhere")]
  (when (:success result)
    (ma/append-to-instructions-file!
      (:memory result)
      ".github/instructions/patterns.instructions.md")))
```

#### 4. Full Interactive (Best for First-Time)
```clojure
(ma/create-and-handle-memory!
  "Context about mistake/correction/pattern...")
;; Choose: Copy / Show in Editor / Append to File
```

### Keyboard Shortcut Setup

Create: `~/.config/joyride/scripts/create_memory.cljs`

```clojure
(ns create-memory
  (:require
   [memory-agent :as ma]
   [promesa.core :as p]
   ["vscode" :as vscode]))

(defn activate []
  (p/let [;; Get selected text as context
          editor vscode/window.activeTextEditor
          selection (when editor (.-selection editor))
          text (when selection
                 (.getText (.-document editor) selection))]
    (if (and text (seq text))
      ;; Use selected text
      (ma/create-and-handle-memory! text)
      ;; Ask for input
      (p/let [input (vscode/window.showInputBox
                      #js {:prompt "Enter context for memory creation"
                           :placeHolder "Mistake: ... Correction: ..."})]
        (when (and input (seq input))
          (ma/create-and-handle-memory! input))))))

(activate)
```

Then add to VS Code keybindings (Command Palette → "Preferences: Open Keyboard Shortcuts (JSON)"):

```json
{
  "key": "ctrl+shift+m",
  "command": "joyride.runUserScript",
  "args": "create_memory.cljs"
}
```

Now `Ctrl+Shift+M` creates memories from selected text or prompts for input!

## Agent Integration

### Add to Agent Instructions

```markdown
## Memory Creation Workflow

When I make a mistake or discover a useful pattern:

1. **Identify what to remember**: Mistake + Correction + When it applies
2. **Tell the user**: "I'll help you create a memory entry for this"
3. **Provide the command**:
   ```clojure
   (require '[memory-agent :as ma])
   (ma/create-and-handle-memory!
     "Mistake: [what I did wrong]
      Correction: [what's correct]
      Pattern: [when this applies]")
   ```
4. **Continue main task** - Memory creation runs independently

The memory agent runs via Language Model API and doesn't consume my context!
```

### Example Agent Usage

**Agent sees mistake:**
> I see I made an error using `replace_string_in_file` without enough context. Let me help you create a memory for this:
>
> ```clojure
> (require '[memory-agent :as ma])
> (ma/create-and-handle-memory!
>   "Mistake: Used replace_string_in_file without 3-5 context lines.
>    Correction: Always include context lines before and after target.
>    Pattern: All structural editing tools need unambiguous matching.")
> ```
>
> Now, back to fixing the actual issue...

## Troubleshooting

### "No language model available"

Check what models you have:
```clojure
(require '["vscode" :as vscode])
(require '[promesa.core :as p])

(p/let [models (vscode/lm.selectChatModels #js {})]
  (prn "Available models:" (count models))
  (doseq [m models]
    (prn "  -" (.-id m) (.-family m))))
```

If no models, ensure GitHub Copilot is active and logged in.

### Memory not formatted well

Try different models:
```clojure
;; Default uses gpt-4o
(ma/create-memory-from-context! context)

;; Try Claude
(ma/create-memory-from-context! context {:model-family "claude-sonnet-4"})

;; Try mini for faster results
(ma/create-memory-from-context! context {:model-family "gpt-4o-mini"})
```

### File append fails

Check the path:
```clojure
;; For workspace-relative paths
(ma/append-to-instructions-file! memory ".github/instructions/file.md")

;; For absolute paths
(ma/append-to-instructions-file! memory "/full/path/to/file.md")
```

## Next Steps

### Immediate
1. ✅ Copy code to `~/.config/joyride/src/memory_agent.cljs`
2. ✅ Test from REPL
3. ✅ Create keyboard shortcut script
4. ✅ Add to agent instructions

### Soon
- **Auto-categorize**: Let LLM suggest which instruction file

## Technical Notes

### SCI Limitations in Joyride

```clojure
;; ✅ Works in SCI
(aget obj "property")
(.-property obj)
(.method obj arg)

;; ❌ Doesn't work in SCI
(unchecked-get obj "property")
js/Symbol.asyncIterator  ; Use (.-asyncIterator js/Symbol)
```

### Accessing Symbol.asyncIterator

```clojure
;; ✅ Correct
(.-asyncIterator js/Symbol)
(aget js/Symbol "asyncIterator")

;; ❌ Won't work
js/Symbol.asyncIterator
```

### Promise Patterns

```clojure
;; ✅ Use p/loop for async iteration
(p/loop [acc []]
  (p/let [v (async-op)]
    (if done? acc (p/recur (conj acc v)))))

;; ❌ Don't block on promises
(loop [acc []]
  (let [v @(async-op)]  ; Blocks!
    (recur (conj acc v))))
```

## Resources

- **VS Code Language Model API**: https://code.visualstudio.com/api/extension-guides/language-model
- **Joyride**: https://github.com/BetterThanTomorrow/joyride
- **SCI**: https://github.com/babashka/sci
- **Promesa**: https://github.com/funcool/promesa

---

**Happy Memory Making!** 🧠✨

Use this system to capture patterns, mistakes, and discoveries so AI agents keep getting better at helping you!
