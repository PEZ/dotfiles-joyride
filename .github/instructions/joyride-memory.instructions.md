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
