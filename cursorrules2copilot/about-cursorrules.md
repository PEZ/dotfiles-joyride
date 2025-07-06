# Understanding Cursor Rules

## What are Cursor Rules?

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
- **Status**: Still supported but being phased out
- **Migration**: Recommended to move to Project Rules for better control

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

### Popular Sources
- **awesome-cursorrules**: Large community collection on GitHub
- **Categories**: Frontend frameworks, Backend, Mobile, CSS, Testing, etc.
- **Examples**: React+TypeScript, Next.js, Python FastAPI, Go, etc.

### Directory Structure in awesome-cursorrules
```
rules/
  nextjs-react-typescript-cursorrules-prompt-file/
    .cursorrules
  python-fastapi-cursorrules-prompt-file/
    .cursorrules
  # ... hundreds more
```

## Key Characteristics for Conversion

### Content Patterns
1. **Guidelines**: General coding practices and conventions
2. **Framework Instructions**: Specific technology usage patterns
3. **Workflow Rules**: Step-by-step processes and methodologies
4. **Error Handling**: Consistent approaches to validation and errors

### Metadata Available
- Technology stack (React, TypeScript, Python, etc.)
- Framework versions (Next.js 14, Python 3.12, etc.)
- Associated tools (Tailwind, Zod, FastAPI, etc.)
- Rule scope (project-wide vs feature-specific)

### Conversion Considerations
- Rules vary from simple guidelines to complex multi-section instructions
- Some include code examples and templates
- Many have specific formatting requirements
- Content ranges from general principles to very specific implementation details

This understanding forms the foundation for converting Cursor rules to VS Code Copilot formats while preserving their intent and effectiveness.