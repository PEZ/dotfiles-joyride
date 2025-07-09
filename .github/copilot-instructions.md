# Joyride User Project - AI Assistant Instructions

> **Essential**: Always start by using `joyride_basics_for_agents` and `joyride_assisting_users_guide` tools for comprehensive, up-to-date Joyride guidance.

## Project-Specific Patterns

### Naming Conventions
- All scripts and source files use **snake_case** filenames (e.g., `awesome_copilot.cljs`, `git_fuzzy.cljs`)
- Namespace names use kebab-case mapping (e.g., `awesome_copilot.cljs` → `awesome-copilot`)

### Key Project Scripts
- `scripts/user_activate.cljs` - User activation script (currently minimal)
- `scripts/awesome_copilot.cljs` - GitHub Copilot instructions browser/installer
- `scripts/awesome_cursorrules_to_copilot.cljs` - Cursor rules to Copilot converter
- `src/git_fuzzy.cljs` - Git history fuzzy search (example integration)
- `src/my_lib.cljs` - Utility functions library

### Project-Specific Integration Points
- Multiple scripts for managing AI coding assistance (Copilot instructions, prompts, chatmodes)
- Integration with GitHub's awesome-copilot repository for content management
- Cursor rules conversion workflows
- Custom VS Code automation focused on developer productivity

### Development Notes
- Rich comment blocks often end with `:rcf` marker
- Project contains extensive archive/ directory with experimental code
- Heavy focus on AI-assisted development workflows and VS Code customization
