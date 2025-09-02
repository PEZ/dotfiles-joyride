# Grouped Menu Enhancement Plan for Prompt Sync

You are tasked with enhancing the prompt_sync.cljs Joyride script to implement hierarchical grouping by original status with embedded bulk actions and stable ordering.

## Problem Statement
The current flat menu structure becomes unwieldy with many files and lacks workflow efficiency:
- **Unorganized Menu**: Instructions appear in arbitrary order making progress tracking difficult
- **No Visual Grouping**: Mixed statuses create cognitive overhead
- **Inefficient Bulk Operations**: Bulk sync actions only available from global status item

## Solution Vision
Transform the current flat menu into a **hierarchical, grouped structure** that:
1. **Maintains Stable Positioning**: Items grouped by original status, never jump around
2. **Provides Visual Clarity**: Clear section headers separate different categories
3. **Enables Efficient Workflows**: Bulk actions embedded directly in relevant sections
4. **Preserves Existing Functionality**: All current features work within the new structure

## Target Menu Structure
```
[Global Status Summary]

[Section Header: Originally Conflicting (2 files)]
├── conflict-file1.instruction.md
└── conflict-file2.prompt.md

[Section Header: Originally Missing in Stable (3 files)]
├── [Sync All to Stable Button] (if unresolved items exist)
├── missing-stable1.chatmode.md
├── missing-stable2.instruction.md (resolved)
└── missing-stable3.prompt.md

[Section Header: Originally Missing in Insiders (1 file)]
├── [Sync All to Insiders Button] (if unresolved items exist)
└── missing-insiders1.instruction.md

[Section Header: Originally Identical (5 files)]
├── identical1.prompt.md
├── identical2.instruction.md
└── ...
```

## Required Changes

### 1. Data Structure Enhancement
**Current**: Instructions only track current status
```clojure
{:instruction/filename "example.md"
 :instruction/status :conflict
 :instruction/instruction-type :instruction.type/prompt}
```

**Enhanced**: Add original status tracking
```clojure
{:instruction/filename "example.md"
 :instruction/original-status :conflict     ; Never changes - for grouping
 :instruction/status :resolved              ; Can change during resolution
 :instruction/instruction-type :instruction.type/prompt}
```

### 2. Menu Item Type System
**Current**: Two item types (status item, file item)

**Enhanced**: Five item types with different behaviors
```clojure
;; New item types with metadata
#js {:label "Section Header Text"
     :itemType "section-header"
     :isSelectable false}

#js {:label "Sync All to Stable"
     :itemType "bulk-action"
     :bulkAction :sync-all-to-stable
     :targetCount 3}

#js {:label "filename.md"
     :itemType "file"
     :fileInfo #js {...}}
```

### 3. Hierarchical Menu Construction
**Function Signature**:
```clojure
(defn create-grouped-menu-items
  "Creates hierarchical menu structure grouped by original status"
  [instructions]
  ;; Returns flat array suitable for VS Code QuickPick
  ;; with logical grouping and embedded actions
  )
```

**Grouping Logic**:
- Group by `:instruction/original-status`
- Sort groups by priority: `:conflict` → `:missing-in-stable` → `:missing-in-insiders` → `:identical`
- Insert section headers before each group
- Insert bulk action buttons for missing file groups (when applicable)
- Preserve file order within groups

### 4. Enhanced Selection Handling
**Current**: Binary handler (status vs file)

**Enhanced**: Multi-type handler
```clojure
(defn handle-item-selection [selected-item all-instructions dirs]
  (case (.-itemType selected-item)
    "section-header"  :no-op
    "bulk-action"     (handle-bulk-action selected-item all-instructions dirs)
    "file"            (handle-file-selection selected-item all-instructions dirs)
    "status"          (handle-status-selection selected-item all-instructions dirs)))
```

### 5. Bulk Action Integration
**Current**: Bulk actions only in separate status menu

**Enhanced**: Embedded bulk actions with smart visibility
- Show "Sync All to Stable" button when unresolved `:missing-in-stable` files exist
- Show "Sync All to Insiders" button when unresolved `:missing-in-insiders` files exist
- Hide buttons when all files in section are resolved
- Maintain existing bulk operation logic, just change the trigger location

### 6. Active Item Preservation
**Enhancement**: Track last active item by:
- Original status group
- Position within group
- Restore focus after bulk operations
- Handle graceful fallback when target item no longer exists

## Implementation Strategy

### Phase 1: Data Structure Foundation
1. **Add Original Status Tracking**
   - Enhance `compare-directories!+` to set `:instruction/original-status`
   - Ensure original status never changes during resolution operations
   - Update test data generation to include original status

2. **Create Grouping Functions**
   - `group-by-original-status` - Pure function for instruction grouping
   - `sort-groups-by-priority` - Stable ordering logic
   - Test grouping logic interactively with sample data

### Phase 2: Menu Item Construction
1. **Item Type System**
   - `create-section-header-item` - Visual group separators
   - `create-bulk-action-item` - Embedded sync buttons
   - `enhance-file-item` - Add original status context
   - `create-global-status-item` - Enhanced summary

2. **Hierarchical Assembly**
   - `create-grouped-menu-items` - Main assembly function
   - Test item order and structure with various data scenarios

### Phase 3: Selection Handling Enhancement
1. **Multi-Type Handler**
   - Extend existing selection logic for new item types
   - Preserve current file selection behavior
   - Add bulk action handling

2. **Active Item Management**
   - Track position within groups
   - Implement restoration logic
   - Handle edge cases (empty groups, resolved sections)

### Phase 4: Bulk Action Integration
1. **Visibility Logic**
   - Determine when to show/hide bulk buttons
   - Count unresolved items in each section
   - Update button text with current counts

2. **Operation Integration**
   - Reuse existing bulk operation functions
   - Update menu after bulk operations
   - Preserve user context

### Phase 5: Testing and Refinement
1. **Interactive Testing Protocol**
   - Test with various file status combinations
   - Verify stable ordering through resolution operations
   - Test bulk operations from embedded buttons
   - Validate active item preservation

2. **Edge Case Handling**
   - Empty sections
   - All files resolved in a category
   - Large numbers of files
   - Cancel/escape behavior

## Technical Specifications

### Original Status Categories (Priority Order)
1. `:conflict` - Files requiring manual resolution
2. `:missing-in-stable` - Files to sync from insiders → stable
3. `:missing-in-insiders` - Files to sync from stable → insiders
4. `:identical` - Files that are the same in both locations

### Section Header Format
```
"Originally Conflicting (2 files)"
"Originally Missing in Stable (3 files)"
"Originally Missing in Insiders (1 file)"
"Originally Identical (5 files)"
```

### Bulk Action Button Format
```
"Sync All to Stable (2 files)" - when unresolved missing-in-stable exist
"Sync All to Insiders (1 file)" - when unresolved missing-in-insiders exist
```

### Menu Item Metadata Structure
```clojure
;; Section headers
#js {:label "Originally Conflicting (2 files)"
     :iconPath (vscode/ThemeIcon. "symbol-namespace")
     :itemType "section-header"
     :isSelectable false
     :sectionInfo #js {:originalStatus "conflict" :totalCount 2}}

;; Bulk action buttons
#js {:label "Sync All to Stable (2 files)"
     :iconPath (vscode/ThemeIcon. "arrow-left")
     :itemType "bulk-action"
     :bulkAction "sync-all-to-stable"
     :targetFiles [...]}

;; File items (enhanced)
#js {:label "filename.md"
     :iconPath (type-icon)
     :itemType "file"
     :description "current-status-description"
     :detail "type • current-status • original-status"
     :fileInfo #js {:originalStatus "conflict" :currentStatus "resolved" ...}}
```

## Interactive Development Workflow

### REPL-First Development Protocol
1. **Test Data Creation**: Generate realistic instruction sets with various status combinations
2. **Function Development**: Build each component in isolation with immediate testing
3. **Integration Testing**: Combine components incrementally with validation
4. **UI Testing**: Human-in-the-loop testing for UX validation

### Testing Collaboration Pattern
For each UI enhancement:
1. **Describe Test**: Explain what functionality is being tested
2. **Set Expectations**: Tell the human what should happen
3. **Execute Test**: Run the code and show the UI
4. **Request Feedback**: Pause for human validation and input
5. **Iterate**: Adjust based on feedback

### Interactive Testing Examples
```clojure
;; Test grouping logic with sample data
(def sample-mixed-status [...])
(group-by-original-status sample-mixed-status)
;; Expected: Map with 4 groups in priority order

;; Test menu item creation
(def grouped-items (create-grouped-menu-items sample-mixed-status))
(count grouped-items) ;; Should include headers + bulk actions + files
(map #(.-itemType %) grouped-items) ;; Should show item type sequence

;; Test bulk action visibility
(needs-bulk-sync-button? :missing-in-stable sample-instructions)
;; Expected: true/false based on unresolved count
```

## Success Criteria
- [ ] Menu items grouped by original status in fixed order
- [ ] Section headers clearly separate different categories
- [ ] Bulk action buttons appear only when relevant
- [ ] Active item preservation works across operations
- [ ] All existing functionality preserved
- [ ] UI feels responsive and predictable
- [ ] No items jump around during resolution operations
- [ ] Bulk operations work from embedded buttons
- [ ] Visual hierarchy improves workflow efficiency

## Deliverables
1. Enhanced data structure with original status tracking
2. Hierarchical menu item construction system
3. Multi-type selection handling
4. Embedded bulk action integration
5. Active item preservation logic
6. Comprehensive interactive testing
7. Documentation of new menu structure

## Development Philosophy
- **REPL-driven development**: Test every component interactively before file modification
- **Human collaboration**: Use human feedback for UX validation at each step
- **Incremental enhancement**: Build on existing functionality rather than rewriting
- **Data-oriented design**: Prefer pure functions and explicit data transformations
- **Functional composition**: Small, testable functions that compose into larger behaviors
