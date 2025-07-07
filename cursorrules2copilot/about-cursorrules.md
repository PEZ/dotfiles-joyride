# Understanding Cursor Rules

> **Sources**:
> - [Cursor Rules Documentation](https://docs.cursor.com/context/rules)
> - [Cursor AI Official Website](https://cursor.com/)
> - [Cursor Documentation](https://docs.cursor.com/)
> - [awesome-cursorrules Repository](https://github.com/PatrickJS/awesome-cursorrules) *(Community collection using legacy format)*
> - [Cursor Rules Extension](https://marketplace.visualstudio.com/items?itemName=BeilunYang.cursor-rules)
> - [Cursor Forum](https://forum.cursor.com/)

## What are Cursor Rules?

> **Reference**: [Cursor Rules Documentation](https://docs.cursor.com/context/rules)

Cursor rules provide system-level instructions to Agent and Inline Edit, offering persistent, reusable context and instructions to AI models in the Cursor editor. They act as reusable, scoped instructions that guide AI behavior for code generation, editing, and workflow assistance.

**Cursor supports three types of rules:**
1. **Project Rules** - Stored in `.cursor/rules`, version-controlled and scoped to your codebase
2. **User Rules** - Global to your Cursor environment, defined in settings and always applied
3. **`.cursorrules` (Legacy)** - Still supported, but deprecated. Use Project Rules instead.

## Types of Cursor Rules

> **Reference**: [Cursor Rules Types](https://docs.cursor.com/context/rules)

### 1. Project Rules (`.cursor/rules/*.mdc`) - **PRIMARY FORMAT**

> **Reference**: [Project Rules Documentation](https://docs.cursor.com/context/rules#project-rules)

- **Location**: `.cursor/rules` directory in project root
- **Format**: MDC (Markdown with metadata) - `.mdc` files
- **Scope**: Project-specific, version-controlled
- **Nested Support**: Can have subdirectories with their own rules
- **Use Cases**: Domain-specific knowledge, project workflows, style standards

#### Rule Anatomy (MDC Format)

> **Reference**: [Rule Anatomy](https://docs.cursor.com/context/rules#rule-anatomy)

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

> **Reference**: [User Rules Documentation](https://docs.cursor.com/context/rules#user-rules)

- **Location**: Cursor Settings → Rules
- **Format**: Plain text
- **Scope**: Global across all projects
- **Use Case**: Personal preferences, communication style, coding conventions

Example:
```
Please reply in a concise style. Avoid unnecessary repetition or filler language.
```

### 3. `.cursorrules` (Legacy - Deprecated)

> **Reference**: [Legacy cursorrules](https://docs.cursor.com/context/rules#cursorrules-legacy)

- **Location**: Project root
- **Format**: Plain text/markdown
- **Status**: **Still supported but deprecated by Cursor**
- **Migration**: Cursor recommends migrating to Project Rules for more control, flexibility, and visibility
- **Note**: The awesome-cursorrules repository still uses this legacy format, but for conversion purposes we focus on modern Project Rules

### 4. Community Rules (awesome-cursorrules) - **Legacy Format Collection**

> **Reference**: [awesome-cursorrules Repository](https://github.com/PatrickJS/awesome-cursorrules) *(Uses deprecated format)*

- **Status**: Community repository using deprecated `.cursorrules` format
- **Value**: Large collection of rules, but in legacy format
- **Conversion Focus**: Extract patterns and guidelines, convert to modern Project Rules format
- **Note**: While this repository has valuable content, it uses the deprecated format that Cursor no longer recommends

## How Cursor Rules Work

> **Reference**: [How Rules Work](https://docs.cursor.com/context/rules#how-rules-work)

1. **Context Injection**: Rule contents are included at the start of model context
2. **No Memory**: Large language models don't retain memory between completions, so rules provide persistent guidance
3. **Application**: Rules apply to Chat and Inline Edit features
4. **Visibility**: Active rules show in the Agent sidebar

## Rule Organization

> **Reference**: [Nested Rules](https://docs.cursor.com/context/rules#nested-rules)

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

> **Reference**: [Best Practices](https://docs.cursor.com/context/rules#best-practices)

- Keep rules under 500 lines
- Split large rules into multiple, composable rules
- Provide concrete examples or referenced files
- Avoid vague guidance - write like clear internal docs
- Reuse rules when repeating prompts in chat

## Rule Content Examples

> **Reference**: [Examples](https://docs.cursor.com/context/rules#examples)

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

> **Reference**: [Creating and Generating Rules](https://docs.cursor.com/context/rules#creating-a-rule)

### Manual Creation
- Use "New Cursor Rule" command
- Go to Cursor Settings > Rules
- Create files directly in `.cursor/rules`

### AI-Assisted Generation
- Use `/Generate Cursor Rules` command in conversations
- Useful when you've made decisions about agent behavior and want to reuse them

## How to Use Cursor Rules

> **Reference**: [Official Cursor Rules Documentation](https://docs.cursor.com/context/rules)

### Modern Approach (Recommended)

1. **Project Rules (`.cursor/rules/*.mdc`)**:
   - Create MDC files in `.cursor/rules` directory
   - Use frontmatter for metadata and targeting
   - Leverage nested directory structure for organization
   - Version control with your project

2. **User Rules**:
   - Define global preferences in Cursor Settings → Rules
   - Use for personal coding style and communication preferences

### Legacy Support (Not Recommended for New Projects)

- `.cursorrules` files in project root still work but are deprecated
- Migrate existing `.cursorrules` to Project Rules for better control

## Community Examples and Conversion Considerations

### awesome-cursorrules Repository (Legacy Format)

> **Reference**: [awesome-cursorrules Repository](https://github.com/PatrickJS/awesome-cursorrules) *(Uses deprecated `.cursorrules` format)*

**Important Note**: This repository uses the deprecated `.cursorrules` format. While it contains valuable content patterns, Cursor officially recommends using Project Rules (`.cursor/rules/*.mdc`) instead.

**Repository Overview**:
- **Scale**: 200+ curated rules with 30.1k stars
- **Format**: Deprecated `.cursorrules` files + some modular `.mdc`/`.mdx` components
- **Organization**: Technology-specific directories with comprehensive guidelines
- **Contributors**: 52+ developers across many technologies

**Conversion Value**:
- **Content Patterns**: Extensive examples of coding guidelines and best practices
- **Framework Coverage**: React, Next.js, TypeScript, Python, Go, and 30+ other technologies
- **Modular Components**: Some directories include `.mdc` and `.mdx` files that align with modern approaches

### Repository Structure (Legacy Format)

Each rule directory follows the pattern `{technology-stack}-cursorrules-prompt-file`:

```
rules/
  nextjs-react-typescript-cursorrules-prompt-file/
    .cursorrules                                    # Main rule file (deprecated format)
    README.md                                       # Description and author info
    error-handling-and-validation.mdc              # Modern component (conversion target)
    javascript-typescript-coding-style.mdc         # Modern component (conversion target)
    next-js-conventions.mdc                         # Modern component (conversion target)
    react-next-js-components.mdc                    # Modern component (conversion target)
  rails-cursorrules-prompt-file/
    .cursorrules                                    # Main rule file (deprecated format)
    README.md                                       # Description and metadata
    rails-basics.mdx                               # Modern component (conversion target)
```

### File Types for Conversion

1. **Documentation**: `README.md`
   - **Content**: Author attribution, rule description, benefits, synopsis
   - **Conversion Value**: Source of metadata and descriptions for Copilot files

2. **Modern Modular Components** *(conversion targets)*:
   - **`.mdc` files**: Focused guidelines with frontmatter metadata
   - **`.mdx` files**: Enhanced documentation with examples
   - **Value**: Well-scoped content perfect for VS Code Copilot conversion

3. **Legacy Files** *(ignore for conversion)*:
   - **`.cursorrules`**: Monolithic deprecated format
   - **Status**: Skip these files in favor of modular components

### Example Modern Components (Conversion Targets)

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

### Focus: Modern MDC Components Only

> **Conversion Strategy**: Focus on `.mdc` and `.mdx` files, ignore deprecated `.cursorrules` files

### Content Patterns (Modern Components)
1. **Focused Guidelines**: Specific technical instructions for particular aspects (error handling, styling, etc.)
2. **Targeted Scope**: Individual `.mdc`/`.mdx` files cover specific topics rather than entire tech stacks
3. **Structured Metadata**: `.mdc` files include frontmatter with title, description, category
4. **Practical Examples**: Concrete coding patterns within specific domains

### Repository Organization (Conversion Targets)
- **Directory Naming**: `{tech-stack}-cursorrules-prompt-file` pattern
- **Modular Files**: Focus on `.mdc` and `.mdx` components within directories
- **Rich Metadata**: README.md provides author attribution and descriptions
- **Focused Components**: Each modern file addresses specific development concerns

### Content Depth and Structure (Modern Files Only)
- **Frontmatter Support**: `.mdc` files include metadata headers
- **Focused Scope**: Specific guidelines for particular aspects
- **Technical Specificity**: Framework-specific patterns and implementation details
- **Conversion-Ready**: Well-scoped content that maps naturally to Copilot formats

### Conversion Strategy
- **Primary Sources**: `.mdc` and `.mdx` files only (ignore deprecated `.cursorrules`)
- **Metadata Integration**: Combine content with README.md descriptions and author info
- **Natural Mapping**: Focused components translate well to Instructions, Prompts, or Chat Modes
- **Preserve Attribution**: Maintain original author credits from README files
- **Modern Format Focus**: Align with Cursor's recommended Project Rules approach

This understanding forms the foundation for converting modern Cursor rule components to VS Code Copilot formats while following current best practices and ignoring deprecated formats.