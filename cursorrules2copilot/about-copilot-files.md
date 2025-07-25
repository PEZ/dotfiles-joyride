# Understanding VS Code Copilot Files

> **Sources**:
> - [VS Code Copilot Documentation](https://code.visualstudio.com/docs/copilot/copilot-chat)
> - [VS Code Copilot Customization](https://code.visualstudio.com/docs/copilot/copilot-customization)
> - [VS Code Custom Instructions](https://code.visualstudio.com/docs/copilot/copilot-customization#_custom-instructions)
> - [VS Code Prompt Files](https://code.visualstudio.com/docs/copilot/copilot-customization#_prompt-files-experimental)
> - [VS Code Chat Modes](https://code.visualstudio.com/docs/copilot/chat/chat-modes)
> - [VS Code Settings Reference](https://code.visualstudio.com/docs/getstarted/settings)
> - [GitHub Copilot Documentation](https://docs.github.com/en/copilot)
> - [GitHub Copilot Instructions Reference](https://docs.github.com/en/copilot/using-github-copilot/prompt-engineering-for-github-copilot)
> - [VS Code Copilot Extension](https://marketplace.visualstudio.com/items?itemName=GitHub.copilot)
> - [awesome-copilot Repository](https://github.com/github/awesome-copilot)

## Overview

VS Code supports multiple file formats for customizing GitHub Copilot behavior through instructions, prompts, and chat modes. These files provide context and guidance to AI models for specific tasks and coding practices.

## File Types and Locations

### 1. Instructions Files

> **Reference**: [VS Code Custom Instructions](https://code.visualstudio.com/docs/copilot/copilot-customization#_custom-instructions)

#### `.github/copilot-instructions.md`
- **Location**: `.github/copilot-instructions.md` in workspace root
- **Format**: Markdown
- **Scope**: Workspace-specific
- **Application**: Automatically included in every chat request
- **Use Case**: General coding practices, preferred technologies, project requirements

**Example**:
```markdown
# Project Coding Guidelines

## TypeScript Standards
- Use interfaces over types
- Prefer functional components
- Always use explicit return types for functions

## React Patterns
- Use hooks for state management
- Implement error boundaries
- Follow component composition patterns
```

#### `.instructions.md` Files
- **Location**:
  - Workspace: `.github/instructions/` (default)
  - User: VS Code profile folder
- **Format**: Markdown with optional frontmatter
- **Scope**: Configurable via glob patterns
- **Application**: Auto-applied or manually attached

**Structure**:
```markdown
---
description: "React component guidelines"
applyTo: "**/*.tsx"
---

# React Component Instructions
Always use TypeScript interfaces for props.
Implement proper error handling with error boundaries.
```

**Frontmatter Properties**:
- `description`: Brief description shown on hover
- `applyTo`: Glob pattern for automatic application
  - `"**"`: Apply to all chat requests
  - `"**/*.ts"`: Apply when TypeScript files are in context

### 2. Prompt Files (`.prompt.md`) - Experimental

> **Reference**: [VS Code Prompt Files](https://code.visualstudio.com/docs/copilot/copilot-customization#_prompt-files-experimental)
>
> ⚠️ **Note**: Prompt files are currently marked as experimental in VS Code

- **Location**:
  - Workspace: `.github/prompts/` (default)
  - User: VS Code profile folder
- **Format**: Markdown with frontmatter
- **Purpose**: Reusable prompts for common tasks
- **Usage**: Run directly in chat or reference from other prompts

**Structure**:
```markdown
---
mode: "agent"
tools: ["codebase", "search", "fetch"]
description: "Generate React form component"
---

# React Form Generator

Create a React form component with the following requirements:
- Use TypeScript
- Implement form validation with Zod
- Include proper error handling
- Follow accessibility guidelines

Variables:
- Component name: ${input:componentName:MyForm}
- Form fields: ${input:fields:name,email,message}
```

**Frontmatter Properties**:
- `mode`: Chat mode (`ask`, `edit`, `agent`)
- `tools`: Array of available tools/tool sets
- `description`: Short description of the prompt

**Variable Support**:
- `${workspaceFolder}`, `${workspaceFolderBasename}`
- `${selection}`, `${selectedText}`
- `${file}`, `${fileBasename}`, `${fileDirname}`, `${fileBasenameNoExtension}`
- `${input:variableName}`, `${input:variableName:placeholder}`

### 3. Chat Mode Files (`.chatmode.md`)

> **Reference**: [VS Code Chat Modes](https://code.visualstudio.com/docs/copilot/chat/chat-modes#_custom-chat-modes)
>
> ⚠️ **Note**: Chat modes are available as of VS Code release 1.101 and are currently in preview

- **Location**:
  - Workspace: `.github/chatmodes/` (default)
  - User: VS Code profile folder
- **Format**: Markdown with frontmatter
- **Purpose**: Define custom chat behaviors and tool sets
- **Scope**: Workspace or user profile

**Example**:
```markdown
---
description: "Generate implementation plan without code edits"
tools: ['codebase', 'fetch', 'findTestFiles', 'githubRepo', 'search', 'usages']
---

# Planning Mode Instructions

You are in planning mode. Generate implementation plans without making code edits.

## Plan Structure
- Overview: Brief feature description
- Requirements: List of requirements
- Implementation Steps: Detailed step list
- Testing: Required test cases

Focus on architecture and design decisions.
```

## Settings-Based Instructions

> **Reference**: [VS Code Custom Instructions in settings](https://code.visualstudio.com/docs/copilot/copilot-customization#_specify-custom-instructions-in-settings)

### Configuration in settings.json
```json
{
  "github.copilot.chat.codeGeneration.instructions": [
    {
      "text": "Always add a comment: 'Generated by Copilot'."
    },
    {
      "file": "general.instructions.md"
    }
  ],
  "github.copilot.chat.testGeneration.instructions": [
    {
      "text": "Use Jest and React Testing Library for tests."
    }
  ]
}
```

### Instruction Types by Setting
- `github.copilot.chat.codeGeneration.instructions`
- `github.copilot.chat.testGeneration.instructions`
- `github.copilot.chat.reviewSelection.instructions`
- `github.copilot.chat.commitMessageGeneration.instructions`
- `github.copilot.chat.pullRequestDescriptionGeneration.instructions`

## Chat Modes

> **Reference**: [VS Code Chat Modes](https://code.visualstudio.com/docs/copilot/chat/chat-modes)

### Built-in Modes
- **Ask Mode**: Questions about codebase and technology concepts
- **Edit Mode**: Direct code edits across multiple files
- **Agent Mode**: Autonomous coding workflows with tools

### Custom Mode Benefits
- Predefined tool sets for specific tasks
- Consistent instructions across team
- Task-specific AI behavior
- Quick switching between contexts

## File Management

> **Reference**:
> - [VS Code Copilot Customization](https://code.visualstudio.com/docs/copilot/copilot-customization)
> - [awesome-copilot Repository](https://github.com/github/awesome-copilot)

### Creation Commands
- `Chat: New Instructions File`
- `Chat: New Prompt File`
- `Chat: New Mode File`

### Configuration Settings
- `chat.instructionsFilesLocations`: Additional instruction locations
- `chat.promptFilesLocations`: Additional prompt locations
- `chat.modeFilesLocations`: Additional chat mode locations
- `chat.promptFiles`: Enable/disable prompt files globally

### Sync Support
- User files sync across devices via Settings Sync
- Enable "Prompts and Instructions" in Settings Sync configuration

## Usage Patterns

### Instructions Files
- **When**: General coding guidelines, project standards
- **Scope**: Workspace-wide or file-type specific
- **Content**: Coding conventions, preferred patterns, tech stack preferences

### Prompt Files
- **When**: Repeatable tasks, code generation templates
- **Scope**: Specific tasks or workflows
- **Content**: Task descriptions, requirements, examples

### Chat Modes
- **When**: Specialized workflows, tool configurations
- **Scope**: Task-oriented AI behavior
- **Content**: Mode instructions, tool restrictions, workflow guidance

## Best Practices

### File Organization
- Keep instructions short and focused
- Split by topic or technology
- Use descriptive filenames
- Organize in logical directory structure

### Content Guidelines
- Use clear, specific language
- Provide concrete examples
- Avoid external references
- Make instructions self-contained

### Team Collaboration
- Store in version control
- Document file purposes
- Use consistent naming conventions
- Regular review and updates

## Integration with VS Code

### Context Addition
- Use `#` mentions in chat to reference files
- Add Context button in Chat view
- Automatic application via `applyTo` patterns

### File References
- Markdown links to other workspace files
- Cross-references between prompt/instruction files
- Variable substitution for dynamic content

This comprehensive understanding enables effective conversion from Cursor rules to appropriate VS Code Copilot file formats while maintaining functionality and improving integration with VS Code's ecosystem.
