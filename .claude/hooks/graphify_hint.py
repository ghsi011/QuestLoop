#!/usr/bin/env python3
"""PreToolUse hook: nudge toward graphify before raw code exploration.

Replaces the old inline hooks in .claude/settings.json, which re-injected the
same paragraph on EVERY Read/Glob/search command — pure context waste after the
first time. This version fires at most once per session (marker file in /tmp
keyed by session_id) and only when graphify-out/graph.json actually exists.
"""
import json
import os
import sys

SRC_EXTS = (
    ".py", ".js", ".ts", ".tsx", ".jsx", ".go", ".rs", ".java", ".rb",
    ".c", ".h", ".cpp", ".hpp", ".cc", ".cs", ".kt", ".kts", ".swift",
    ".php", ".scala", ".lua", ".sh", ".md", ".rst", ".txt", ".mdx",
)
SEARCH_CMDS = ("grep", "rg ", "ripgrep", "find ", "fd ", "ack ", "ag ")

HINT = (
    "graphify-out/graph.json exists. Orient with `graphify query \"<question>\"` "
    "(or `graphify explain`/`graphify path`) before wide greps or raw file reads; "
    "read raw files only after graphify has oriented you, or to modify/debug "
    "specific lines. Include this rule in code-exploration subagent prompts. "
    "(This hint is shown once per session.)"
)


def wants_hint(data):
    tool = data.get("tool_name") or ""
    tool_input = data.get("tool_input") or {}
    if tool == "Bash":
        cmd = str(tool_input.get("command") or "")
        return any(k in cmd for k in SEARCH_CMDS)
    # Read / Glob
    s = " ".join(
        str(tool_input.get(k) or "") for k in ("file_path", "pattern", "path")
    ).lower().replace("\\", "/")
    return "graphify-out/" not in s and any(e in s for e in SRC_EXTS)


def main():
    try:
        data = json.load(sys.stdin)
    except Exception:
        return
    if not os.path.isfile("graphify-out/graph.json"):
        return
    marker = "/tmp/.graphify-hint-%s" % (data.get("session_id") or "nosession")
    if os.path.exists(marker) or not wants_hint(data):
        return
    try:
        open(marker, "w").close()
    except OSError:
        pass
    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "additionalContext": HINT,
        }
    }))


if __name__ == "__main__":
    main()
