# Fork workflow for this local copy

This checkout tracks two repositories: the original Picsou project and the personal fork used for local changes. Use this workflow to review upstream changes, keep the fork current, and develop custom changes without mixing them into `main` too early.

## Current remotes

| Remote | Purpose | URL |
|--------|---------|-----|
| `origin` | Personal fork. Push your branches here. | `https://github.com/amartidandqdq/picsou-finance.git` |
| `upstream` | Original project. Fetch updates from here. | `https://github.com/Cloeille/picsou-finance.git` |

The `upstream` push URL is intentionally disabled so local commands cannot accidentally push to the original project.

## Daily start

```bash
git fetch upstream
git fetch origin
git status --short --branch
```

Use this before asking OpenCode to analyze or implement anything. It shows whether upstream moved and whether local work is still clean.

## See what changed upstream

```bash
git log main..upstream/main --oneline
git diff main..upstream/main --stat
git diff main..upstream/main
```

Use this when you want OpenCode to explain what the original project changed before merging it.

## Update local `main` from upstream

Only do this when your working tree is clean.

```bash
git switch main
git merge upstream/main
git push origin main
```

Result: your fork's `main` matches the original project plus any accepted fork-level baseline commits.

## Develop your own changes

Always work on a branch, not directly on `main`.

```bash
git switch main
git pull --ff-only origin main
git switch -c alex/my-change
```

After edits:

```bash
git status --short
git diff
git add <files>
git commit -m "fix(scope): describe change"
git push -u origin alex/my-change
```

## Rebase your branch after upstream moves

```bash
git fetch upstream
git switch main
git merge upstream/main
git switch alex/my-change
git rebase main
```

If conflicts appear, stop and ask OpenCode to resolve them. Do not guess during conflict resolution; conflicts are where upstream and local intent collide.

## What to ask OpenCode

Good prompts:

```text
Fetch upstream and summarize what changed since my fork main.
```

```text
Analyze upstream/main vs main and tell me if my local branch needs changes.
```

```text
Rebase alex/my-change on latest upstream and resolve conflicts safely.
```

```text
Implement this feature on a new alex/... branch and keep upstream compatibility in mind.
```

## Safety checklist

- [ ] `git status --short --branch` checked before edits.
- [ ] Work happens on `alex/...` branch, not directly on `main`.
- [ ] `upstream` is fetched before comparing or rebasing.
- [ ] `upstream` push URL remains disabled.
- [ ] Docs are updated with related code changes.
