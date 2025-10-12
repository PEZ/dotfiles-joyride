# Memory Agent Improvements

## Background

The [/remember prompt](https://github.com/github/awesome-copilot/blob/main/prompts/remember.prompt.md) enables users and Copilot to build a self-improving knowledge base by recording lessons learned into domain-organized memory instructions. It delivers stellar results with comprehensive context analysis, intelligent domain categorization, and high-quality memory authoring. However, it requires premium-tier models (Claude Sonnet 4/4.5, GPT-5-Codex), which means:
- Premium request costs
- Slower execution time
- Context pollution in the main task (the memory recording work happens in the same conversation)

## The Memory Recorder Solution

The memory recorder (Joyride implementation) aims to deliver comparable quality while addressing these limitations:
- **Free-tier models** - Uses Grok Code Fast 1 (free tier) instead of premium models
- **Isolated context** - Spawns a sub-agent that handles all memory authoring work separately, avoiding pollution of the main task focus and context
- **Minimal cognitive overhead** - Quick invocation, brief confirmation, back to work

The user and Copilot can quickly and easily turn mistakes and corrections from the chat session into Copilot instructions, as memories, that capture the desired patterns, code style, preferences, and workflows - with minimum token costs and minimal impact on the current task in terms of context use and focus.

## Related Documentation

### Code Files
- [`src/ai_workflow/memory_agent.cljs`](../src/ai_workflow/memory_agent.cljs) - Main memory agent implementation
  - `remember-prompt` function (line ~43) - Generates prompt for memory recording agent
  - `record-memory!+` function (line ~429) - Main API for recording memories
  - `clean-heading` function (line ~282) - Removes `##` prefix from headings
  - `trim-heading-from-content` function (line ~295) - Prevents duplicate headings
  - `append-memory-section` function (line ~313) - Appends memory to existing file
- [`src/test/ai_workflow/memory_agent_test.cljs`](../src/test/ai_workflow/memory_agent_test.cljs) - Test suite
- [`src/ai_workflow/agents.cljs`](../src/ai_workflow/agents.cljs) - Autonomous agent utility

### Prompt Files
- **Record Memory Prompt**: `vscode-userdata:/Users/pez/Library/Application Support/Code - Insiders/User/prompts/record-memory.prompt.md`
  - User-facing prompt for `/record-memory` command
  - Defines syntax and task flow
- **Instructions Base Directory** (Global): `~/Library/Application Support/Code - Insiders/User/prompts/`
  - Where global memory files are stored
  - Includes `clojure-memory.instructions.md`, `memory.instructions.md`, etc.
- **Instructions Base Directory** (Workspace): `.github/instructions/`
  - Where workspace-scoped memory files are stored

### Experiment Results
- [`docs/memory-agent-record-expermiments-2025-10-12.md`](./memory-agent-record-expermiments-2025-10-12.md) - Today's experiments
  - Three test runs with function definition order memory
  - Shows heading duplication issues (now fixed)
  - Different output structures across runs

### Memory Files (Examples)
- Global Clojure Memory: `~/Library/Application Support/Code - Insiders/User/prompts/clojure-memory.instructions.md`
- Universal Memory: `~/Library/Application Support/Code - Insiders/User/prompts/memory.instructions.md`
- Workspace Example: `.github/instructions/joyride-user-project.instructions.md`


## Observations

### Current Issues
- Memory content varies significantly between runs with same input
- Content structure inconsistent (sometimes detailed examples, sometimes brief)

### Current Focus

Improve predictability and quality of recorded memories to ensure consistent, useful documentation that helps AI agents learn from past mistakes and patterns.

## Todo

### Better Memory Quality

#### Quality Criteria (Defined)
- [x] **What makes a memory useful?**
  - Helps the agent avoid pitfalls and time sinks by guiding it to the right patterns from the start
  - Prevents the agent from even considering wrong paths
  - Provides clear, actionable guidance

- [x] **What level of detail is appropriate?**
  - **Succinct over comprehensive** - Many small, targeted memories beat few comprehensive ones
  - Trade-off: accuracy/detail vs context consumption
  - Aim for scannable, focused entries

- [x] **How should code examples be structured?**
  - **Short, to the point, general**
  - Code comments can carry commentary burden
  - Pattern: Heading (scannable) → Brief "because X, do Y" → Positive code example (if necessary)
  - Code examples are NOT always necessary - text can be sufficient
  - Example of unnecessary code: Simple function ordering doesn't need "correct" and "incorrect" examples

- [x] **When to use positive vs negative patterns?**
  - **Positive enforcement almost always**
  - Language pattern: "Because X, instead of Y, do Z"
  - **No negative code examples** - Like asking "paint a room with no elephants" → more likely to get elephants
  - Negative examples add noise without value

#### Implementation Tasks
- [ ] Look at current experiment results to see gap to quality criteria
- [ ] Conduct some more experiments
- [ ] Compare quality criteria with what the `remember-prompt` function instructs to do
- [ ] Apply updated quality criteria to `remember-prompt` function

- [ ] Improve `remember-prompt` function
  - Review current prompt structure
  - Test variations for consistency
  - Ensure clear instructions for EDN structure
  - Validate heading format expectations (with/without ##)

- [ ] Refine `/record-memory` prompt
  - Clarify lesson summarization guidelines
  - Provide better examples
  - Define expected output format more precisely

- [ ] Enhance agentic prompt for `ai-workflow.agents` utility
  - Review autonomous agent behavior
  - Improve tool usage patterns
  - Better context gathering strategies

- [ ] Research VS Code system prompts for Grok
  - Investigate default system prompts
  - Understand model-specific behaviors
  - Document findings

- [ ] Experiment with alternative models
  - Test GPT-4o-mini (also free tier)
  - Compare quality vs speed tradeoffs
  - Document model-specific quirks
  - Still prefer Grok, but understand alternatives


