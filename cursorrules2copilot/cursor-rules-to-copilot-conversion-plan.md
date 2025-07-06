# Cursor Rules to Copilot Conversion Project Plan

## Project Overview
A Joyride-based VS Code extension to browse, search, and convert Cursor rules from the awesome-cursorrules repository to GitHub Copilot instructions, prompts, and chat modes.

## MVP Scope
This plan outlines exactly what we're shipping as our initial version - a functional conversion tool with a smooth user experience.

## Understanding the Technologies

### ✅ Research Phase
- [x] Cursor Rules Format (MDC/MDX files only)
  - MDC format with frontmatter metadata (description, globs, alwaysApply)
  - MDX files from awesome-cursorrules repository
  - ~~Legacy .cursorrules files~~ (excluded from scope)
  - Auto-attached, manual, or agent-requested rules
- [x] VS Code Copilot Formats
  - **Instructions**: `.github/copilot-instructions.md` or `.instructions.md` files
  - **Prompts**: `.prompt.md` files in `.github/prompts/`
  - **Chat Modes**: `.chatmode.md` files in `.github/chatmodes/`
- [x] Format Mapping Strategy
  - User selects target format: Instructions, Prompts, or Chat Modes
  - Default suggestion: Instructions (most common use case)
  - Simple UI: "Convert this rule to..." with quick-pick

## Core Components

### ✅ Data Layer
- [ ] Consume pre-built cursor-rules index (following awesome-copilot pattern)
  - [ ] Expected data shape: `cursor-rules.json` with flat array of rule objects
  - [ ] Each rule object contains: `{:id, :title, :description, :tech-stack, :filename, :link}`
  - [ ] `:link` contains relative path for constructing raw GitHub URL (like awesome-copilot pattern)
  - [ ] Pre-processing extracts clean titles from directory names (e.g., "nextjs-react-typescript-cursorrules-prompt-file" → "Next.js React TypeScript")
  - [ ] Content fetched only when user performs action (preview, convert, install)
  - [ ] Optimized structure for VS Code quick-pick fuzzy search and filtering

### ✅ User Interface (Joyride-based)
- [ ] Single flat quick-pick menu for all rules
  - [ ] Label: Smart composition from title and tech stack extraction
  - [ ] Description: Use existing description field from index
  - [ ] Detail: Show filename, rule type, and repository info
  - [ ] Fuzzy search across all three fields for maximum discoverability
- [ ] Rule preview interface
  - [ ] Fetch rule content on-demand from GitHub raw URLs (like awesome-copilot)
  - [ ] Show extracted metadata from index (title, tech stack, description)
  - [ ] Display fetched content in preview before conversion
- [ ] Conversion target selection
  - [ ] Simple format selection: Instructions (default), Prompts, or Chat Modes
  - [ ] Workspace vs User scope selection
  - [ ] Preview generated frontmatter and content based on user's format choice

### ✅ Conversion Engine
- [ ] Content fetcher
  - [ ] Fetch cursor rule content on-demand from GitHub raw URLs
  - [ ] Basic content validation and cleanup after fetch
  - [ ] Use extracted tech stack information from index metadata
- [ ] Format converter
  - [ ] Generate appropriate frontmatter for user-selected Copilot format
  - [ ] Convert fetched plain text cursor rules while preserving intent
  - [ ] Add VS Code-specific adaptations and formatting
- [ ] File generator
  - [ ] Create target files in correct locations (.github/copilot-instructions.md, etc.)
  - [ ] Handle naming conventions based on rule metadata
  - [ ] Avoid conflicts with existing files

### ✅ File Management
- [ ] Workspace integration
  - [ ] Detect `.github/` folder or create if needed
  - [ ] Handle different target directories (instructions/, prompts/, chatmodes/)
  - [ ] Respect VS Code Copilot settings
- [ ] Installation workflow
  - [ ] Show preview before installation
  - [ ] Confirmation dialogs
  - [ ] Success feedback with file paths

## Technical Implementation

### ✅ Joyride Script Structure (Single File Approach)
- [ ] Single self-contained script (`cursor_rules_converter.cljs`)
  - [ ] Easy installation: copy entire file and paste into Joyride user script
  - [ ] All functionality in one namespace (data fetching, UI, conversion, file ops)
  - [ ] Clear installation instructions in file header comments
  - [ ] Follows awesome_copilot.cljs pattern for maximum usability

### ✅ Data Flow
- [ ] Load cursor-rules.json index on startup → Parse into searchable structure
- [ ] User searches flat quick-pick list → Filter and rank results from loaded data
- [ ] User selects rule → Fetch content and suggest conversion type
- [ ] User confirms conversion → Generate and install files
- [ ] Show success feedback → Option to open created files

### ✅ Error Handling
- [ ] Missing or corrupted cursor-rules.json file
- [ ] File system permission issues
- [ ] Invalid cursor rule formats in index
- [ ] Workspace folder not found
- [ ] Target file conflicts

## User Experience Flow

### ✅ Startup Sequence
- [ ] Command: "Joyride: Run User Script" → "cursor_rules_converter"
- [ ] Load cursor-rules.json index from bundled data
- [ ] Show main menu with browse/search options

### ✅ Browse and Convert Flow
- [ ] Single flat quick-pick with fuzzy search across all rules
- [ ] Smart ranking based on search relevance and rule quality
- [ ] Rule preview with content display from loaded index
- [ ] Conversion type selection (Instructions default, user chooses)
- [ ] Target location selection (workspace/user)
- [ ] Preview generated content with proper frontmatter
- [ ] Install confirmation
- [ ] Success feedback with open file option

### ✅ Quality of Life Features
- [ ] Simple format selection with sensible defaults
- [ ] Duplicate detection (warn if similar files exist)
- [ ] Batch conversion option (convert multiple rules)
- [ ] Recent conversions list

## Validation and Testing

### ✅ Manual Testing Plan
- [ ] Test with different cursor rule types from index
- [ ] Verify generated file formats match VS Code expectations
- [ ] Test in workspaces with/without `.github/` folder
- [ ] Validate flat quick-pick search and filtering functionality
- [ ] Test error scenarios (missing index, no permissions)

### ✅ Content Quality
- [ ] Verify conversion preserves rule intent
- [ ] Check frontmatter format correctness
- [ ] Ensure generated files work with VS Code Copilot
- [ ] Test with different tech stack combinations

## Success Criteria

### ✅ Functional Requirements
- [ ] Successfully browse awesome-cursorrules repository
- [ ] Convert cursor rules to VS Code Copilot formats
- [ ] Install converted files in correct workspace locations
- [ ] Provide smooth, wizard-like user experience

### ✅ Quality Requirements
- [ ] Conversions preserve original rule intent
- [ ] Generated files are valid and work with Copilot
- [ ] User interface is intuitive and responsive
- [ ] Error messages are clear and actionable

## Future Enhancements (Post-MVP)
*Note: These are explicitly not part of our initial shipping plan*

- AI-powered rule analysis and enhancement
- Rule sharing to Awseome Copilot

---

## Next Steps
1. Create single self-contained cursor_rules_converter.cljs script
2. Implement flat quick-pick UI with fuzzy search across all awesome-cursorrules
3. Add rule content fetching and conversion logic
4. Build file generation and installation workflows
5. Add comprehensive installation instructions in script header
6. Manual testing and refinement
7. Documentation and usage examples

**Target completion**: Functional MVP ready for initial use and feedback
