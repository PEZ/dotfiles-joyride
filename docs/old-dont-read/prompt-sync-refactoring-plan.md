# Prompt Sync Refactoring Plan

## Overview

The current `prompt_sync.cljs` implementation has several architectural and naming issues that need systematic refactoring. This document outlines a comprehensive plan to clean up the codebase while maintaining functionality.

## Current Issues

### 1. Function Shadowing (Critical)
- `:prompt-sync.file/name` shadows Clojure's `name` function
- `:prompt-sync.file/type` shadows Clojure's `type` function
- `:prompt-sync.conflict/type` shadows Clojure's `type` function
- `:prompt-sync.resolved/type` shadows Clojure's `type` function

**Impact**: Causes "K.call is not a function" errors when destructuring

### 2. Data Structure Inconsistency
- Mixed object types in vectors based on status
- Backward design: different shapes for different statuses
- Should be uniform file objects with status indicators

### 3. Non-Qualified Keys
- Test data uses bare keys (`:name`, `:content`, `:location`)
- Return values mix qualified and non-qualified keys
- Only VS Code API should use non-qualified keys

### 4. Magic Numbers
- `(= type 1)` for file type checking
- No semantic meaning, hard to understand

### 5. Mixed Status/Metadata Design
- Status field encodes multiple pieces of information
- `:resolved-skipped` mixes status + resolution
- `:copied-to-stable` mixes status + direction

## Refactoring Plan

### Phase 1: Fix Function Shadowing (Highest Priority)

**Files to change**: All files accessing these keys

**Changes**:
```clojure
;; Current (problematic)
:prompt-sync.file/name → :prompt-sync.file/filename
:prompt-sync.file/type → :prompt-sync.file/file-type
:prompt-sync.conflict/type → :prompt-sync.conflict/file-type
:prompt-sync.resolved/type → :prompt-sync.resolved/file-type
```

**Functions affected**:
- `scan-directory!+`
- `compare-directories!+`
- `copy-missing-files!+`
- `enhance-sync-result`
- `create-all-files-picker-item`
- `show-diff-preview!+`
- All test data access

### Phase 2: Separate Status from Metadata

**Goal**: Clean separation of concerns - status represents file state, not actions or directions

**Current Problem Analysis**:
The status field currently mixes three different semantic concepts:
- File state (`:conflict`, `:identical`)
- Copy direction (`:copied-to-stable`, `:copied-to-insiders`)
- Resolution actions (`:resolved-to-stable`, `:resolved-to-insiders`, `:resolution-skipped`)

**New structure**:
```clojure
{:prompt-sync.file/filename "example.md"
 :prompt-sync.file/file-type :instruction
 :prompt-sync.file/status :conflict          ; clean file state only
 :prompt-sync.file/stable-file {...}
 :prompt-sync.file/insiders-file {...}
 :prompt-sync.file/copy-direction :copied-to-insiders    ; when applicable
 :prompt-sync.file/resolution :resolution/skipped}       ; when resolved
```

**Status enum** (file state only):
- `:conflict` - has conflicts to resolve
- `:identical` - same content in both
- `:copied` - was missing, now copied
- `:resolved` - conflict was resolved

**Resolution enum** (separate field):
- `:resolution/choose-stable`
- `:resolution/choose-insiders`
- `:resolution/skipped`

**Copy direction enum** (unchanged, but separate field):
- `:copied-to-stable`
- `:copied-to-insiders`

**Implementation Steps**:

**Step 2.1: Update enhance-sync-result logic**
- Conflicts: `{:status :conflict}` (no copy-direction, no resolution)
- Missing files: `{:status :copied, :copy-direction :copied-to-stable}`
- Identical: `{:status :identical}` (no other fields)
- Resolved: `{:status :resolved, :resolution :resolution/choose-stable}`

**Step 2.2: Update sync-prompts!+ resolution tracking**
- Change resolution-status tracking from `:resolved-to-stable` to `:status :resolved, :resolution :resolution/choose-stable`
- Update the resolution state management in `handle-conflicts`

**Step 2.3: Refactor create-all-files-picker-item**
- Update description generation to use separated fields:
```clojure
(case status
  :conflict (str (name file-type) " • has conflicts")
  :identical "identical"
  :copied (case copy-direction
            :copied-to-insiders "Stable → Insiders"
            :copied-to-stable "Insiders → Stable")
  :resolved (case resolution
              :resolution/choose-stable "resolved • chose Stable"
              :resolution/choose-insiders "resolved • chose Insiders"
              :resolution/skipped "resolved • skipped"))
```

**Step 2.4: Test data structure consistency**
- Validate all file objects have consistent field sets
- Ensure UI displays correctly with separated concerns

### Phase 3: Uniform File Objects

**Goal**: All items in `all-files` have the same shape

**Functions to refactor**:
- `enhance-sync-result` - produce uniform objects
- `create-all-files-picker-item` - handle separated fields
- `show-all-files-picker!+` - work with uniform structure

### Phase 3B: Uniform Namespace (Major Architecture Win)

**Goal**: Eliminate namespace proliferation by using `:prompt-sync.file/` for all file-related data

**Current Problem**: Multiple namespaces for the same conceptual entity
- `:prompt-sync.conflict/filename` vs `:prompt-sync.resolved/filename` vs `:prompt-sync.file/filename`
- `:prompt-sync.conflict/stable-file` vs `:prompt-sync.resolved/stable-file`
- Same data, different namespaces based on lifecycle state

**Insight**: Different namespaces represent **lifecycle states** of the same entity (file comparison data), not different types of data.

**Uniform Approach**:
```clojure
;; All file comparison data uses :prompt-sync.file/ namespace
{:prompt-sync.file/filename "example.md"
 :prompt-sync.file/stable-file {...}
 :prompt-sync.file/insiders-file {...}
 :prompt-sync.file/file-type :instruction
 :prompt-sync.file/status :conflict           ; lifecycle state
 :prompt-sync.file/copy-direction nil         ; when applicable
 :prompt-sync.file/resolution nil}            ; when resolved
```

**Benefits**:
- **Massive code reduction**: Eliminates ~47 lines of transformation functions
- **Consistent destructuring**: `{:prompt-sync.file/keys [...]}` everywhere
- **Simpler state transitions**: Just `assoc` new status instead of complex transformations
- **Better semantic clarity**: All file comparison data uses same namespace
- **Easier testing**: Same structure to validate across all states

**Implementation**:

**Step 3B.1: Update data creation in `compare-directories!+`**
```clojure
;; Current
:prompt-sync.result/conflicts [{:prompt-sync.conflict/filename "..."
                                :prompt-sync.conflict/stable-file ...}]

;; New
:prompt-sync.result/conflicts [{:prompt-sync.file/filename "..."
                                :prompt-sync.file/stable-file ...
                                :prompt-sync.file/status :conflict}]
```

**Step 3B.2: Replace transformation functions with simple status assignment**
```clojure
;; Replace all 5 transformer functions with:
(defn add-file-status
  "Adds status and optional metadata to file data"
  [file-data status & {:keys [resolution copy-direction]}]
  (cond-> file-data
    true (assoc :prompt-sync.file/status status)
    resolution (assoc :prompt-sync.file/resolution resolution)
    copy-direction (assoc :prompt-sync.file/copy-direction copy-direction)))
```

**Step 3B.3: Simplify `enhance-sync-result`**
```clojure
;; Old: 15 lines with 5 different transformer calls
;; New: ~8 lines with simple status assignments
(concat
  (map #(add-file-status % :conflict) conflicts)
  (map #(add-file-status % :resolved :resolution (:action %)) resolved)
  (map #(add-file-status % :copied :copy-direction :copied-to-insiders) missing-in-insiders)
  ;; etc.
)
```

**Step 3B.4: Update all destructuring**
```clojure
;; All functions use same destructuring pattern
{:prompt-sync.file/keys [filename stable-file insiders-file file-type status]}
```

**Risk Assessment**: Medium
- Touches data creation and all consumers
- But conceptually simple change (just namespace unification)
- Easy to validate: same data, same behavior, cleaner code

### Phase 4: Qualify All Domain Keys

**Goal**: Qualify all domain-specific keys while keeping VS Code API and JavaScript interop keys bare

**Scope**: Only qualify keys that represent prompt-sync domain concepts, not external API parameters

**Changes needed**:

**Test data (`populate-test-files!+`)**:
```clojure
;; Current
{:name "file.md" :content "..." :location :stable-only
 :stable-content "..." :insiders-content "..."}

;; New
{:prompt-sync.file/filename "file.md"
 :prompt-sync.file/content "..."
 :prompt-sync.file/location :stable-only
 :prompt-sync.file/stable-content "..."
 :prompt-sync.file/insiders-content "..."}
```

**Return values**:
```clojure
;; copy-missing-files!+
;; Current: {:copied-from-stable 2 :copied-from-insiders 1}
;; New:
{:prompt-sync.result/copied-from-stable 2
 :prompt-sync.result/copied-from-insiders 1}

;; create-test-environment!+
;; Current: {:stable "/path" :insiders "/path"}
;; New:
{:prompt-sync.env/stable-dir "/path/to/stable"
 :prompt-sync.env/insiders-dir "/path/to/insiders"}

;; resolve-conflict!+
;; Current: {:action :choose-stable :filename "file.md" :success true}
;; New:
{:prompt-sync.resolution/choice :resolution/choose-stable  ; Note: not action/action
 :prompt-sync.file/filename "file.md"
 :prompt-sync.result/success true}
```

**Keys that should STAY bare** (external APIs):
- VS Code picker items: `:label`, `:description`, `:iconPath`, `:detail`
- JavaScript interop: any keys going to/from JS objects
- Clojure core concepts: `:keys` in destructuring
- Generic utilities: standard Clojure return patterns

**Implementation order**:
1. Update `populate-test-files!+` test data structure
2. Update `copy-missing-files!+` return keys
3. Update `create-test-environment!+` return keys
4. Update `resolve-conflict!+` return keys
5. Update any consumers of these return values

### Phase 5: Replace Magic Numbers

**Changes**:
```clojure
;; Add constants
(def ^:const VSCODE-FILE-TYPE 1)  ; Or use vscode/FileType.File if available

;; Update scan-directory!+
(filter (fn [[filename file-type]]
          (and (= file-type VSCODE-FILE-TYPE)
               (.endsWith filename ".md"))))
```

### Phase 6: Update UI Layer

**Functions to update**:
- `create-all-files-picker-item` - handle new separated status/resolution/copy-direction
- Description generation logic to use new fields
- Event handlers to work with uniform file objects

## Implementation Order

### Step 1: Constants and Naming (Low Risk)
1. Add VSCODE-FILE-TYPE constant
2. Fix magic number in `scan-directory!+`
3. Qualify return value keys in simple functions
**→ Validate: Test mode runs, file scanning works**

### Step 2: Core Data Structure (Medium Risk)
1. Update `scan-directory!+` to use `:prompt-sync.file/filename`
2. Update `compare-directories!+` to use new keys
3. Update `copy-missing-files!+` key access
**→ Validate: Full file comparison and copying works**

### Step 3: Enhanced Result Structure (High Risk)
1. Refactor `enhance-sync-result` to produce uniform objects with separated fields
2. Update resolution tracking to use new structure
3. Test with simple scenarios
**→ Validate: All file types appear correctly in picker**

### Step 4: UI Layer (High Risk)
1. Update `create-all-files-picker-item` for new structure
2. Update picker preview logic
3. Test full UI workflow
**→ Validate: Complete conflict resolution workflow works**

### Step 5: Test Data (Low Risk)
1. Update `populate-test-files!+` to use qualified keys
2. Update test file structure access
**→ Validate: Test scenarios create expected file states**

## Incremental Change Philosophy

**Core principle**: Each step must leave the system in a fully working state.

- **No partial implementations**: Complete each change entirely before moving on
- **No compatibility layers**: Update all consumers of changed data immediately
- **Fail fast**: If a step breaks something, fix it completely before continuing
- **Single responsibility**: Each step changes one concept at a time
- **Full validation**: Manual testing must pass 100% before next step
- **Interactive programming**: Develop solution in REPL first, then apply to files
- **Todo tracking**: Maintain active todo list to ensure no steps are missed and track progress

## Testing Strategy

### Unit Testing Approach
- Test each phase in isolation
- Create REPL test scenarios for each data transformation
- Validate that old and new structures produce same UI behavior

### Integration Testing
- Run full sync workflow after each phase
- Verify all file types display correctly
- Ensure conflict resolution still works
- Test copy operations

### Safety Measures
- Keep test mode functional throughout refactoring
- Use REPL to validate each transformation step
- **No backward compatibility**: Make one small change at a time, ensure it works completely, then move on
- **Interactive programming**: Use REPL-driven development throughout the refactoring process
- **Todo list management**: Agent should maintain active todo list to track progress and next steps
- **Manual testing**: AI+human co-op validation between all phases before continuing

## Success Criteria

### Functional
- [ ] All file operations work correctly
- [ ] UI displays all file types with correct statuses
- [ ] Conflict resolution workflow unchanged from user perspective
- [ ] Copy operations work for missing files
- [ ] Alphabetical sorting maintained

### Code Quality
- [ ] No function shadowing in destructuring
- [ ] All domain keys properly namespaced
- [ ] Uniform data structures throughout pipeline
- [ ] No magic numbers
- [ ] Clear separation of status/metadata concerns

### Maintainability
- [ ] Easy to add new file statuses
- [ ] Easy to add new resolution types
- [ ] Clear data flow through pipeline
- [ ] Self-documenting code with domain keywords

## Risk Assessment

**Low Risk**:
- Constants and magic number fixes
- Return value key qualification
- Test data structure updates

**Medium Risk**:
- Core data structure key changes
- Destructuring updates

**High Risk**:
- `enhance-sync-result` refactoring (complex logic)
- UI layer updates (user-facing changes)
- Resolution tracking changes

## Rollback Plan

Each phase should be completable in isolation. If issues arise:

1. **Phase 1-2**: Revert key naming changes, restore destructuring
2. **Phase 3**: Revert to old `enhance-sync-result` logic
3. **Phase 4**: Revert UI layer changes
4. **Phase 5**: Revert test data changes

Keep working versions of each function during refactoring to enable quick rollback.