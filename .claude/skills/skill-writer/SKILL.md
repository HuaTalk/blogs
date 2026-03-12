---
name: skill-writer
description: Create new Claude Code skills. Trigger with "/skill-writer" or "create skill". Guides through skill creation with proper frontmatter, structure, and best practices.
---

# Skill Writer

## Purpose

Create well-structured Claude Code skills following the Agent Skills open standard.

## When to Use

- User wants to create a new skill
- User says "create skill", "new skill", or "/skill-writer"
- User wants to automate a repeating workflow

## Skill Structure

Every skill needs:

1. **SKILL.md** - Main skill definition file
2. **Frontmatter** - YAML metadata block
3. **Instructions** - Markdown body with clear guidance

## Required Frontmatter

```yaml
---
name: skill-name           # lowercase, hyphenated
description: Brief description of what the skill does and when to trigger it
---
```

## Optional Frontmatter

```yaml
---
name: skill-name
description: ...
version: "1.0.0"           # Semantic versioning
min_claude_code_version: "1.0.0"
---
```

## Skill Body Structure

```markdown
# Skill Name

## Overview
One paragraph explaining the skill's purpose.

## When to Use
- Trigger condition 1
- Trigger condition 2

## How It Works
Step-by-step process the skill follows.

## Output Specification
Where and how outputs should be saved.

## Examples
Concrete usage examples.
```

## Best Practices

1. **Clear trigger conditions** - Define exactly when to activate
2. **Specific outputs** - Specify file paths and formats
3. **Actionable instructions** - Tell Claude what to DO, not what to know
4. **Examples** - Include concrete examples of inputs/outputs
5. **Fail-safe behavior** - Handle edge cases gracefully

## Installation Locations

| Scope | Path |
|-------|------|
| Personal | `~/.claude/skills/<name>/SKILL.md` |
| Project | `.claude/skills/<name>/SKILL.md` |

## Workflow

1. **Ask**: What should this skill do?
2. **Define**: Name, description, trigger conditions
3. **Structure**: Write the SKILL.md content
4. **Save**: Output to appropriate skills directory
5. **Test**: Verify skill triggers correctly

## Output

Save new skills to: `~/.claude/skills/<skill-name>/SKILL.md`

For project-specific skills: `.claude/skills/<skill-name>/SKILL.md`
