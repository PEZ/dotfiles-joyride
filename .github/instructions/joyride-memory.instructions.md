---
description: 'Memories for Joyride domain - patterns and lessons in VS Code automation using ClojureScript'
applyTo: 'scripts/**/*.cljs, src/**/*.cljs, deps.edn, .joyride/**/*.cljs'
---

# Joyride domain memories: reusable knowledge for effective VS Code extension development and user automation

## Resetting Misplaced View Locations

When developing or using Joyride sidebar views, leverage VS Code's built-in `workbench.action.resetViewLocations` command to restore default positions. This ensures views return to sidebars after dragging mishaps, maintaining a clean UI without manual state manipulation.

```clojure
(require '["vscode" :as vscode])

;; Execute to reset all view locations to defaults
(vscode/commands.executeCommand "workbench.action.resetViewLocations")
```

Apply this pattern early when views appear stuck, promoting reliable extension behavior and user experience.

## Prioritize Native VS Code Features Over Custom Builds

Before developing custom Joyride scripts to extend VS Code capabilities, verify if the VS Code team is already implementing similar features:

1. Search the vscode GitHub repository for related feature requests and issues.
2. Review VS Code's public roadmap and iteration plans.
3. Scan recent release notes for newly added functionality.
4. Check community discussions on Slack or Discord for insights on upcoming features.

This ensures future compatibility, minimizes maintenance overhead, and benefits from seamless native integrations. Reserve custom builds for highly specialized workflows, features the team won't implement, urgent needs, or experimental/learning purposes.

```clojure
;; Example: Before crafting a custom script, confirm it's not on the roadmap
;; to avoid redundant work and ensure long-term viability.
```

## Documenting Keybindings in Source Files

To simplify user adoption of recommended shortcuts, when creating keyboard shortcuts for Joyride functions, document them directly in the ClojureScript source file as commented-out JSON. This keeps keybindings discoverable alongside the code and ready for users to copy into their `keybindings.json`.

Group related keybindings in the file preamble for easy reference. Before proposing a shortcut, verify it's available by checking the user's `keybindings.json`.

Example file header snippet:
```clojure
;; Keybindings:
;; {
;;   "key": "ctrl+alt+j ctrl+alt+x",
;;   "command": "joyride.runCode",
;;   "args": "(do (require '[my-ns] :reload) (my-ns/my-function!+))"
;; },
```

The `:reload` ensures the latest code is executed, enhancing the development workflow.