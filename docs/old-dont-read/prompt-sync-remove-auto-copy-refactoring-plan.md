# Prompt Sync Refactoring Plan: Remove Auto-Copy, Add User Control

## Overview

This refactoring removes automatic file copying and puts users in full control of synchronization decisions. The changes maintain the functional, data-oriented design while adding resolution menus for missing files and bulk operations.

## Current vs Target Behavior

### Current (Auto-Copy)
1. `compare-directories!+` identifies missing files (`:missing-in-stable`, `:missing-in-insiders`)
2. `copy-missing-files!+` **automatically copies** without user choice
3. Status changes to `:copied`, action-needed becomes `:none`
4. Only conflicts reach the user picker

### Target (User Control)
1. `compare-directories!+` identifies missing files (same as before)
2. **Skip auto-copy entirely** - all files go to picker
3. Missing files get resolution menus like conflicts
4. Status item gets bulk operations menu
5. Users explicitly choose all synchronization actions

## Interactive Programming Methodology

**CRITICAL**: Use REPL-first development throughout this refactoring:

1. **Test current behavior** - Run functions with sample data in REPL
2. **Develop changes incrementally** - Build each function step-by-step
3. **Validate at each step** - Ensure no regressions
4. **Use todo list extensively** - Track progress and maintain focus

### REPL Development Pattern
```clojure
;; Always start with namespace and current state inspection
(in-ns 'prompt-sync)

;; Test current function behavior
(def sample-instructions [...])
(original-function sample-instructions)

;; Develop new version step by step
(defn new-function-v1 [data]
  ;; Initial implementation
  )

;; Test and iterate
(new-function-v1 sample-instructions)
;; => Check results, refine, repeat

;; Only update files when REPL version is working perfectly
```

## Detailed Refactoring Tasks

### Phase 1: Data Structure Changes
- **Task 1.1**: Update `:action-needed` values for missing files
  - Change from `:copy-to-*` to `:resolve` (like conflicts)
  - Test with sample instruction data in REPL

- **Task 1.2**: Add new action keywords for missing file resolution
  ```clojure
  ;; Individual missing file actions
  :prompt-sync.action/sync-to-stable
  :prompt-sync.action/sync-to-insiders

  ;; Bulk operations
  :prompt-sync.action/sync-all-missing
  :prompt-sync.action/sync-all-missing-to-stable
  :prompt-sync.action/sync-all-missing-to-insiders
  :prompt-sync.action/skip-all-missing
  :prompt-sync.action/skip-all-missing-in-stable
  :prompt-sync.action/skip-all-missing-in-insiders
  ```

### Phase 2: Remove Auto-Copy Logic
- **Task 2.1**: Eliminate `copy-missing-files!+` from `sync-prompts!+`
  - Test current flow in REPL first
  - Develop new sync flow without auto-copy
  - Verify all instruction statuses preserved correctly

- **Task 2.2**: Update `create-picker-item` status display
  - Remove `:copied` status references (from auto-copy)
  - Add proper display for `:missing-in-*` statuses
  - Test picker item creation with new data

### Phase 3: Extend Resolution Menus
- **Task 3.1**: Enhance `show-resolution-menu!+` for missing files
  - Current: Only handles conflicts
  - Target: Handle conflicts + missing files with appropriate actions
  - Test with different instruction types in REPL

- **Task 3.2**: Update `resolve-conflict!+` to handle all resolution types
  - Rename to `resolve-instruction!+` (broader scope)
  - Add cases for new sync actions
  - Test file copying operations for missing files

### Phase 4: Add Bulk Operations
- **Task 4.1**: Create status item bulk resolution menu
  - New function: `show-status-resolution-menu!+`
  - Offer bulk sync and skip operations
  - Test menu creation and action selection

- **Task 4.2**: Implement bulk operation handlers
  - New function: `apply-bulk-operation!+`
  - Handle multiple instruction updates
  - Test with various instruction combinations

- **Task 4.3**: Integrate bulk operations into picker flow
  - Update `show-all-files-picker!+` to handle status item selection
  - Route to bulk operation flow when status item chosen
  - Test full picker interaction flow

### Phase 5: Update Picker Behavior
- **Task 5.1**: Extend picker to handle missing file resolution
  - Currently: Only conflicts get resolution menu
  - Target: Missing files also get resolution menu
  - Test picker selection logic with new instruction types

- **Task 5.2**: Update picker descriptions and placeholders
  - Change from "Select conflicts to resolve" to include missing files
  - Update status display to reflect new categories
  - Test visual presentation of all instruction types

### Phase 6: Integration and Testing
- **Task 6.1**: Update `main-menu-loop!+` for new resolution types
  - Ensure loop handles missing file resolutions
  - Test with various resolution scenarios
  - Verify proper state transitions

- **Task 6.2**: Test complete end-to-end flow
  - Create comprehensive test scenarios in REPL
  - Test all resolution paths (individual + bulk)
  - Verify no auto-copying occurs

## Implementation Strategy

### 1. Setup Todo List
Use `manage_todo_list` extensively:
- Break down each phase into specific, testable tasks
- Mark tasks in-progress before starting work
- Mark completed immediately after REPL validation
- Track dependencies between tasks

### 2. REPL-First Development
For each function modification:
```clojure
;; 1. Read and understand current function
(source function-name)

;; 2. Create test data
(def test-instructions [...])

;; 3. Test current behavior
(function-name test-instructions)

;; 4. Develop new version incrementally
(defn function-name-v2 [args]
  ;; New implementation built step by step
  )

;; 5. Validate thoroughly
(function-name-v2 test-instructions)
;; Test edge cases, empty data, etc.

;; 6. Only then update the file
```

### 3. Incremental Validation
After each task:
- Test the modified function in isolation
- Test integration with dependent functions
- Run test scenarios to ensure no regressions
- Update todo list with progress

## Key Design Principles

### Data-Oriented Approach
- Keep instruction data structures flat and clear
- Use domain-specific namespaced keywords
- Prefer pure functions that transform data

### Functional Pipeline
- Maintain clear data flow: compare → present → resolve → update
- Each function should have a single, clear responsibility
- Use threading macros for clear transformation pipelines

### User Agency
- No automatic operations - everything requires user choice
- Clear visual feedback about what each action will do
- Bulk operations for efficiency without losing control

## Success Criteria

### Functional Requirements
- [ ] No automatic file copying occurs
- [ ] Missing files get resolution menus
- [ ] Status item offers bulk operations
- [ ] All resolution types work correctly
- [ ] Picker shows appropriate options for each instruction type

### Code Quality Requirements
- [ ] All functions developed and tested in REPL first
- [ ] No compilation warnings or errors
- [ ] Clear, self-documenting function names
- [ ] Consistent data structure patterns
- [ ] Comprehensive test coverage via REPL exploration

### User Experience Requirements
- [ ] Clear visual distinction between instruction types
- [ ] Intuitive resolution menu options
- [ ] Efficient bulk operations for common scenarios
- [ ] No surprising automatic behaviors

## Risk Mitigation

### Potential Issues
1. **Breaking existing picker flow** - Test thoroughly with current scenarios
2. **Data structure inconsistencies** - Validate all instruction transformations
3. **Missing edge cases** - Test with empty directories, single files, etc.

### Mitigation Strategies
1. **REPL validation at each step** - Catch issues early
2. **Incremental implementation** - Modify one function at a time
3. **Comprehensive test scenarios** - Cover all instruction type combinations
4. **Reversible changes** - Keep original functions until validation complete

## Notes for Implementation

- Use the existing `copy-file!+` function for actual file operations
- Maintain the current instruction data structure format
- Preserve the functional, promise-based async patterns
- Keep the UI consistent with VS Code design patterns
- Leverage domain-specific keywords for clear code

This refactoring transforms the prompt sync tool from an automatic system to a user-controlled one while maintaining all the existing functionality and improving user agency.