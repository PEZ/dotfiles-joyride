# Prompt Sync Data Structure Refactoring Plan

## Overview

This document outlines the refactoring plan for `prompt_sync.cljs` to transform the current asymmetric data structure into a symmetric, instruction-centric model. This is a **Joyride script** that leverages ClojureScript in VS Code's Extension Host environment, enabling interactive programming throughout the refactoring process.

## Context: Joyride Interactive Programming

**IMPORTANT FOR AGENTS**: This is a Joyride project! Apply the following principles:

1. **Use Interactive Programming** - Leverage the REPL extensively to explore, test, and validate changes
2. **Joyride Copilot Instructions Apply** - Follow standard Joyride development practices
3. **Leverage Todo List Tool** - Use the `manage_todo_list` tool frequently for task tracking and visibility
4. **Test in the Live System** - Use `joyride_evaluate_code` to test functions in VS Code's actual environment

## Problem Statement

### Current Asymmetric Structure (PROBLEMATIC)

The current data structure privileges stable files at the root level while nesting insiders data:

```clojure
;; Current problematic shape
{:prompt-sync.file/filename "test.md"           ; ← Stable data at root
 :prompt-sync.file/path "/stable/test.md"       ; ← Stable data at root
 :prompt-sync.file/content "stable content"     ; ← Stable data at root
 :prompt-sync.file/insiders-file               ; ← Insiders nested!
 {:prompt-sync.file/filename "test.md"
  :prompt-sync.file/path "/insiders/test.md"
  :prompt-sync.file/content "insiders content"}
 :prompt-sync.file/status :conflict}
```

**Problems with this approach:**
- **Semantic confusion**: Implies stable files are "primary"
- **Access asymmetry**: `(:prompt-sync.file/content file)` vs `(-> file :prompt-sync.file/insiders-file :prompt-sync.file/content)`
- **Conceptual mismatch**: We're modeling instruction pairs, not "stable files with optional insiders variants"

### Target Symmetric Structure (BETTER)

```clojure
;; Better symmetric shape
{:instruction/filename "test.md"                ; ← Instruction metadata at root
 :instruction/instruction-type :prompt-sync.type/instruction
 :instruction/status :conflict                  ; ← Instruction metadata at root
 :instruction/action-needed :resolve
 :instruction/original-status :original/conflict
 :instruction/stable                           ; ← Balanced children
 {:location/path "/stable/test.md"
  :location/uri "file:///stable/test.md"
  :location/content "stable content"}
 :instruction/insiders                         ; ← Balanced children
 {:location/path "/insiders/test.md"
  :location/uri "file:///insiders/test.md"
  :location/content "insiders content"}}
```

**Benefits of this approach:**
- **Semantic clarity**: Root represents the instruction as a logical unit
- **Access symmetry**: Both locations accessed equally
- **Conceptual accuracy**: Models what we actually have - instructions in two environments

## Interactive Programming Workflow for Agents

### Phase 1: Exploration & Planning
```clojure
;; Use Joyride REPL to understand current structure
(in-ns 'prompt-sync)
(get-user-prompts-dirs {:prompt-sync/test-mode? true})

;; Examine test data to understand shapes
test-files

;; Create sample structures to validate the new design
(def sample-instruction {...})
```

### Phase 2: Function-by-Function Refactoring
Use the todo list tool to track progress through each function:

1. **Update todo status to 'in-progress' before starting each function**
2. **Test each change in the REPL before applying**
3. **Mark todo as 'completed' immediately after successful change**
4. **Update user with brief progress notes**

### Phase 3: Validation & Testing
```clojure
;; Test the refactored functions in the live environment
(main-test)

;; Validate new data structures work correctly
(def test-result (compare-directories!+ {...}))
```

## Detailed Refactoring Tasks

### 1. Update Namespace Keywords

**Current:** `:prompt-sync.file/*` keywords
**Target:** `:instruction/*` and `:location/*` namespaces

**Changes needed:**
- `:prompt-sync.file/filename` → `:instruction/filename`
- `:prompt-sync.file/status` → `:instruction/status`
- `:prompt-sync.file/file-type` → `:instruction/instruction-type`
- `:prompt-sync.file/path` → `:location/path`
- `:prompt-sync.file/uri` → `:location/uri`
- `:prompt-sync.file/content` → `:location/content`

### 2. Transform `scan-directory!+`

**Current output:**
```clojure
{:prompt-sync.file/filename filename
 :prompt-sync.file/path (path/join dir-path filename)
 :prompt-sync.file/uri (vscode/Uri.file ...)
 :prompt-sync.file/file-type (classify-file-type filename)}
```

**Target output:**
```clojure
{:location/path (path/join dir-path filename)
 :location/uri (vscode/Uri.file ...)
 ;; filename and instruction-type moved to instruction level
 :location/filename filename  ; temporary, for later merging
 :location/instruction-type (classify-instruction-type filename)}
```

### 3. Rewrite `compare-directories!+` (MAJOR CHANGE)

This is the core function creating the asymmetric structure. Needs complete rewrite:

**Current logic:**
```clojure
;; File in both directories
(merge stable-file
       {:prompt-sync.file/insiders-file insiders-file}
       {:prompt-sync.file/status ...})
```

**Target logic:**
```clojure
;; File in both directories
{:instruction/filename filename
 :instruction/instruction-type (get-instruction-type-from-either)
 :instruction/status (compare-contents stable insiders)
 :instruction/stable stable-location
 :instruction/insiders insiders-location}
```

### 4. Update UI Functions

Functions needing access path updates:

**`show-diff-preview!+`:**
- Current: `{:prompt-sync.file/uri uri :prompt-sync.file/insiders-file {...}}`
- Target: `{:instruction/stable {:location/uri ...} :instruction/insiders {:location/uri ...}}`

**`create-picker-item`:**
- Current: `(:prompt-sync.file/filename file)`
- Target: `(:instruction/filename instruction)`

**`show-file-preview!+`:**
- Current: `(:prompt-sync.file/uri file-info)`
- Target: `(-> instruction :instruction/stable :location/uri)` or `(-> instruction :instruction/insiders :location/uri)`

### 5. Update File Operation Functions

**`resolve-conflict!+`:**
- Current: `{:prompt-sync.file/uri stable-uri :prompt-sync.file/insiders-file {...}}`
- Target: `{:instruction/stable {:location/uri stable-uri} :instruction/insiders {:location/uri insiders-uri}}`

**`copy-missing-files!+`:**
- Current: `(:prompt-sync.file/uri file-info)`
- Target: Determine which location (stable or insiders) to copy from based on instruction status

### 6. Update Status Management

**Current status fields:**
- `:prompt-sync.file/status`
- `:prompt-sync.file/action-needed`
- `:prompt-sync.file/original-status`

**Target status fields:**
- `:instruction/status`
- `:instruction/action-needed`
- `:instruction/original-status`

## Interactive Programming Guidelines for Agents

### REPL-Driven Development Process

1. **Before any file editing:**
   ```clojure
   ;; Load the current namespace
   (in-ns 'prompt-sync)

   ;; Examine current data shapes
   (def sample-data (first compared))
   sample-data
   ```

2. **Test new function designs:**
   ```clojure
   ;; Test new data structure transformations
   (defn new-compare-directories!+ [...]
     ;; new implementation
     )

   ;; Test with sample data
   (new-compare-directories!+ test-dirs)
   ```

3. **Validate each change:**
   ```clojure
   ;; After each function update, test it works
   (main-test)
   ```

### Todo List Management

**Critical workflow:**
1. Mark todo as 'in-progress' before starting
2. Complete the work
3. Mark as 'completed' immediately
4. Brief progress update to user
5. Move to next todo

**Example:**
```clojure
;; Update todo list
(manage_todo_list {:operation "write"
                   :todoList [...updated list with status changes...]})
```

### Testing Strategy

1. **Unit-level testing:** Test individual functions in REPL
2. **Integration testing:** Run `main-test` to validate full flow
3. **Data shape validation:** Inspect intermediate results
4. **UI testing:** See UI Testing Process below

### UI Testing Process

With UI testing the human developer is the source of truth together with the REPL. Follow this process:

1. Think about what should be tested
2. Summarize the test and expectations to the human
3. Evaluate the test using `awaitResult: true`
4. When the evaluation tool use returns:
   1. Examine any results from the REPL
   2. Ask the human for feedback (using the chat, tool use here risks messing with the UI)
   3. Stop and listen
5. Rinse and repeat

## Migration Strategy

### Option 1: Big Bang Refactor
- Transform all functions at once
- Requires careful coordination
- Higher risk but faster completion

### Option 2: Incremental with Adapters
- Create transformation functions between old/new formats
- Migrate functions one at a time
- Lower risk, longer timeline

**Recommended:** Option 1 with extensive REPL testing, given the interactive programming capabilities.

## Joyride-Specific Considerations

### VS Code Integration
- Functions interact with VS Code APIs (`vscode/workspace.fs`, `vscode/window.showQuickPick`)
- Test in actual VS Code environment, not just REPL
- Use `awaitResult: true` for async operations when exploring

### Extension Context
- Functions use `joyride/extension-context` for path resolution
- Test directory detection logic with actual VS Code installations

### Error Handling
- Maintain existing error handling patterns
- VS Code UI feedback remains important

## Success Criteria

### Functional Requirements
- [ ] All existing functionality preserved
- [ ] Data structure is symmetric and instruction-centric
- [ ] UI interactions work identically
- [ ] File operations complete successfully
- [ ] Test mode functions correctly

### Code Quality Requirements
- [ ] Consistent namespace keyword usage
- [ ] Clear semantic meaning in data structures
- [ ] Balanced access patterns for stable/insiders data
- [ ] Maintainable and extensible design

### Testing Requirements
- [ ] All functions tested in REPL
- [ ] Integration test (`main-test`) passes
- [ ] Manual UI testing successful
- [ ] Edge cases handled (missing directories, empty files, etc.)

## Next Steps for Implementation

1. **Start with namespace keyword updates** (lowest risk)
2. **Transform `scan-directory!+`** (moderate impact)
3. **Rewrite `compare-directories!+`** (highest impact - test extensively)
4. **Update UI functions** (test with actual VS Code UI)
5. **Update file operations** (test with actual file system operations)
6. **Integration testing and validation**

## Notes for Future Agents

- This refactoring improves both semantic clarity and functional elegance
- The symmetric structure makes the code more maintainable
- Interactive programming with Joyride is your superpower - use it!
- Keep the todo list updated for visibility and progress tracking
- Test early, test often in the actual VS Code environment