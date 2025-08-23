# Joyride Note Manager - Technical Implementation Notes

*Development notes and implementation details*

---

## Core Architecture

### API Functions

```clojure
(defn open-note-input! []
  "Shows persistent input box for human users, handles submission workflow")

(defn gather-context []
  "Builds context map from current environment (no side effects)")

(defn jot-this-down! [note context]
  "Processes note with AI agent using provided context")
```

### Function Responsibilities

**`open-note-input!`** - Human interface:
- Shows persistent input box with `ignoreFocusOut: true`
- On submission,
  1. Calls `gather-context` to build environment context
  2. calls `jot-this-down!` with note and context
- No AI interface needed

**`gather-context`** - Context building:
- Environment-dependent but no side effects
- Returns context map with easily computable information
- Used by both human workflow and Copilot chat

**`jot-this-down!`** - Core processing:
- Takes note text and context map
- Performs AI processing and file creation
- Context includes instructions field for data-driven behavior
- Used by human workflow (via `open-note-input!`) and Copilot chat directly

### Workflow Patterns

**Human User:**
```
open-note-input! → gather-context → jot-this-down!
```

**Copilot Chat:**
```
gather-context → augment context → jot-this-down!
```

### Context Map Structure

The context map contains information easily computable without AI:

```clojure
{:workspace {:name "project-name"              ; from workspace root folder name
             :path "/absolute/path/to/workspace"
             :current-file {:path "/path/to/current/file.clj"
                           :language "clojure"}} ; from VS Code active editor

 :temporal {:timestamp "2025-08-23T14:30:00Z"   ; basic timestamp
            :day-of-week "friday"
            :is-weekend false}

 :instructions {:workspace-instructions "content of .joyride/notes/notes-instructions.md"
                :global-instructions "content of <user-joyride-dir>/notes/notes-instructions.md"
                :processing-instructions "human-workflow or copilot-provided"}

 :existing-notes {:count 23                     ; simple file count
                  :directories [".joyride/notes" "~/.config/joyride/notes"]
                  :recent-files ["2025-08-22-bug-fix.md" "2025-08-21-feature-idea.md"]}}
```

## File Organization Strategy

### Directory Structure

```
.joyride/notes/               # Workspace-specific notes
├── notes-instructions.md          # Workspace note organization rules
├── 2025-08-23-auth-bug.md   # Individual note files
├── 2025-08-22-feature-idea.md
└── ...

<user-joyride-dir>/notes/     # Global user notes
├── notes-instructions.md          # Global organization rules
├── 2025-08-23-career-thoughts.md
├── 2025-08-20-learning-goal.md
└── ...
```

### Note File Format

Each note is a standalone markdown file with YAML frontmatter:

```markdown
---
title: "Fix authentication timeout bug"
date: "2025-08-23T14:30:00Z"
tags: ["bug", "authentication", "timeout"]
categories: ["work"]
workspace: "my-app"
related-files: ["src/auth/core.clj", "test/auth/core_test.clj"]
status: "active"
context: "debugging session"
original-note: "Auth timeout bug. /ask-me This might be related to the Redis configuration we changed last week?"
---

# Auth timeout issue

User sessions are timing out after 5 minutes instead of the configured 30 minutes.

Noticed this while testing the login flow. Need to check:
- Session configuration in auth/core.clj
- Redis TTL settings
- Environment variable loading

Investigation needed regarding recent Redis configuration changes.
```

**Note**: The `/ask-me` command triggers AI follow-up questions during processing but is removed from the final note content. The original unprocessed note is preserved in the `original-note` frontmatter field.

## Core Functions

### 1. User Interface

```clojure
(defn open-note-input! []
  "Shows persistent input box for human note capture")
```

**User Invocation**: Since Joyride doesn't support command registration with manifests, users can invoke functions through:
- REPL calls (for development/testing)
- Keyboard shortcuts in keybindings.json calling `joyride.runCode`
- User scripts for custom workflows
- Status bar buttons or custom UI elements

### 2. Context Gathering

```clojure
(defn gather-context []
  "Builds context map from current environment")

(defn get-workspace-info []
  "Returns workspace name, path, current file info")

(defn get-temporal-context []
  "Returns basic timestamp and day info")

(defn load-instructions []
  "Loads content from instruction files")

(defn get-existing-notes-info []
  "Returns basic info about existing notes (count, recent files)")
```

### 3. Note Processing

```clojure
(defn jot-this-down! [note context]
  "Main processing function - calls AI agent with note and context")

(defn call-ai-agent! [note context]
  "Makes AI API call with constructed prompt")

(defn handle-ai-response [response context]
  "Processes AI response, creates files, displays in preview mode")
```

**Special Commands**: `/ask-me` and other syntax is handled within the AI system prompt rather than pre-processing.

**AI Response Flow**: AI agent creates the note file(s) and returns file path(s). The response handler displays the created file in VS Code preview mode.

### 4. Note Management

```clojure
(defn create-note-file! [note-data target-dir]
  "Creates markdown file with frontmatter")

(defn generate-filename [note-data]
  "Creates filename: YYYY-MM-DD-slug.md")
```

**Note Updates**: Note modification and metadata updates are delegated to AI agent tasks rather than specific functions.

### 5. Search and Retrieval

```clojure
(defn show-notes-list! []
  "Shows QuickPick menu with all notes, tags, and categories for navigation")

(defn get-notes-by-tag [tag]
  "Returns all notes with specific tag")

(defn get-notes-by-category [category]
  "Returns all notes in category")

(defn get-related-notes [context]
  "AI-powered: find notes related to current context")
```

**Enhanced UX Design:**
- `show-notes-list!` creates smart QuickPick with fuzzy search enabled
- Menu starts with "Tags" and "Categories" items that filter the list when selected
- Each note item includes "Related Notes" button that calls `get-related-notes`
- Intelligent search configuration allows fuzzy matching on title, content, and tags

### 5. Analysis and Summarization

```clojure
(defn summarize-note-structure []
  "Returns high-level overview of note organization without AI")

(defn analyze-note-patterns []
  "Identifies user's note-taking patterns for AI instructions")

(defn suggest-cleanup []
  "AI-powered suggestions for note organization improvements")
```

## User Interface

### Human Interface Commands

**`open-note-input!` Implementation:**
- Register VS Code command: `joyride.noteManager.openNoteInput`
- Use `vscode/window.showInputBox` with specific options:
  - `ignoreFocusOut: true` (keeps open when losing focus)
  - `prompt: "Jot this down..."`
  - `placeHolder: "Quick note (use /ask-me for follow-up questions)"`
- On submission: calls `gather-context` then `jot-this-down!`

### Copilot Chat Interface

**For Copilot Chat usage:**
- Call `gather-context` to get base context
- Augment context with AI analysis (project type, semantic meaning)
- Add custom instructions to `:processing-instructions` field
- Call `jot-this-down!` with note and augmented context

### Notes List Interface

**Based on git-fuzzy pattern:**
- Use `vscode/window.createQuickPick`
- Load all notes with formatted display
- Enable fuzzy matching on title, content, tags
- Preview functionality on selection
- Action buttons for edit/delete/copy/related notes

### Search Result Format

```clojure
{:label "Fix authentication timeout bug"
 :description "$(tag) bug authentication timeout"
 :detail "2025-08-23 - work - my-app workspace"
 :file-path "/path/to/note.md"
 :buttons [{:name "edit" :iconPath "edit" :tooltip "Edit note"}
           {:name "copy" :iconPath "copy" :tooltip "Copy content"}
           {:name "related" :iconPath "references" :tooltip "Find related notes"}]}
```

## AI Agent System Prompt

The AI agent receives context-aware instructions for intelligent note organization:

1. **Role Definition**: "You are a contextual note organization assistant"
2. **Primary Goals**: Organize notes intelligently, maintain consistency, ask clarifying questions when needed
3. **Context Utilization**: Use minimal temporal info to derive semantic meaning (time-of-day → work focus, season → planning cycles)
4. **Organization Principles**: File naming, frontmatter standards, tag/category strategies
5. **Special Commands**: Handle `/ask-me` to trigger follow-up questions using `joyride_request_human_input` tool
6. **Human Escalation**: When to use `joyride_request_human_input` tool for guidance

## Implementation Phases

### Phase 1: Core Infrastructure
- [ ] Context gathering functions
- [ ] Basic note creation and file management
- [ ] Simple AI processing (without special commands)

### Phase 2: User Interface
- [ ] Input box implementation with persistence
- [ ] Basic notes list interface
- [ ] User invocation methods (keybindings, scripts)

### Phase 3: Advanced Features
- [ ] `/ask-me` command processing
- [ ] Related notes discovery
- [ ] Pattern analysis and suggestions

### Phase 4: Intelligence Enhancements
- [ ] Adaptive organization learning
- [ ] Temporal pattern recognition
- [ ] Cross-workspace note relationships

## Configuration Files

### Workspace Instructions Template (.joyride/notes/notes-instructions.md)

**Purpose**: Complements the system prompt with workspace-specific organization guidance.

```markdown
# Workspace Note Organization Instructions

## Project Context
This is a [project type] project focused on [main purpose].

## Organization Preferences
- Use tags for: technical concepts, bug types, feature areas
- Use categories for: work phases, note types (avoid urgency unless specifically needed)
- File naming: prefer descriptive slugs over generic names

## Special Considerations
- Bug reports should include affected components in tags
- Feature ideas should be categorized by implementation complexity
- Meeting notes should reference attendees in metadata

## Related Files and Patterns
[Project-specific guidance based on codebase analysis]
```

### Global Instructions Template

; FEEDBACK: This should probably include an “About me” section where the agent can learn about the human. (I don't think the agent we control will have access to Copilot's instructions and such.)

```markdown
# Global Note Organization Instructions

## About Me
[Personal context for the AI agent to understand the human]
- Role: [job title, responsibilities]
- Interests: [technical interests, learning goals]
- Work style: [preferences for organization, communication]
- Context: [current projects, focus areas]

## Personal Organization Style
[User's preferred organization patterns]

## Cross-Project Categories
- learning: new technologies, tutorials, insights
- career: job opportunities, skills development, industry trends
- personal: non-work thoughts that deserve capture

## Integration Preferences
- Work notes stay in workspace directories
- Personal insights move to global space
- Learning notes: duplicate to both if work-related

## Review and Cleanup
- Weekly: review and re-categorize if needed
- Monthly: archive completed items
- Quarterly: analyze patterns and update instructions
```

## Error Handling

- **AI Service Unavailable**: Fall back to manual file creation with basic frontmatter
- **Invalid Note Content**: Sanitize and proceed with warnings
- **File System Errors**: Retry with alternative naming/location
- **Context Gathering Failures**: Proceed with partial context, note limitations

## Testing Strategy

- Unit tests for context gathering functions
- Integration tests for AI agent interactions
- Manual testing for UI responsiveness and focus behavior
