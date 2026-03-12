---
name: hook-writer
description: Create Claude Code hooks for automating workflows. Trigger with "/hook-writer" or "create hook". Hooks run shell commands in response to Claude Code events like tool calls or notifications.
---

# Hook Writer

## Purpose

Create Claude Code hooks that automate workflows by running shell commands in response to events.

## When to Use

- User wants to create a new hook
- User says "create hook", "new hook", or "/hook-writer"
- User wants to run commands automatically when Claude does something

## Hook Events

| Event | Description |
|-------|-------------|
| `PreToolUse` | Before a tool is executed |
| `PostToolUse` | After a tool completes |
| `Notification` | When Claude sends a notification |
| `Stop` | When Claude stops responding |
| `SubagentStop` | When a subagent completes |

## Hook Configuration Location

Add hooks to `settings.json`:
- User-level: `~/.claude/settings.json`
- Project-level: `.claude/settings.json`

## Hook Structure

```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Bash",
        "hooks": [
          {
            "type": "command",
            "command": "echo 'Running bash command'"
          }
        ]
      }
    ],
    "PostToolUse": [
      {
        "matcher": "Write",
        "hooks": [
          {
            "type": "command",
            "command": "echo 'File written: $TOOL_INPUT_file_path'"
          }
        ]
      }
    ]
  }
}
```

## Environment Variables

Hooks receive context via environment variables:

| Variable | Description |
|----------|-------------|
| `$TOOL_NAME` | Name of the tool being called |
| `$TOOL_INPUT_<param>` | Tool input parameters |
| `$TOOL_OUTPUT` | Tool output (PostToolUse only) |
| `$SESSION_ID` | Current session ID |

## Hook Types

### Command Hook
```json
{
  "type": "command",
  "command": "your-shell-command"
}
```

### Script Hook
```json
{
  "type": "command",
  "command": "bash /path/to/script.sh"
}
```

## Common Use Cases

1. **Auto-format on file write**
```json
{
  "matcher": "Write",
  "hooks": [{"type": "command", "command": "prettier --write $TOOL_INPUT_file_path"}]
}
```

2. **Run tests after edits**
```json
{
  "matcher": "Edit",
  "hooks": [{"type": "command", "command": "npm test"}]
}
```

3. **Lint on save**
```json
{
  "matcher": "Write",
  "hooks": [{"type": "command", "command": "eslint --fix $TOOL_INPUT_file_path"}]
}
```

## Workflow

1. **Identify need**: What should trigger automatically?
2. **Choose event**: PreToolUse or PostToolUse?
3. **Define matcher**: Which tool(s) should trigger it?
4. **Write command**: What should run?
5. **Add to settings**: Update settings.json
6. **Test**: Verify hook executes correctly

## Output

Add hook configuration to:
- `~/.claude/settings.json` (user-level)
- `.claude/settings.json` (project-level)
