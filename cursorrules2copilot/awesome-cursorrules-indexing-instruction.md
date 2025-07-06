# Instruction: Add awesome-cursorrules Indexing to awesome-copilot-index

## Context

You are extending the existing awesome-copilot-index project to also index the awesome-cursorrules repository. The project currently indexes GitHub's awesome-copilot repository containing copilot instructions, prompts, and chat modes. Now we need to add support for indexing cursor rules from the awesome-cursorrules repository.

## Current Project Structure

The project uses:
- **Language**: Clojure with Babashka
- **Main files**: `src/pez/index.clj` (indexing logic), `src/pez/tasks.clj` (download/build tasks)
- **Build**: `bb.edn` with tasks `download-awesome` and `generate-index`
- **Output**: JSON and EDN files served at `pez.github.io/awesome-copilot-index/`

## Current Data Format (awesome-copilot)

```json
{
  "generated": "timestamp",
  "instructions": [{"filename": "...", "title": "...", "description": "...", "link": "..."}],
  "prompts": [...],
  "chatmodes": [...]
}
```

## awesome-cursorrules Repository Structure

- **Repository**: `https://github.com/lencx/awesome-cursorrules`
- **Organization**: Technology-specific directories (e.g., `nextjs-react-typescript-cursorrules-prompt-file/`)
- **Content per directory**:
  - `.cursorrules` (deprecated monolithic file - **IGNORE**)
  - `.mdc` files (modular components - **PRIMARY TARGETS**)
  - `.mdx` files (modular components - **PRIMARY TARGETS**)
  - `README.md` (metadata and author attribution)

## Required New Data Format for cursor-rules

```json
{
  "generated": "timestamp",
  "cursor-rules": [
    {
      "description": "Best practices for error handling in TypeScript applications",
      "tech-stack": "Next.js TypeScript",
      "domain": "Error Handling",
      "link": "nextjs-react-typescript-cursorrules-prompt-file/error-handling-and-validation.mdc",
      "component-type": "mdc"
    }
  ]
}
```

## Implementation Requirements

### 1. Update `src/pez/tasks.clj`

- Add new constants for awesome-cursorrules:
  ```clojure
  (def cursorrules-dir "awesome-cursorrules-main")
  (def cursorrules-zip-url "https://github.com/lencx/awesome-cursorrules/archive/refs/heads/main.zip")
  ```
- Add `download-cursorrules!` function (copy pattern from `download-awesome!`)
- Update `bb.edn` with new task: `download-cursorrules`

### 2. Update `src/pez/index.clj`

- Add `process-cursorrules-directory` function to:
  - Scan all technology directories
  - For each directory, process only `.mdc` and `.mdx` files (ignore `.cursorrules`)
  - Extract tech-stack from directory name (convert `nextjs-react-typescript-cursorrules-prompt-file` → `"Next.js TypeScript"`)
  - Extract domain from filename (convert `error-handling-and-validation.mdc` → `"Error Handling"`)
  - Read README.md for additional metadata/descriptions
  - Handle frontmatter parsing for `.mdc` files
- Add `generate-cursorrules-index!` function
- Create separate output: `cursor-rules.json` and `cursor-rules.edn`

### 3. Key Processing Logic

- **Tech-stack extraction**: `nextjs-react-typescript-cursorrules-prompt-file` → `"Next.js TypeScript"`
- **Domain extraction**: `error-handling-and-validation.mdc` → `"Error Handling"`
- **Description extraction**: Use H1 heading first, then filename-based fallback for descriptions
- **Filter files**: Only process `.mdc` and `.mdx` files, ignore `.cursorrules` and `README.md`
- **Relative paths**: Ensure `link` field contains correct relative path from repository root

### 4. Output Requirements

- Generate `site/cursor-rules.json` (for consumption by Joyride script)
- Generate `site/cursor-rules.edn` (for debugging)
- Maintain same JSON structure patterns as awesome-copilot
- Ensure proper UTF-8 encoding and pretty-printing

### 5. Build Integration

- Add new bb.edn tasks:
  - `download-cursorrules` → downloads awesome-cursorrules repository
  - `generate-cursorrules-index` → generates cursor-rules index files
- Keep existing awesome-copilot functionality unchanged
- Both indexes should be generated independently

## Data Processing Details

### Tech-Stack Extraction Logic

Convert directory names to human-readable tech stacks:
- `nextjs-react-typescript-cursorrules-prompt-file` → `"Next.js TypeScript"`
- `python-fastapi-cursorrules-prompt-file` → `"Python FastAPI"`
- `rails-cursorrules-prompt-file` → `"Rails"`
- `go-cursorrules-prompt-file` → `"Go"`

### Domain Extraction Logic

Convert component filenames to readable domains:
- `error-handling-and-validation.mdc` → `"Error Handling"`
- `react-next-js-components.mdc` → `"React Components"`
- `javascript-typescript-coding-style.mdc` → `"Coding Style"`

### Component Type Detection

- Files ending in `.mdc` → `"mdc"`
- Files ending in `.mdx` → `"mdx"`
- Ignore files ending in `.cursorrules`
- Ignore `README.md` files (but read for metadata)

## Success Criteria

1. **New JSON file** `cursor-rules.json` contains flat array of modular components
2. **Required fields** Each component has all required fields for "Tech stack - Domain" labeling
3. **File filtering** Only `.mdc` and `.mdx` files are indexed (no `.cursorrules` files)
4. **Data extraction** Tech-stack and domain extraction works correctly
5. **Independent build** Build process is independent of existing awesome-copilot indexing
6. **Format consistency** Output format matches expected data shape for Joyride consumption

## Testing Approach

1. **Start small** Test with a few sample directories first
2. **Verify extraction** Check tech-stack and domain extraction logic
3. **Validate output** Ensure JSON structure matches requirements
4. **Test builds** Verify both awesome-copilot and cursor-rules can be built independently

## Error Handling Requirements

- Handle missing or corrupted repository downloads
- Graceful handling of malformed `.mdc` frontmatter
- Log warnings for directories without valid components
- Maintain same error handling patterns as existing code

## Notes and Constraints

- **Focus on modular components** (`.mdc`/`.mdx`) not monolithic `.cursorrules` files
- **Human-readable output** Tech-stack should be human-readable (spaces, proper capitalization)
- **Descriptive domains** Domain should be descriptive and extracted from component content
- **Pattern consistency** Maintain same error handling and logging patterns as existing code

## Expected Outcome

This will enable the Joyride `cursor_rules_converter.cljs` script to consume the generated `cursor-rules.json` index following the same patterns as `awesome_copilot.cljs`, providing a flat quick-pick menu with "Tech stack - Domain" labeling for all modular cursor rule components.

## Build Commands After Implementation

```bash
# Download awesome-cursorrules repository
bb download-cursorrules

# Generate cursor-rules index
bb generate-cursorrules-index

# (Existing commands still work)
bb download-awesome
bb generate-index
```

The cursor-rules index will be available at: `https://pez.github.io/awesome-copilot-index/cursor-rules.json`
