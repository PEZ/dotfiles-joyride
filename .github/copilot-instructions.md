---
description: 'Joyride User project — scripts and source files available across all VS Code windows'
applyTo: '**'
---

# Joyride User Project

This is your Joyride User project at `~/.config/joyride/`. Scripts and
source files here are available globally across all VS Code windows.

The Joyride extension bundles skills with comprehensive API documentation
and pattern guidance. These instructions focus on this project's specific
content.

## Project Inventory

### Scripts (`scripts/`)

- `user_activate.cljs` — Activation script, manages disposables
- `agent_monitor_reveal.cljs`
- `awesome_copilot.cljs`
- `awesome_cursorrules_to_copilot.cljs`
- `clojuredocs.cljs`
- `create_memory.cljs`
- `cursorrules_to_copilot.cljs`
- `fuzzy.cljs`
- `hello_joyride_user_script.cljs`
- `highlight_thousands.cljs`
- `html_to_hiccup.cljs`
- `js_repl.cljs`
- `keybinding_palette.cljs`
- `my_lib.cljs`
- `philosophers_race.cljs`
- `print_string.cljs`
- `prompt_sync.cljs`, `prompt_sync_testing.cljs`
- `replicant_tictactoe.cljs`
- `resolve_issue.cljs`
- `showtime.cljs`
- `sidecar_hello.cljs`
- `splunk_test.cljs`
- `timezones.cljs`
- `typist.cljs`
- `vscode_state_explorer.cljs`
- `webview_editor.cljs`

### Source (`src/`)

- `my_lib.cljs`, `util.cljs` — Shared utilities
- `flares.cljs`, `flare_lab.cljs` — Flare experiments
- `gh_diff.cljs`, `git_fuzzy.cljs` — Git tooling
- `human_intelligence.cljs` — Human input integration
- `js_parse.cljs` — JS parsing utilities
- `lm_fiddle.cljs` — LM experimentation
- `pastedown.cljs` — Paste-as-markdown
- `showtime.cljs` — Presentation support
- `splunk.cljs` — Splunk integration
- `async_test.cljs` — Async pattern testing
- `agents/` — Agent utilities, Clojure interactive, instructions selector, memory keeper
- `ai_workflow_old/` — Legacy AI workflow (human intelligence, mood selector)
- `bench/` — Benchmarks (object_to_data)
- `lm_dispatch/` — LM dispatch system (agent core, orchestrator, monitor, state, UI, logging, utils)
- `prezo/` — Presentation system (next_slide, next_slide_notes)
- `tts/` — Text-to-speech (audio generation, audio playback)
- `test/` — Tests for agents, lm_dispatch modules

## Development

Use the REPL (`joyride_evaluate_code`) as your primary tool. Develop
incrementally, evaluate subexpressions, only update files when asked.
Prefer structural editing tools when editing Clojure files.

As new scripts and source files are added to this project, update the
inventory above.

**When to use `awaitResult: false` (default):**
- Synchronous operations
- Fire-and-forget async operations like simple information messages
- Side-effect async operations where you don't need the return value

### Promise Handling
```clojure
(require '[promesa.core :as p])

;; Users need to understand async operations
(p/let [result (vscode/window.showInputBox #js {:prompt "Enter value:"})]
  (when result
    (vscode/window.showInformationMessage (str "You entered: " result))))

;; Pattern for unwrapping async results in REPL (use awaitResult: true)
(p/let [files (vscode/workspace.findFiles "**/*.cljs")]
  (def found-files files))
;; Now `found-files` is defined in the namespace for later use

;; Yet another example with `joyride.core/slurp` (use awaitResult: true)
(p/let [content (joyride.core/slurp "some/file/in/the/workspace.csv")]
  (def content content) ; if you want to use/inspect `content` later in the session
  ; Do something with the content
  )
```

### Extension APIs
```clojure
;; How to access other extensions safely
(when-let [ext (vscode/extensions.getExtension "ms-python.python")]
  (when (.-isActive ext)
    (let [python-api (.-exports ext)]
      ;; Use Python extension API safely
      (-> python-api .-environments .-known count))))

;; Always check if extension is available first
(defn get-python-info []
  (if-let [ext (vscode/extensions.getExtension "ms-python.python")]
    (if (.-isActive ext)
      {:available true
       :env-count (-> ext .-exports .-environments .-known count)}
      {:available false :reason "Extension not active"})
    {:available false :reason "Extension not installed"}))
```

## Joyride Flares - WebView Creation

Joyride Flares provide a convenient way to create WebView panels and sidebar views.

### Basic Usage
```clojure
(require '[joyride.flare :as flare])

;; Create a flare with Hiccup
(flare/flare!+ {:html [:h1 "Hello World!"]
                :title "My Flare"
                :key "example"})

;; Create sidebar flare (slots 1-5 available)
(flare/flare!+ {:html [:div [:h2 "Sidebar"] [:p "Content"]]
                :key :sidebar-1})

;; Load from file (HTML or EDN with Hiccup)
(flare/flare!+ {:file "assets/my-view.html"
                :key "my-view"})

;; Display external URL
(flare/flare!+ {:url "https://example.com"
                :title "External Site"})
```

**Note**: `flare!+` returns a promise, use `awaitResult: true`.

### Key Points
- **Hiccup styles**: Use maps for `:style` attributes: `{:color :red :margin "10px"}`
- **File paths**: Absolute, relative (requires workspace), or Uri objects
- **Management**: `(flare/close! key)`, `(flare/ls)`, `(flare/close-all!)`
- **Bidirectional messaging**: Use `:message-handler` and `post-message!+`

**Full documentation**: [API docs](https://github.com/BetterThanTomorrow/joyride/blob/master/doc/api.md#joyrideflare)

**Comprehensive examples**: [flares_examples.cljs](https://github.com/BetterThanTomorrow/joyride/blob/master/examples/.joyride/src/flares_examples.cljs)

## Common User Patterns

### Script Execution Guard
```clojure
;; Essential pattern - only run when invoked as script, not when loaded in REPL
(when (= (joyride/invoked-script) joyride/*file*)
  (main))
```

### Managing Disposables
```clojure
;; Always register disposables with extension context
(let [disposable (vscode/workspace.onDidOpenTextDocument handler)]
  (.push (.-subscriptions (joyride/extension-context)) disposable))
```

## Editing files

Develop using the REPL. Yet, sometimes you need to edit file. And when you do, prefer structural editing tools.

## Read File Preambles First

When editing any file, particularly in Clojure projects, begin by reading the first 10 lines to review the preamble. This section often outlines critical requirements. Always start with the preamble to maintain the file's contract and avoid workflow mismatches.

## Important workflow

- Use interactive programming
- Work using TDD in the repl
  - Develop `cljs.test` tests in the repl
  - Tests reside in `src/test/` which also means that their namespaces all start with `test.`, e.g. `test.agents.memory-keeper-test`

  To run all tests:
  ```clojure
  (do (require 'run-all-tests :reload) (run-all-tests/run!+))
  ```
- When committing repl verified code to the repl, always prefer your structural editing tools