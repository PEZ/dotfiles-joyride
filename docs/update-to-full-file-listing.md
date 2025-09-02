# Implement Full File Listing Menu for Prompt Sync

You are tasked with updating the prompt_sync.cljs Joyride script to show ALL processed files in a  listing menu, not just conflicts.

NB: This should be a pretty minimal change.

## Current State Analysis
The script currently:
- Auto-copies missing files between VS Code stable/insiders prompt directories
- Shows only conflicting files in `show-conflict-picker!+` function
- Handles conflict resolution with diff preview and resolution options

## Required Changes

### 1. Data Structure Enhancement
- Enhance `compare-directories!+` to return complete file status categorization
- Include file metadata for menu display directly in the analysis result
- Simplify `copy-missing-files!+` to operate on the enhanced analysis structure

### 2. Create Unified File Listing Menu
Transform `show-conflict-picker!+` into `show-files-picker!+`:

**Menu Items for ALL File Types:**
- **Copied Stable → Insiders**: Show with right arrow, type icon, and "Copied Stable → Insiders" description
- **Copied Stable ← Insiders**: Show with left arrow, type icon, and "Copied Stable ← Insiders" description
- **Identical Files**: Show with type icon, and "identical" description
- **Conflicting Files**: Current styling and behaviour with conflict description

### 3. Interaction Behavior Implementation
- **Non-conflicting files**: On focus/activation → open file in VS Code preview mode (use `vscode.open` with preview option)
- **Non-conflicting files**: On selection → do nothing (keep menu open)
- **Conflicting files**: Current behavior (show diff + resolution menu)

NB: “Current beavior” for conflicting files is important. The changes are in that the menu contains additional file statuses which need different behaviour (file preview on focus, no-op on selection), and different messaging.

### 4. Visual Consistency Requirements
- ALL items must have type icons: instruction (list-ordered), prompt (chevron-right), chatmode (color-mode)
- Use directional arrows (in the labels) for copied files: → and ←
- Consistent description format across all file types

### 5. State Management
- Add menu state persistence to remember last active item
- Restore focus to previously selected item when menu reopens
- Handle state across conflict resolution operations

## Implementation Approach

1. **First**: Enhance `compare-directories!+` to return complete file categorization with metadata
2. **Second**: Create unified menu item factory function for all file types
3. **Third**: Update menu interaction handlers for different behaviors
4. **Fourth**: Implement preview file opening for non-conflicting files
5. **Fifth**: Add menu state persistence logic

## Technical Specifications

### File Status Categories
```clojure
{:prompt-sync.result/missing-in-stable []     ; files that exist only in insiders (will copy to stable)
 :prompt-sync.result/missing-in-insiders []   ; files that exist only in stable (will copy to insiders)
 :prompt-sync.result/identical []             ; files identical in both directories
 :prompt-sync.result/conflicts []             ; files needing manual resolution
 ;; Enhanced for menu display:
 :prompt-sync.result/copied-to-stable []      ; files copied insiders → stable (derived from missing-in-stable)
 :prompt-sync.result/copied-to-insiders []    ; files copied stable → insiders (derived from missing-in-insiders)
}
```

### Menu Item Structure
```clojure
#js {:label "filename.md"
     :iconPath (type-icon)
     :description "status-description"
     :detail "additional-context"
     :file-type :file-type
     :file-status :file-status}
```

### Preview Opening
Use VS Code command for preview mode:
```clojure
(vscode/commands.executeCommand "vscode.open" file-uri #js {:preview true})
```

(The preview should not steal focus, which is missing from that call.)

## Deliverables
1. Updated data structure tracking all file operations
2. Unified file listing menu showing all processed files
3. Appropriate interaction behaviors for each file status
4. Menu state persistence across operations
5. Proper visual indicators and icons for all file types


## Workflow

* Focus on REPL-driven development - test each component interactively before file modification.
* When defining new versions of existing functions, name them the same as the old function, unless semantics has changed to warrent a name change.
* When testing the UI and the flow, remember that the human (me) is your eyes and ears, and the tester:
  1. Describe what you are testing
  2. Tell the me what you want me to do and what to expect to happen
  3. Exacute the test
  4. Yield the chat waiting for my feedback
* Plan your work, use the todo list intelligently