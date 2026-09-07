#!/usr/bin/env bash
# PostToolUse hook — fires when a file is written into the Claude Code
# auto-memory store, and reminds the session to route anything cloud-relevant
# into a *tracked* doc as well.
#
# Why this exists: the auto-memory store lives in ~/.claude/projects/<slug>/memory/,
# which no cloud session and no other machine can see. Portable knowledge that
# only lands there is invisible to every cloud agent. The repo's answer is
# curated promotion (docs/GOTCHAS.md, docs/PATTERNS.md, docs/BACKLOG.md) rather
# than syncing the store — the store also holds host-specific facts (JDK paths,
# emulator flags, keystore locations) that would mislead a cloud sandbox and do
# not belong in a public repo.
#
# This hook does not block and does not judge the content. It only ensures the
# routing question gets asked at the moment a memory is written, instead of
# depending on someone remembering the rule.
#
# Exit 0 always. Output is JSON on stdout: additionalContext is surfaced to the
# model as a note, not to the user as an error.
set -euo pipefail

input=$(cat)

path=$(printf '%s' "$input" | jq -r '.tool_input.file_path // empty')
[ -n "$path" ] || exit 0

# Match the auto-memory store on any host: .../.claude/projects/<slug>/memory/...
case "$path" in
    */.claude/projects/*/memory/*) ;;
    *) exit 0 ;;
esac

# The index itself is a pointer file; the routing question is about facts.
case "$(basename "$path")" in
    MEMORY.md) exit 0 ;;
esac

jq -n --arg p "$path" '{
  hookSpecificOutput: {
    hookEventName: "PostToolUse",
    additionalContext: (
      "[promote-memory] You just wrote to the local auto-memory store (\($p)).\n" +
      "That path is invisible to cloud sessions and to every other machine. Before moving on, route it:\n" +
      "  • Host-independent trap or lesson  -> also add to docs/GOTCHAS.md\n" +
      "  • Named, reusable convention       -> also add to docs/PATTERNS.md (+ a one-line pointer in CLAUDE.md)\n" +
      "  • Shipped but not yet verified     -> also add to docs/BACKLOG.md, section \"Pending on-device verification\"\n" +
      "  • Host-specific (JDK path, emulator flag, device serial, keystore, this machine only)\n" +
      "                                     -> local memory ONLY. Do not commit it; the repo is public.\n" +
      "If it is already covered by a tracked doc, do nothing."
    )
  }
}'
