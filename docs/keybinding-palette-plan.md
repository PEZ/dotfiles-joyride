# Keybinding Command Palette — Implementation Plan

## Overview

A QuickPick-based "missing command palette" that shows all user keybindings from `keybindings.json`, allowing search and execution with full args fidelity.

**Trigger**: `ctrl+alt+j ctrl+alt+j`

## File Structure

- **File**: `src/keybinding_palette.cljs`
- **Namespace**: `keybinding-palette`
- **Naming rationale**: Follows project conventions (`git-fuzzy`, `human-intelligence` — descriptive, kebab-case)

## Key Design Decisions

### 1. `showQuickPick` (not `createQuickPick`)

All three reviews converged: for a simple "select → execute" flow, `showQuickPick` is sufficient. It supports `matchOnDescription`, `matchOnDetail`, preserves custom properties on items, and preserves insertion order. No need for the ceremony of `createQuickPick` unless we add "Recently Used" later.

### 2. Keep args as raw JS — no round-trip

`jsonc/parse` returns native JS objects. `vscode/commands.executeCommand` expects JS args. Converting through `js->clj`/`clj->js` is a no-op that adds risk for zero benefit. Only extract string fields (`title`, `key`, `command`, `when`) for display; stash `args` as the raw JS value on the QuickPickItem.

### 3. No caching — re-read every invocation

The full pipeline (readFileSync + jsonc/parse + transform) takes ~2ms for 184 entries. Re-reading ensures fresh results when keybindings.json changes. No file watcher complexity needed.

### 4. Args execution: unified `(some? args)` check

```clojure
(if (some? args)
  (vscode/commands.executeCommand command args)  ;; args already JS
  (vscode/commands.executeCommand command))
```

- `(some? args)` correctly handles `args: false` (a real entry in the file)
- No need for type-branching — raw JS args pass through directly
- **Resolved**: Vector args (7 entries) — VS Code keybindings pass `args` as a single value to `executeCommand`, not spread. Our `(executeCommand command args)` is correct.

### 5. `:when` destructuring gotcha

Destructuring `{:keys [when]}` shadows `clojure.core/when` in SCI, causing `"V.call is not a function"` errors. Avoided entirely by using JS property access on the raw entry objects (`(.-when entry)`).

### 6. Filtering

- **Include**: All entries with a `title` field
- **Exclude**: Negated commands (command starts with `-`) — these are unbindings, not executable
- This cleanly separates the 126 actionable entries from the 58 unbindings

## QuickPick Item Layout

| Field | Content | Example |
|-------|---------|---------|
| `label` | Title (or command as fallback) | `"Squeeze Sexp"` |
| `description` | Keyboard shortcut | `"ctrl+alt+r ctrl+alt+r"` |
| `detail` | Command name + when clause | `"runCommands · when: editorFocus"` |

Fuzzy search across all three fields via `matchOnDescription: true`, `matchOnDetail: true`.

## Data Pipeline

```
keybindings.json (JSONC file on disk)
  → fs/readFileSync (sync, ~2ms)
  → jsonc/parse (returns JS array of JS objects)
  → iterate JS entries, extract display strings + keep raw args
  → filter: has title, not negated
  → sort by title (case-insensitive)
  → create #js QuickPickItems with stashed command/args
  → showQuickPick with matchOnDescription + matchOnDetail
  → on selection: executeCommand with raw JS args
```

## Code Skeleton

```clojure
(ns keybinding-palette
  (:require ["jsonc-parser" :as jsonc]
            ["fs" :as fs]
            ["os" :as os]
            ["path" :as path]
            ["vscode" :as vscode]
            [clojure.string :as str]
            [joyride.core :as joyride]
            [promesa.core :as p]))

;; "Install" by placing this file in ~/.config/joyride/src
;; and adding this keybinding to keybindings.json:
;; {
;;   "title": "Keybinding Command Palette",
;;   "key": "ctrl+alt+j ctrl+alt+j",
;;   "command": "joyride.runCode",
;;   "args": "(require '[keybinding-palette :as kp] :reload) (kp/show-palette!+)"
;; }

(defn- keybindings-path []
  (path/join (os/homedir)
             "Library" "Application Support"
             (str/replace vscode/env.appName #"^Visual Studio " "")
             "User" "keybindings.json"))

(defn- read-keybindings
  "Reads and parses keybindings.json (JSONC). Returns JS array."
  []
  (jsonc/parse (fs/readFileSync (keybindings-path) "utf8")))

(defn- js-entry->item
  "Converts a JS keybinding entry to a QuickPickItem.
   Returns nil for entries that should be filtered out."
  [entry]
  (let [title (.-title entry)
        command (.-command entry)
        key-str (.-key entry)
        when-clause (.-when entry)
        args (.-args entry)]
    (when (and title
               (not (str/starts-with? command "-")))
      #js {:label title
           :description key-str
           :detail (if when-clause
                     (str command " · when: " when-clause)
                     command)
           :_command command
           :_args args})))

(defn- execute-keybinding!
  "Executes a command with its args (already JS)."
  [item]
  (let [command (.-_command item)
        args (.-_args item)]
    (if (some? args)
      (vscode/commands.executeCommand command args)
      (vscode/commands.executeCommand command))))

(defn show-palette!+
  "Shows the keybinding command palette."
  []
  (p/let [entries (read-keybindings)
          items (->> entries
                     (keep js-entry->item)
                     (sort-by #(str/lower-case (.-label %)))
                     into-array)
          selected (vscode/window.showQuickPick
                    items
                    #js {:title "Keybinding Command Palette"
                         :placeholder "Search keybindings..."
                         :matchOnDescription true
                         :matchOnDetail true})]
    (when selected
      (execute-keybinding! selected))))

(when (= (joyride/invoked-script) joyride/*file*)
  (show-palette!+))
```

## Function Signatures

| Function | Signature | Purpose |
|----------|-----------|---------|
| `keybindings-path` | `[] → string` | Path to keybindings.json (macOS, detects Insiders vs Stable) |
| `read-keybindings` | `[] → JS Array` | Read + JSONC parse |
| `js-entry->item` | `[JS Object] → #js QuickPickItem \| nil` | Transform + filter |
| `execute-keybinding!` | `[#js QuickPickItem] → Promise` | Execute command with args |
| `show-palette!+` | `[] → Promise` | Main entry: read → display → execute |

## Keybinding Entry

```json
{
  "title": "Keybinding Command Palette",
  "key": "ctrl+alt+j ctrl+alt+j",
  "command": "joyride.runCode",
  "args": "(require '[keybinding-palette :as kp] :reload) (kp/show-palette!+)"
}
```

## Future Enhancements (v2)

- **Recently Used**: Track usage in `globalState`, show recently-used section at top with `QuickPickItemKind.Separator`. Would require switching to `createQuickPick` with `sortByLabel=false`. REPL-validated as feasible.

###  v3

- **Cross-platform paths**: Linux (`~/.config/<app>/User/`) and Windows (`%APPDATA%/<app>/User/`).

## REPL-Validated Facts

- 184 total keybindings, 126 with `title`, 58 negated (all without title)
- Args types: 47 nil, 39 string, 32 object, 7 array, 1 boolean (`false`)
- 10 duplicate titles (disambiguated by keybinding in description + when in detail)
- `jsonc-parser` installed as npm dep, works from Joyride
- `showQuickPick` supports `matchOnDescription`, `matchOnDetail`, custom properties survive selection
- Full pipeline: ~2ms
- `:when` destructuring shadows `clojure.core/when` — avoided by using JS property access `(.-when entry)`
- `(some? false)` → `true` — correctly handles boolean args
- Vector args: keybindings pass args as single value (not spread) — `(executeCommand cmd args)` is correct
- Platform detection: `(.-platform js/process)` → `"darwin"`, `vscode/env.appName` → `"Visual Studio Code - Insiders"`, prior art: `(str/replace appName #"^Visual Studio " "")`
- `globalState` works from Joyride for future "Recently Used" feature
- `QuickPickItemKind.Separator` exists (value -1) for future grouping
