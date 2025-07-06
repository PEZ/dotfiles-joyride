# Understanding Cursor Rules

## What are Cursor Rules?

Cursor rules are configuration files that provide persistent, reusable context and instructions to AI models in the### File Types and Formats

1. **Documentation**: `README.md`
   - **Format**: Markdown with structured metadata
   - **Content**: Author attribution, rule description, benefits, synopsis
Cursor rules are configuration files that provide persistent, reusable context and instructions to AI models in the Cursor editor. They act as system-level instructions that guide AI behavior for code generation, editing, and workflow assistance.

## Types of Cursor Rules

### 1. Project Rules (`.cursor/rules/*.mdc`)
- **Location**: `.cursor/rules` directory in project root
- **Format**: MDC (Markdown with metadata)
- **Scope**: Project-specific, version-controlled
- **Nested Support**: Can have subdirectories with their own rules

#### Rule Anatomy (MDC Format)
```markdown
---
description: RPC Service boilerplate
globs: "**/*.ts"
alwaysApply: false
---

- Use our internal RPC pattern when defining services
- Always use snake_case for service names.

@service-template.ts
```

#### Rule Types by Application:
- **Always**: Always included in model context
- **Auto Attached**: Included when files matching glob patterns are referenced
- **Agent Requested**: AI decides whether to include (requires description)
- **Manual**: Only included when explicitly mentioned using @ruleName

### 2. User Rules
- **Location**: Cursor Settings → Rules
- **Format**: Plain text
- **Scope**: Global across all projects
- **Use Case**: Personal preferences, communication style, coding conventions

Example:
```
Please reply in a concise style. Avoid unnecessary repetition or filler language.
```

### 3. Legacy `.cursorrules` (Deprecated)
- **Location**: Project root
- **Format**: Plain text/markdown
- **Status**: **Still supported but officially deprecated by Cursor**
- **Migration**: Cursor recommends migrating to Project Rules for better control and flexibility
- **Note**: The awesome-cursorrules repository uses this legacy format

### 4. Community Rules (awesome-cursorrules)
- **Location**: GitHub repository with 200+ curated rules
- **Format**: Legacy `.cursorrules` files + modular `.mdc`/`.mdx` components
- **Organization**: Technology-specific directories with complete rule sets
- **Scope**: Production-ready rules for major frameworks and languages
- **Content**: Mix of legacy comprehensive guidelines and modern modular components

## How Cursor Rules Work

1. **Context Injection**: Rule contents are included at the start of model context
2. **No Memory**: Large language models don't retain memory between completions, so rules provide persistent guidance
3. **Application**: Rules apply to Chat and Inline Edit features
4. **Visibility**: Active rules show in the Agent sidebar

## Rule Organization

### Nested Rules Structure
```
project/
  .cursor/rules/        # Project-wide rules
  backend/
    server/
      .cursor/rules/    # Backend-specific rules
  frontend/
    .cursor/rules/      # Frontend-specific rules
```

### Best Practices
- Keep rules under 500 lines
- Split large rules into multiple, composable rules
- Provide concrete examples or referenced files
- Avoid vague guidance - write like clear internal docs
- Reuse rules when repeating prompts in chat

## Rule Content Examples

### General Coding Guidelines
```markdown
You are an expert in TypeScript, Node.js, Next.js 14 App Router, React, and Tailwind CSS.

Key Principles:
- Write concise, technical responses with accurate examples
- Use functional, declarative programming. Avoid classes
- Prefer iteration and modularization over duplication
- Use descriptive variable names with auxiliary verbs (e.g., isLoading)
```

### Framework-Specific Rules
```markdown
React/Next.js:
- Use functional components and TypeScript interfaces
- Use declarative JSX
- Minimize 'use client', 'useEffect', and 'setState'. Favor RSC
- Use Zod for form validation
- Implement responsive design with Tailwind CSS
```

### Error Handling Patterns
```markdown
Error Handling and Validation:
- Handle errors and edge cases at the beginning of functions
- Use early returns for error conditions
- Place the happy path last in the function
- Use guard clauses for preconditions and invalid states
```

## Rule Generation

### Manual Creation
- Use "New Cursor Rule" command
- Go to Cursor Settings > Rules
- Create files directly in `.cursor/rules`

### AI-Assisted Generation
- Use `/Generate Cursor Rules` command in conversations
- Useful when you've made decisions about agent behavior and want to reuse them

## Community and Examples

### awesome-cursorrules Repository
- **Scale**: 200+ curated cursor rules for major technologies
- **Organization**: Each rule in its own directory with comprehensive documentation
- **Quality**: Production-ready rules from experienced developers
- **Categories**: Frontend frameworks, Backend, Mobile, CSS, Testing, Build tools, etc.

### Repository Structure and Content
- **Main Rule File**: `.cursorrules` (comprehensive plain text guidelines)
- **Documentation**: `README.md` with author info, benefits, and synopsis
- **Modular Components**: Additional `.mdc` and `.mdx` files for rule organization
- **Tech Stack Coverage**: React, Next.js, TypeScript, Python, Go, Elixir, etc.

### Rule Quality and Depth
- **Comprehensive Guidelines**: Complete coding standards and best practices
- **Framework-Specific**: Detailed patterns for specific technology combinations
- **Error Handling**: Explicit error handling and validation strategies
- **Performance**: Optimization guidelines and Web Vitals considerations

### Directory Structure in awesome-cursorrules
Each rule is organized in its own directory following the pattern `{technology-stack}-cursorrules-prompt-file`:

```
rules/
  nextjs-react-typescript-cursorrules-prompt-file/
    .cursorrules                                    # Main rule file (plain text, no frontmatter)
    README.md                                       # Description and author info
    error-handling-and-validation.mdc              # Additional rule components (optional)
    general-solidity-typescript-node-js-next-js-rule.mdc
    javascript-typescript-coding-style.mdc
    next-js-conventions.mdc
    next-js-server-actions.mdc
    react-next-js-components.mdc
  rails-cursorrules-prompt-file/
    .cursorrules                                    # Main rule file
    README.md                                       # Description and metadata
    rails-basics.mdx                               # Additional documentation (MDX format)
  python-fastapi-cursorrules-prompt-file/
    .cursorrules                                    # Main rule file
    README.md                                       # Description and metadata
  # ... hundreds more directories
```

### File Types and Formats

1. **Documentation**: `README.md`
   - **Format**: Markdown with structured metadata
   - **Content**: Author attribution, rule description, benefits, synopsis
   - **Conversion Value**: Source of metadata and descriptions for Copilot files

2. **Modular Rule Components** (primary conversion targets):
   - **`.mdc` files**: Markdown with metadata components - focused, specific guidelines
   - **`.mdx` files**: MDX format for enhanced documentation and examples
   - **Purpose**: Targeted rule components for specific aspects (error handling, styling, etc.)
   - **Conversion Value**: Well-scoped content perfect for individual Copilot instructions/prompts

3. **Legacy Rule File**: `.cursorrules` (not suitable for conversion)
   - **Format**: Monolithic plain text guidelines (deprecated format)
   - **Content**: Comprehensive development guidelines covering entire tech stacks
   - **Conversion Challenge**: Too broad and comprehensive for targeted Copilot formats
   - **Status**: Officially deprecated by Cursor in favor of modern Project Rules

### Actual Content Structure (Modular Components)

The modular `.mdc` and `.mdx` files contain focused, targeted guidelines perfect for Copilot conversion:

**Example .mdc file (error-handling-and-validation.mdc):**
```markdown
---
title: Error Handling and Validation
description: Best practices for error handling in TypeScript applications
category: guidelines
---

Error Handling and Validation:
- Handle errors and edge cases at the beginning of functions
- Use early returns for error conditions to avoid deeply nested statements
- Place the happy path last in the function for improved readability
- Use guard clauses to handle preconditions and invalid states early
```

**Example .mdx file (react-next-js-components.mdx):**
```markdown
# React/Next.js Component Guidelines

- Use functional components and TypeScript interfaces
- Use declarative JSX and avoid imperative DOM manipulation
- Minimize 'use client', 'useEffect', and 'setState'. Favor RSC
- Use Zod for form validation
- Implement responsive design with Tailwind CSS
```

## Key Characteristics for Conversion

### Content Patterns (Modular Components)
1. **Focused Guidelines**: Specific technical instructions for particular aspects (error handling, styling, etc.)
2. **Targeted Scope**: Individual `.mdc`/`.mdx` files cover specific topics rather than entire tech stacks
3. **Structured Metadata**: Many `.mdc` files include frontmatter with title, description, category
4. **Practical Examples**: Concrete coding patterns within specific domains

### Repository Organization (Conversion Targets)
- **Directory Naming**: `{tech-stack}-cursorrules-prompt-file` pattern
- **Modular Files**: Multiple `.mdc` and `.mdx` components per rule directory
- **Rich Metadata**: README.md provides author attribution and descriptions
- **Focused Components**: Each file addresses specific development concerns

### Content Depth and Structure (Modular Files)
- **Frontmatter Support**: `.mdc` files often include metadata headers
- **Focused Scope**: Specific guidelines for particular aspects (not comprehensive guides)
- **Technical Specificity**: Framework-specific patterns and implementation details
- **Conversion-Ready**: Well-scoped content that maps naturally to Copilot formats

### Conversion Strategy
- **Primary Sources**: `.mdc` and `.mdx` files (ignore deprecated `.cursorrules`)
- **Metadata Integration**: Combine content with README.md descriptions and author info
- **Natural Mapping**: Focused components translate well to Instructions, Prompts, or Chat Modes
- **Preserve Attribution**: Maintain original author credits from README files
- **Legacy Consideration**: awesome-cursorrules uses deprecated format, but modular components are conversion-ready

This understanding forms the foundation for converting Cursor rules to VS Code Copilot formats while preserving their intent and effectiveness.