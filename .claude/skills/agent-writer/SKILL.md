---
name: agent-writer
description: Create custom Claude Code subagent definitions. Trigger with "/agent-writer" or "create agent". Define specialized agents with specific tools, behaviors, and capabilities for complex workflows.
---

# Agent Writer

## Purpose

Create custom subagent definitions for Claude Code's Agent tool.

## When to Use

- User wants to create a custom subagent
- User says "create agent", "new agent", or "/agent-writer"
- User needs a specialized agent for repeating tasks

## Agent Definition Location

Add agent definitions to settings or create agent files:
- `~/.claude/agents/<agent-name>.md` (personal)
- `.claude/agents/<agent-name>.md` (project)

## Agent Definition Structure

```markdown
---
name: agent-name
description: Brief description shown in Agent tool
model: sonnet                    # Optional: sonnet, opus, haiku
tools:                           # Optional: restrict available tools
  - Read
  - Grep
  - Glob
  - WebFetch
---

# Agent Name

## Purpose
What this agent does.

## Capabilities
- Capability 1
- Capability 2

## Instructions
Step-by-step guidance for the agent.

## Output Format
How results should be formatted.
```

## Built-in Agent Types

| Type | Description | Tools |
|------|-------------|-------|
| `general-purpose` | Multi-step tasks | All tools |
| `Explore` | Codebase exploration | Read-only tools |
| `Plan` | Implementation planning | Read-only tools |
| `claude-code-guide` | Claude Code documentation | Read-only + web |

## Tool Restrictions

Agents can be restricted to specific tools:

```yaml
tools:
  - Read      # Read files
  - Glob      # Find files by pattern
  - Grep      # Search file contents
  - Bash      # Run commands
  - WebFetch  # Fetch URLs
  - WebSearch # Search web
```

## Example Agents

### Code Reviewer Agent
```markdown
---
name: code-reviewer
description: Review code for bugs, security issues, and best practices
model: sonnet
tools: [Read, Glob, Grep]
---

# Code Reviewer

## Purpose
Thoroughly review code changes for quality and security.

## Process
1. Identify changed files
2. Read and analyze each file
3. Check for common issues
4. Report findings with line numbers

## Output
- Security issues (critical)
- Bugs (high)
- Code smells (medium)
- Style suggestions (low)
```

### Documentation Agent
```markdown
---
name: doc-generator
description: Generate documentation from code
model: haiku
tools: [Read, Glob, Grep, Write]
---

# Documentation Generator

## Purpose
Generate markdown documentation from source code.

## Process
1. Scan for public APIs
2. Extract docstrings and comments
3. Generate structured documentation
```

## Best Practices

1. **Clear purpose** - Define exactly what the agent does
2. **Minimal tools** - Only grant necessary tool access
3. **Specific output** - Define expected output format
4. **Model selection** - Use haiku for simple tasks, opus for complex
5. **Instructions** - Be explicit about process and expectations

## Workflow

1. **Identify need**: What task needs a specialized agent?
2. **Define scope**: What tools and capabilities needed?
3. **Write definition**: Create the agent markdown file
4. **Save**: Place in agents directory
5. **Test**: Invoke via Agent tool with subagent_type
