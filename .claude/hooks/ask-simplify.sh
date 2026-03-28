#!/usr/bin/env bash
# Fires before a git commit — asks user if they want /simplify-review run first.
# If yes: blocks the commit (exit 2) and tells Claude to run /simplify-review.
# If no:  lets the commit proceed (exit 0).

input=$(cat)
command=$(echo "$input" | jq -r '.tool_input.command // empty' 2>/dev/null)

# Only trigger on git commit commands
if ! echo "$command" | grep -qE '^git commit'; then
    exit 0
fi

read -r -p "Run /simplify before committing? [y/N] " answer < /dev/tty

if [[ "$answer" =~ ^[Yy]$ ]]; then
    echo "User wants to run /simplify-review before committing. Invoke the /simplify-review skill now (always Sonnet/Medium) to review all changed code for quality, then re-attempt the commit." >&2
    exit 2
fi

exit 0
