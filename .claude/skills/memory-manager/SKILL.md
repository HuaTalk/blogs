---
name: memory-manager
description: Manage Claude Code's persistent auto memory. Trigger with "/memory" or "manage memory". View, update, organize, and clean up memory files that persist across sessions.
---

# Memory Manager

## Purpose

Help users manage Claude Code's persistent auto memory system.

## When to Use

- User says "manage memory", "/memory", "show memories"
- User wants to see what Claude remembers
- User wants to organize or clean up memory files
- User says "forget X" or "remember Y"

## Memory Location

- **Global**: `~/.claude/projects/<project-hash>/memory/`
- **Project-specific**: `.claude/memory/` (if exists)

## Memory Files

| File | Purpose |
|------|---------|
| `MEMORY.md` | Main memory file, always loaded (keep under 200 lines) |
| `*.md` | Topic-specific files linked from MEMORY.md |

## Commands

### View All Memories
```bash
cat ~/.claude/projects/*/memory/MEMORY.md
ls ~/.claude/projects/*/memory/
```

### View Current Project Memory
Read the MEMORY.md in the current project's memory directory.

### Clean Up Memories
- Remove outdated or incorrect entries
- Consolidate duplicate information
- Move detailed content to topic files

## MEMORY.md Structure

```markdown
# Project Memory

## Preferences
- Key user preferences

## Patterns
- Established code patterns

## Architecture
- Important architectural decisions

## See Also
- [Topic Details](./topic.md)
```

## Best Practices

1. **Keep MEMORY.md concise** - Under 200 lines, summarize and link to details
2. **Organize by topic** - Create separate files for detailed notes
3. **Update, don't duplicate** - Check existing memories before adding
4. **Remove stale info** - Delete outdated or wrong memories
5. **Verify before saving** - Don't save speculative conclusions

## What to Save

- Stable patterns confirmed across multiple interactions
- User preferences for workflow and tools
- Key architectural decisions
- Solutions to recurring problems

## What NOT to Save

- Session-specific context
- Incomplete or unverified information
- Content that duplicates CLAUDE.md
- Speculative conclusions

## Actions

1. **List memories**: Show all memory files
2. **View memory**: Display MEMORY.md contents
3. **Add memory**: Save new verified pattern/preference
4. **Update memory**: Modify existing entry
5. **Remove memory**: Delete outdated/incorrect entry
6. **Organize**: Restructure for better organization
