#!/usr/bin/env python3
"""PreToolUse(Bash) hook: block `git push` until the review gate has run.

AGENTS.md "Process" requires every code change to pass the code-reviewer agent
(plus the skeptic for risky changes) before pushing. After the final reviewed
commit, stamp:   git rev-parse HEAD > .git/questloop-review-stamp

Detection tokenizes the command (shlex), so a `git push` that is merely
mentioned inside a quoted string — a commit message, a heredoc line — does not
trigger the gate; only an actual `git [opts] push` invocation does.
Exit code 2 + stderr blocks the tool call and shows the message to the agent.
"""
import json
import shlex
import subprocess
import sys

BLOCK_MESSAGE = """\
Review gate: HEAD has not been reviewed (.git/questloop-review-stamp missing or stale).
Run the mandatory review from AGENTS.md "Process": spawn the code-reviewer agent on the
final diff (plus the skeptic agent, in parallel, for risky changes), fix confirmed
findings, re-test, then stamp AS A SEPARATE COMMAND before pushing:
    git rev-parse HEAD > .git/questloop-review-stamp
(The gate is checked before your command runs, so stamping and pushing in one command
will still be blocked.) Docs/CI-only diffs may stamp directly without review.
"""

# git options that consume a separate following argument
ARG_OPTS = {"-C", "-c", "--git-dir", "--work-tree", "--exec-path", "--namespace"}


def is_git_push(command):
    try:
        tokens = shlex.split(command)
    except ValueError:
        tokens = command.split()
    i = 0
    while i < len(tokens):
        tok = tokens[i]
        if tok == "git" or tok.endswith("/git"):
            j = i + 1
            while j < len(tokens) and tokens[j].startswith("-"):
                j += 2 if tokens[j] in ARG_OPTS else 1
            if j < len(tokens) and tokens[j] == "push":
                return True
            i = j
        else:
            i += 1
    return False


def main():
    try:
        data = json.load(sys.stdin)
        command = str((data.get("tool_input") or {}).get("command") or "")
    except Exception:
        return 0
    if not is_git_push(command):
        return 0
    try:
        head = subprocess.run(
            ["git", "rev-parse", "HEAD"], capture_output=True, text=True, check=True
        ).stdout.strip()
    except Exception:
        return 0  # not a git repo / git unavailable: fail open
    try:
        with open(".git/questloop-review-stamp") as f:
            stamp = f.read().strip()
    except OSError:
        stamp = None  # no stamp yet: block
    if head and stamp == head:
        return 0
    sys.stderr.write(BLOCK_MESSAGE)
    return 2


if __name__ == "__main__":
    sys.exit(main())
