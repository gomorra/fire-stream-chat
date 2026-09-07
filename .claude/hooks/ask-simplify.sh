#!/usr/bin/env bash
# Fires before a git commit — asks the user whether to run /simplify first.
# If yes: blocks the commit (exit 2) and tells Claude to run /simplify.
# If no:  lets the commit proceed (exit 0).
#
# /simplify is the QUALITY gate (reuse, simplification, efficiency). For
# correctness bugs the tool is /code-review — see CLAUDE.md "Review tools".

input=$(cat)
command=$(echo "$input" | jq -r '.tool_input.command // empty' 2>/dev/null)

# Only trigger on git commit commands
if ! echo "$command" | grep -qE '^git commit'; then
    exit 0
fi

read -r -p "Run /simplify before committing? [y/N] " answer < /dev/tty

if [[ "$answer" =~ ^[Yy]$ ]]; then
    echo 'User wants to run /simplify before committing. Invoke Skill(skill: "simplify") now to review all changed code for quality, then re-attempt the commit.' >&2
    exit 2
fi

exit 0
