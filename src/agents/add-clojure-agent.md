#  Interactive Clojure Agent Plan

Goal: Create an interactive-programming focused agent (`interactively-do!+`) that guides REPL-first TDD workflows, stays in the REPL until verification, then uses structural editing tools to commit implementations to files.

## Configuration defaults

- **Model**: `claude-haiku-4.5`
- **Max turns**: `30`
- **Instructions**: `:instructions-selector` (automatically selects relevant instruction files)
- **Tools enabled**: Complete Clojure REPL, Backseat Driver, and Joyride toolset (excluding `human_intelligence`)

## Tool suite

```clojure
["clojure_evaluate_code"
 "clojure_symbol_info"
 "clojuredocs_info"
 "clojure_repl_output_log"
 "clojure_balance_brackets"
 "replace_top_level_form"
 "insert_top_level_form"
 "clojure_create_file"
 "clojure_append_code"
 "joyride_evaluate_code"
 "copilot_searchCodebase"
 "copilot_findFiles"
 "copilot_findTextInFiles"
 "copilot_readFile"
 "copilot_listDirectory"
 "copilot_getErrors"
 "copilot_readProjectStructure"
 "copilot_getChangedFiles"
 "copilot_getDocInfo"
 "copilot_githubRepo"]
```

## Workflow principles

The agent should emphasize:
1. **REPL-first development** - develop and test solutions in the REPL before writing to files
2. **TDD in the REPL** - define tests with `clojure.test` and develop incrementally
3. **Subexpression evaluation** - test building blocks, avoid `println` debugging
4. **Verification before commit** - only use structural editing tools after REPL verification
5. **Structural editing** - prefer `replace_top_level_form`, `insert_top_level_form`, `clojure_create_file` over string replacement
6. **Tool awareness** - Agent has access to structural editing tools but should use them **only for reading their documentation**, not for making file changes

## Agent deliverable format

The agent should produce a structured report to Copilot containing:

### 1. Achievement summary
- Clear description of what has been implemented
- Key design decisions and architectural choices
- Any deviations from original requirements with rationale

### 2. REPL state reconstruction
- Complete sequence of REPL evaluations needed to recreate the current working state
- Includes all namespace requires, function definitions, test definitions, and data setup
- Ordered so evaluations can be run top-to-bottom to restore state after REPL restart

### 3. Verification instructions for Copilot
- Specific REPL expressions Copilot should evaluate to verify critical functionality
- Expected results for each verification step
- Edge cases or boundary conditions to check

### 4. User verification handoff
- Instructions for Copilot to present achievements to user in clear, non-technical summary
- Specific prompts for user to test the implementation (e.g., "Try X and verify Y happens")
- **Explicit instruction to STOP and wait for user feedback** before proceeding
- Clear statement: "Do not proceed with file commits until user confirms the implementation works correctly"

### 5. Structural editing guide for Copilot
- Reminder of key structural editing principles:
  - Plan all edits before executing (list which forms in which files)
  - Work bottom-to-top within files to avoid line number shifts
  - Use `replace_top_level_form`, `insert_top_level_form`, `clojure_create_file` as appropriate
  - Read the tool documentation if uncertain about usage
- Reference to the complete REPL state (#2) that will be needed after edits
- Note that exact editing details will depend on user feedback and cannot be predetermined

## Implementation checklist

- [ ] Extract shared helpers from `agents.memory-keeper` into `agents.agent-util` without changing behaviour
  - [ ] Path helpers: `user-data-instructions-path`, `workspace-instructions-path`
  - [ ] File operations: `read-existing-file!+`, `list-instruction-files!+`, `build-file-descriptions-map!+`, `format-description-listing`
  - [ ] Utilities: `normalize-scope`, `file-path->uri-string`, `extract-description-from-content`
- [ ] Update `agents.memory-keeper` to use `agent-util` while preserving existing defaults
  - [ ] Replace local implementations with `require` from `agent-util`
  - [ ] Verify existing tests still pass
  - [ ] Confirm `record-memory!+` behaviour unchanged
- [ ] Implement `agents.clojure-interactive/interactively-do!+`
  - [ ] Core function with conversation-data map parameter
  - [ ] Default model: `claude-haiku-4.5`
  - [ ] Default max-turns: `30`
  - [ ] Default instructions: `:instructions-selector`
  - [ ] Default tool-ids: full suite from list above
  - [ ] Build interactive-programming goal prompt template that specifies the deliverable format
  - [ ] Goal prompt must instruct agent to produce structured report with:
    - Achievement summary
    - Complete REPL state reconstruction sequence
    - Verification instructions for Copilot
    - User verification handoff (with explicit STOP instruction)
    - Structural editing guide for Copilot (principles only, not detailed plan)
  - [ ] Goal prompt must clarify: structural editing tools available for documentation reading only, not for execution
  - [ ] Support override options (model-id, max-turns, tool-ids, instructions, title, progress-callback, goal)
  - [ ] Call `agent-orchestrator/autonomous-conversation!+` with merged config
  - [ ] Parse agent's report from response
  - [ ] Return structured result containing the report sections
- [ ] Add tests covering `agent-util` helpers and the new agent's behaviour
  - [ ] Test `agent-util` path helpers return expected locations
  - [ ] Test `normalize-scope` handles keywords and strings
  - [ ] Test `interactively-do!+` goal prompt contains REPL/TDD guidance
  - [ ] Test goal prompt specifies all required report sections
  - [ ] Test defaults apply correctly (model, max-turns, instructions, tools)
  - [ ] Test overrides work as expected
  - [ ] Test report parsing extracts all sections correctly
- [ ] Document the agent in `src/agents/README.md`
  - [ ] Usage examples showing basic invocation
  - [ ] Explanation of REPL-first workflow
  - [ ] Description of the structured report format agent produces
  - [ ] Explanation of user verification handoff workflow
  - [ ] Note about structural editing for finalization happening after user approval
  - [ ] Configuration override examples
  - [ ] Example report showing all five sections

## Files to create/modify

### New files
- `src/agents/agent_util.cljs` - shared helper functions
- `src/agents/clojure_interactive.cljs` - interactive agent implementation
- `src/test/agents/agent_util_test.cljs` - tests for shared helpers
- `src/test/agents/clojure_interactive_test.cljs` - tests for interactive agent

### Modified files
- `src/agents/memory_keeper.cljs` - refactor to use `agent-util`
- `src/agents/README.md` - document both agents and their workflows
