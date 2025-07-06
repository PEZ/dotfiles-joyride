# Cursor Rules to Copilot Convers### ✅ Frontmatter and Glob Pattern Handling
- [ ] **Parse Cursor rule metadata**: Extract `.mdc` frontmatter fields like `description`, `globs`, `alwaysApply`
- [ ] **Map to VS Code Instructions**: Convert glob patterns to natural language scope descriptions
  - Example: `globs: ["**/*.ts", "**/*.tsx"]` → "Use these patterns for TypeScript and React files"
  - Instructions are repository-wide by default, no frontmatter `applyTo` patterns needed
- [ ] **Map to VS Code Prompts**: Use original rule structure for prompt templates
  - Prompt files can reference specific files using `#file:path` syntax
  - Include original glob information as part of prompt context
- [ ] **Handle missing globs**: When no globs specified, create general-purpose instructions
- [ ] **Preserve rule intent**: Maintain original author's scope and application guidanceoject Plan

## Project Overview
A Joyride-based VS Code extension to browse, search, and convert Cursor rules from the awesome-cursorrules repository to GitHub Copilot instructions, prompts, and chat modes.

## MVP Scope
This plan outlines exactly what we're shipping as our initial version - a functional conversion tool with a smooth user experience.

## Understanding the Technologies

### ✅ Research Phase
- [x] Cursor Rules Repository Structure
  - Each rule in technology-specific directories (e.g., `nextjs-react-typescript-cursorrules-prompt-file/`)
  - Multiple files per rule: `.cursorrules` (deprecated), `.mdc`/`.mdx` components, `README.md`
  - Focus on modular `.mdc`/`.mdx` files (conversion targets) vs monolithic `.cursorrules` (deprecated)
  - Rich metadata in README.md files (author attribution, benefits, synopsis)
- [x] VS Code Copilot Formats
  - **Instructions**: `.github/copilot-instructions.md` files (repository-wide custom instructions)
  - **Prompts**: `.prompt.md` files (reusable prompt templates - VS Code only feature)
  - **Note**: Chat modes not found in official documentation - focusing on Instructions and Prompts
- [x] Format Mapping Strategy
  - User selects target format: Instructions (default) or Prompts
  - **Instructions**: Generate `.github/copilot-instructions.md` files - plain markdown with natural language instructions
  - **Prompts**: Generate `.prompt.md` files in `.github/prompts/` for reusable task-specific templates (VS Code only)
  - Default suggestion: Instructions (most common, works across all platforms)
  - **Glob Pattern Handling**:
    - Instructions format doesn't use `applyTo` frontmatter - repository-wide by default
    - Extract Cursor rule `globs` for context/documentation but apply as natural language in instructions
    - Example: "Use this pattern for TypeScript files in src/" instead of `applyTo: ["src/**/*.ts"]`
  - Simple UI: "Convert this rule to..." with quick-pick

## Core Components

### ✅ Frontmatter Glob Pattern Handling
- [ ] **Parse existing globs**: Extract `globs`, `applyTo`, or similar fields from `.mdc` frontmatter
- [ ] **Preserve original patterns**: Use author-specified globs when available (e.g., `"**/*.ts"`, `"src/**/*.tsx"`)
- [ ] **No assumptions**: When globs are missing, omit `applyTo` field (requires manual application via `#` references)
- [ ] **Fallback behavior**: Instructions without `applyTo` patterns work via explicit chat references
- [ ] **Validation**: Ensure glob patterns are valid VS Code format

### ✅ Data Layer
- [ ] Consume pre-built cursor-rules index from: `https://pez.github.io/awesome-copilot-index/awesome-cursorrules.json`
  - [ ] Expected data shape: `awesome-cursorrules.json` with flat array of component objects
  - [ ] Each component object contains: `{:description, :tech-stack, :domain, :link, :component-type}`
  - [ ] `:component-type` indicates file type: "mdc", "mdx", "readme" (ignore "cursorrules")
  - [ ] `:link` contains relative path to specific component file for URL construction with base URL
  - [ ] `:domain` extracted from component title/filename (e.g., "Error Handling", "Component Guidelines")
  - [ ] `:tech-stack` extracted from directory name (e.g., "Next.js TypeScript", "Python FastAPI")
  - [ ] Pre-processing extracts clean titles and combines tech-stack + domain for "Tech stack - Domain" labels
  - [ ] Content fetched only when user performs action (preview, convert, install)
  - [ ] Optimized structure for VS Code quick-pick fuzzy search with memory persistence

### ✅ User Interface (Joyride-based)
- [ ] Single flat quick-pick menu for all modular components (single-select)
  - [ ] Label format: "Tech stack - Domain" (e.g., "Next.js TypeScript - Error Handling")
  - [ ] Description: Component type and source directory
  - [ ] Detail: Show filename, component type (.mdc/.mdx), and focus area
  - [ ] Fuzzy search across all fields for maximum discoverability
  - [ ] Uses quick-pick-with-memory pattern (like in awesome-copilot) for recent selections
- [ ] Component preview interface
  - [ ] Fetch component content on-demand from GitHub raw URLs
  - [ ] Show extracted metadata from index (title, tech stack, component type)
  - [ ] Display fetched content with frontmatter parsing for .mdc files
  - [ ] Show README.md context when available
- [ ] Conversion target selection
  - [ ] Simple format selection: Instructions (default) or Prompts
  - [ ] Repository-wide instructions vs task-specific prompts explanation
  - [ ] Preview generated content based on user's format choice

### ✅ Conversion Engine
- [ ] Content fetcher
  - [ ] Fetch modular component content (.mdc/.mdx files) from GitHub raw URLs
  - [ ] Parse .mdc frontmatter for metadata (title, description, category)
  - [ ] Handle .mdx files with enhanced markdown parsing
  - [ ] Fetch associated README.md for author attribution and context
- [ ] Format converter
  - [ ] **Instructions**: Generate plain markdown with natural language scope guidance from original globs
    - Example: "When working with TypeScript files, always use strict mode"
    - Include tech stack context from original rule domain
    - Repository-wide application by default (no frontmatter needed)
  - [ ] **Prompts**: Generate frontmatter with descriptive titles and file references
    - Use original globs to suggest relevant file patterns in prompt context
    - Support `#file:path` syntax for specific file references
  - [ ] Convert focused component content while preserving intent and structure
  - [ ] **Glob Pattern Translation**: Convert Cursor glob patterns to VS Code natural language guidance
  - [ ] Combine component content with README metadata (author, benefits)
  - [ ] Add VS Code-specific adaptations and formatting
- [ ] File generator
  - [ ] Create target files in correct locations (`.github/copilot-instructions.md` or `.github/prompts/`)
  - [ ] Handle naming conventions: instructions are single file, prompts use descriptive names
  - [ ] Include original author attribution from README.md
  - [ ] Avoid conflicts with existing files

### ✅ File Management
- [ ] Workspace integration
  - [ ] Detect `.github/` folder or create if needed
  - [ ] Handle different target locations (root for instructions, prompts/ subfolder for prompts)
  - [ ] Respect VS Code Copilot settings and file conventions
- [ ] Installation workflow
  - [ ] Show preview before installation with generated content and natural language scope
  - [ ] Explain how instructions apply repository-wide vs prompts for specific tasks
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
- [ ] Load awesome-cursorrules.json index on startup → Parse into searchable structure
- [ ] User searches flat quick-pick list → Filter and rank results
- [ ] User selects component → Fetch .mdc/.mdx content and README context
- [ ] User confirms conversion → Generate and install files with proper attribution
- [ ] Show success feedback → Option to open created files

### ✅ Error Handling
- [ ] Missing or corrupted awesome-cursorrules.json file
- [ ] File system permission issues
- [ ] Invalid cursor rule formats in index
- [ ] Workspace folder not found
- [ ] Target file conflicts

## User Experience Flow

### ✅ Startup Sequence
- [ ] Command: "Joyride: Run User Script" → "cursor_rules_converter"
- [ ] Load awesome-cursorrules.json index from https://pez.github.io/awesome-copilot-index/awesome-cursorrules.json
- [ ] Show main menu with browse/search options

### ✅ Browse and Convert Flow
- [ ] Single flat quick-pick with fuzzy search across all modular components (single-select)
- [ ] Component selection with "Tech stack - Domain" labeling and memory persistence
- [ ] Component preview with content display and README context
- [ ] Conversion type selection (Instructions default, Prompts alternative)
- [ ] Preview generated content with proper natural language scope guidance and attribution
- [ ] Install confirmation
- [ ] Success feedback with open file option

### ✅ Quality of Life Features
- [ ] Simple format selection with sensible defaults
- [ ] Duplicate detection (warn if similar files exist)

## Validation and Testing

### ✅ Manual Testing Plan
- [ ] Test with different modular component types (.mdc/.mdx) from index
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
2. Implement flat quick-pick UI with fuzzy search across all modular components (.mdc/.mdx files)
3. Add component content fetching with frontmatter parsing and README context
4. Build file generation and installation workflows with proper attribution
5. Add comprehensive installation instructions in script header
6. Manual testing and refinement with different component types
7. Documentation and usage examples

**Target completion**: Functional MVP ready for initial use and feedback
