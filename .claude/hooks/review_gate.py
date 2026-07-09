#!/usr/bin/env python3
"""PreToolUse(Bash) hook: block `git push` until the review gate has run.

AGENTS.md "Process" requires every code change to pass the code-reviewer agent
(plus the skeptic for risky changes) before pushing. After the final reviewed
commit, stamp (works in linked worktrees too):
    git rev-parse HEAD > "$(git rev-parse --git-path questloop-review-stamp)"

The gate is diff-aware: when it blocks, it classifies HEAD's diff against
origin/main (docs-only / standard / risky) so the agent is told mechanically —
not by self-assessment — whether the skeptic is required and whether the
docs-only carve-out actually applies.

Detection tokenizes the command with shell punctuation awareness and recurses
into `sh|bash -c "..."` strings, so quoted mentions of a push (commit messages,
docs heredocs) don't trigger it, while wrapped or chained pushes still do.
A command that both moves HEAD (commit/merge/rebase/...) and pushes is always
blocked: the stamp is checked against the pre-command HEAD and cannot certify
what such a command would push. Exit 2 + stderr blocks the call.
"""
import json
import re
import shlex
import subprocess
import sys

# git options that consume a separate following argument
ARG_OPTS = {"-C", "-c", "--git-dir", "--work-tree", "--exec-path", "--namespace"}
HEAD_MUTATORS = {"commit", "merge", "rebase", "cherry-pick", "revert", "pull", "am", "reset"}
SHELLS = {"sh", "bash", "zsh", "dash", "ksh"}

DOCS_PREFIXES = ("docs/", ".github/", ".claude/")
DOCS_FILES = (".gitignore", ".gitattributes", ".editorconfig")
RISKY_PATTERNS = (
    ("/widget/", "widget"),
    ("reminder", "reminders/alarms"),
    ("boot", "boot receiver"),
    ("/ai/", "AI pipeline"),
    ("securekeystore", "credentials"),
    ("app/schemas/", "Room schema"),
    ("/data/", "data layer / ledger"),
)
RISKY_LINE_THRESHOLD = 150

STAMP_CMD = 'git rev-parse HEAD > "$(git rev-parse --git-path questloop-review-stamp)"'


def tokenize(command):
    lex = shlex.shlex(command, posix=True, punctuation_chars=True)
    lex.whitespace_split = True
    try:
        return list(lex)
    except ValueError:
        return command.split()


def is_punct(tok):
    return bool(re.fullmatch(r"[;&|()<>]+", tok))


def analyze(command, depth=0):
    """Find actual git-push invocations and HEAD-mutating git subcommands.

    Returns {"pushes": [list-of-args-after-push, ...], "mutators": [subcmd, ...]}.
    Recurses into `sh|bash -c "<string>"` so wrapped pushes are still seen.
    """
    result = {"pushes": [], "mutators": []}
    if depth > 3:
        return result
    tokens = tokenize(command)
    i = 0
    while i < len(tokens):
        tok = tokens[i]
        if tok == "git" or tok.endswith("/git"):
            j = i + 1
            while j < len(tokens) and tokens[j].startswith("-") and not is_punct(tokens[j]):
                j += 2 if tokens[j] in ARG_OPTS else 1
            if j < len(tokens):
                sub = tokens[j]
                if sub == "push":
                    args, k = [], j + 1
                    while k < len(tokens) and not is_punct(tokens[k]):
                        args.append(tokens[k])
                        k += 1
                    result["pushes"].append(args)
                    i = k
                    continue
                if sub in HEAD_MUTATORS:
                    result["mutators"].append(sub)
            i = j + 1
            continue
        if tok in SHELLS or any(tok.endswith("/" + s) for s in SHELLS):
            k = i + 1
            while k < len(tokens) and not is_punct(tokens[k]):
                if tokens[k] == "-c" and k + 1 < len(tokens):
                    inner = analyze(tokens[k + 1], depth + 1)
                    result["pushes"] += inner["pushes"]
                    result["mutators"] += inner["mutators"]
                    k += 1
                k += 1
            i = k
            continue
        i += 1
    return result


def push_ref_sources(args):
    """Source refs named by a push's refspecs ('' for deletions)."""
    positional = [a for a in args if not a.startswith("-") and not a.isdigit()]
    sources = []
    for spec in positional[1:]:  # positional[0] is the remote
        src = spec.lstrip("+").split(":", 1)[0]
        sources.append(src)
    return sources


def git(*args):
    return subprocess.run(
        ["git"] + list(args), capture_output=True, text=True, check=True
    ).stdout.strip()


def classify_diff():
    """Classify HEAD's diff vs origin/main: (n_files, docs_only, risky, product_lines).

    Returns None when no base ref is resolvable (classification unavailable).
    """
    out = None
    for basis in ("origin/main...HEAD", "@{upstream}...HEAD", "main...HEAD"):
        r = subprocess.run(
            ["git", "diff", "--numstat", basis], capture_output=True, text=True
        )
        if r.returncode == 0:
            out = r.stdout
            break
    if out is None:
        return None
    n_files, docs_only, risky, product_lines = 0, True, set(), 0
    for line in out.splitlines():
        parts = line.split("\t")
        if len(parts) != 3:
            continue
        added, deleted, path = parts
        n_files += 1
        lp = path.lower()
        if (lp.endswith(".md") or lp in DOCS_FILES
                or any(lp.startswith(p) for p in DOCS_PREFIXES)):
            continue
        docs_only = False
        try:
            product_lines += int(added) + int(deleted)
        except ValueError:
            pass  # binary file
        for pattern, label in RISKY_PATTERNS:
            if pattern in lp:
                risky.add(label)
    if product_lines > RISKY_LINE_THRESHOLD:
        risky.add(">%d product-source lines" % RISKY_LINE_THRESHOLD)
    return n_files, docs_only and n_files > 0, risky, product_lines


def block_message():
    lines = ["Review gate: HEAD has not been reviewed (stamp missing or stale)."]
    info = classify_diff()
    if info is None:
        lines.append(
            "Diff classification unavailable (no origin/main) - assume the change "
            "needs the code-reviewer agent; add the skeptic if it touches "
            "economy/ledger/schema, credentials/AI, widget/reminders/boot, or is large."
        )
    else:
        n_files, docs_only, risky, product_lines = info
        if n_files == 0:
            lines.append(
                "No content diff vs origin/main (empty or already-merged commit, e.g. "
                "a [release] commit) - stamp directly and re-push."
            )
        elif docs_only:
            lines.append(
                "This diff is docs/CI/config-only (%d files, no product source) - "
                "no review required; stamp directly and re-push." % n_files
            )
        elif risky:
            lines.append(
                "This diff is RISKY (%s) - spawn BOTH the code-reviewer AND skeptic "
                "agents, in parallel, on the final diff (see AGENTS.md \"Process\")."
                % ", ".join(sorted(risky))
            )
        else:
            lines.append(
                "Standard change (%d product-source lines) - spawn the code-reviewer "
                "agent on the final diff (see AGENTS.md \"Process\")." % product_lines
            )
        if n_files > 0 and not docs_only:
            lines.append(
                "Fix confirmed findings, re-run the affected tests, commit."
            )
    lines.append("Then stamp AS A SEPARATE COMMAND and re-push:")
    lines.append("    " + STAMP_CMD)
    lines.append(
        "(The gate is checked before your command runs; stamping and pushing in one "
        "command stays blocked. HEAD moves without new code - merge from main, clean "
        "rebase of reviewed work - may re-stamp directly.)"
    )
    return "\n".join(lines) + "\n"


def main():
    try:
        data = json.load(sys.stdin)
        command = str((data.get("tool_input") or {}).get("command") or "")
    except Exception:
        return 0
    found = analyze(command)
    if not found["pushes"]:
        return 0
    try:
        head = git("rev-parse", "HEAD")
        stamp_path = git("rev-parse", "--git-path", "questloop-review-stamp")
    except Exception:
        return 0  # not a git repo / git unavailable: fail open
    if found["mutators"]:
        sys.stderr.write(
            "Review gate: this command both moves HEAD (git %s) and pushes, so the "
            "stamp cannot certify what would be pushed. Commit first; then review, "
            "stamp (%s), and push as separate commands.\n"
            % (", ".join(sorted(set(found["mutators"]))), STAMP_CMD)
        )
        return 2
    try:
        with open(stamp_path) as f:
            stamp = f.read().strip()
    except OSError:
        stamp = None  # no stamp yet: block below
    if not head or stamp != head:
        sys.stderr.write(block_message())
        return 2
    # Stamped — but the stamp certifies HEAD only; block pushes naming other refs.
    try:
        branch = git("rev-parse", "--abbrev-ref", "HEAD")
    except Exception:
        branch = "HEAD"
    for args in found["pushes"]:
        for src in push_ref_sources(args):
            if src and src not in ("HEAD", branch):
                sys.stderr.write(
                    "Review gate: the stamp certifies HEAD (%s) only, but this push "
                    "names '%s'. Check out that ref, review it, stamp, and push it "
                    "from HEAD - or push the stamped branch instead.\n" % (branch, src)
                )
                return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
