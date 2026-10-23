# ztk Phase 5 (Migration and skills) Implementation Plan

**Goal:** Move the knowledge base from the bash tool to ztk once, by fixed rules and without an agent deciding anything, and give agents the skills to use ztk properly: the equivalents of the user's existing skills first, extras only when they earn their place in context. Ends with the **Switch**, one Monday on which everything changes at once.

**Spec:** `docs/specs/2026-10-15-ztk-design.md` §4.4, §9 (migration), §10 (skills), §11 (skills check), §12 (rehearsal and switch), §13. Domain terms: `docs/CONTEXT.md` (Progress log, Switch).

**Out of scope:** a `ztk migrate` command and `ztk install-skills` (dropped: each is done once, by one user); a plan file, fingerprints or a verify step in the migration (git is the safety net: commit, review `git diff`, revert); rewriting frontmatter `date` or quoting values (ztk never reads `date`); converting `progress*.md` into sessions; deleting `000-Project-Index.md` (the user does it by hand); `ztk-papers` (a short part of `ztk`); rehearsing skills beyond trying them on the rehearsal copy (they are tested by use).

**Plan status:** a draft. The order below is a suggestion.

## Where we left off (2026-10-23, Tasks 1 to 3 done)

The design was settled in an interview on 2026-10-22 and written into the spec and `CONTEXT.md`. The Phase 4 end-of-phase review was skipped.

Task 1: `migration/migrate.sh <knowledge-base>` is written and was tried on a made-up knowledge base in the scratchpad (root notes, weekly statuses with the shift, a task with one note, one with two, one with none, a note of a deleted task, a note with an empty `task_uuid`). The user rehearsed it on `/tmp/ztk-rehearsal` (kept for Task 3): 9 root notes and 34 weekly statuses moved as 100% renames, 262 tasks linked, the expected 21 cases printed (on stderr: run it with `2>&1 | tee`), and `check` unchanged. The try-out started early, through a scratchpad wrapper that refuses to run unless both configurations point at the copy. Fixes from it, done and checked on the copy with a new binary:

- `graph` accepts a note's path or name ending in `.md`.
- A task in a summary changed only after the period is marked `(untouched)`, without a day; the JSON keeps the day.
- `add` offers `project:<name>` for a word that is the start of `project:`.
- Period completion: `summary` (previous week, current and previous quarter and year), `goal list`/`effort list` (previous, current and next quarter, current and next year).
- `add` and `start` print the note path in `note`, as every command printing a task does.

Skipped: the effort slug rule (its hint suggests `ztkswitch` for `ZtkSwitch`; minor).

Task 2: `skills/ztk`, `skills/ztk-summary`, `skills/ztk-planning` and `skills/AGENTS.md` are written, and the native smoke test checks every `` `ztk …` `` they mention against the binary's help (tried alone on `ztk2`: the real skills pass, planted misses are reported). The drafts of the in-place edits are under "Switch-day edits" below. Found on the way and fixed: `info` lacked `generateHelp`, so `ztk info --help` reported an unknown option. Task 3: the skills were tried on the copy through a stand-in `ztk` that runs `ztk2` only there. Resuming a task worked; the agent fell back to the Progress log as meant (the copy has no sessions), but looked tasks up with `task … export`, so `ztk` and `AGENTS.md` now forbid `task` for reads too (`ztk info` takes a short UUID). An agent proposing `ztk begin` came from the old personal skills, which still load until the switch replaces them. Skills cannot be tested further before real use: they are adjusted as sessions after the switch show what is off. The copy is deleted. Next: Task 4, the switch, on a Monday.

How the script is put together (keep it this way):

- It refuses to run when Taskwarrior's `data.location` (after `TASKRC`/`TASKDATA`) is not the knowledge base, so a rehearsal never touches the real tasks. It does not require a clean tree: the rehearsal copy is thrown away uncommitted, and the switch starts with a commit.
- `task_uuid` is read from every `.md` on disk, tracked or not (the copy has untracked notes).
- Moves first, links last, so `note` holds each note's final path, relative to the knowledge base as `ztk add` writes it. Moves are staged by `git mv`; the database change is not.
- Only the frontmatter is read for `task_uuid`. Exported fields are split by the unit separator, since `read` merges empty fields split by tabs.
- The knowledge base's git tracks `taskchampion.sqlite3-shm` and `-wal`; Taskwarrior removes them at checkpoint, so they show as deleted in the diff.

## Decisions (summary; the spec has the details)

- **The migration is a bash script, `migration/migrate.sh`,** in this repository and deleted once done. It moves files with `git mv` and sets `note` with `task modify`, defining the UDA on the command line as ztk does. Nothing in it needs ztk's code once notes are never edited inside.
- **Git is the safety net:** the knowledge base is a git repository holding `taskchampion.sqlite3`, so the user commits before running it, reviews `git diff` (moves show as renames), fixes small things by hand, and reverts to that commit if anything goes badly wrong. The script refuses nothing beyond what its rules leave out.
- **It never guesses:** a case it cannot settle by rule is printed and left unchanged. On the real base: one completed task with three notes, 17 tasks without a note (15 completed, 2 pending), 3 notes of deleted tasks (`check` already warns, `orphan-task-note`).
- **What it does:** (1) sets `note` on each live task whose UUID is the `task_uuid` of exactly one note (262 on the real base); (2) moves every root `.md` into `zettel/` except `progress*.md`, `Weekly-Status-*.md`, `AGENTS.md`, `CLAUDE.md` and `000-Project-Index.md` (9 on the real base, one without frontmatter, which keeps its file name as title); (3) moves `Weekly-Status-2026-WW.md` to `summaries/2026-Www.md`, the week kept up to 40 and shifted back one from 42 on (the bash script broke around week 41). Folder names are the configuration's defaults (`zettel`, `summaries`), the only ones the user's `ztkrc` uses.
- **`modified` is bumped:** Taskwarrior sets it to the day of any change, even through `task import`, so every open task looks touched on the migration day. Accepted: the migration runs right after that Monday's summary, the next weekly summary is noisy, the quarter's retrospective says so.
- **`progress*.md` stay at the root as the Progress log,** never converted: free-form entries with no Done/Now/Next, 70 of 220 naming no task. Agents read it when `info` shows no sessions for a task worked before the switch; quarter and year retrospectives starting before the switch read it too.
- **Checks after the run:** `ztk check` reports no `dangling-link` it did not report before; one link to a moved zettel opens in Telekasten. Links already broken in Telekasten are out of scope.
- **Skills:** `ztk`, `ztk-planning` and `ztk-summary` live in `skills/` (they describe ztk's commands and change with the code), symlinked by hand on the switch day. `start-session`, `finish-session` and `to-ztk` are the user's workflow skills, edited in place in `~/.claude/skills/`: `start-session` starts with `ztk info <task>`; `finish-session` writes `session add` in place of `progress.md`; `to-ztk` gets ztk's commands. The old copies in the knowledge base's `.claude/skills/` are deleted. `weekly-summary` is replaced by `ztk-summary`.
- **What agents may run** (in `ztk`): reads, `add` (saying which task), `session add`, `new`, `paper new` and writes into notes freely; `start`, `stop`, `done`, `review` only when the user says so; goal and effort writes only inside `ztk-planning`; `summary --write` only inside `ztk-summary`; never `--editor`, nor `task` writes.
- **`ztk-summary`:** the weekly narrative keeps `weekly-summary`'s format exactly, its detail taken from the sessions in `summary --json`; a quarter's or year's is the Retrospective, written after interviewing the user one goal at a time: a section per goal under its closing status, General work, What didn't work, Looking ahead; paragraphs, not bullets.
- **`AGENTS.md`:** `skills/AGENTS.md` merges the knowledge base's drifted `AGENTS.md` and `CLAUDE.md` for the new layout; `CLAUDE.md` becomes `@AGENTS.md` (Claude Code reads `CLAUDE.md`, not `AGENTS.md`; the user uses other agents too).

## Tasks

Each task is one complete piece of functionality and ends with a commit. Tasks are deliberately large. Nothing here touches the real knowledge base, the real Taskwarrior data or `~/.claude/skills/` before the switch.

1. **Migration script and its rehearsal.** `migration/migrate.sh` with the three rules and the printed skipped cases, then a rehearsal: copy `~/cerebro` to `/tmp` (the Taskwarrior database included), commit nothing there beyond a baseline, run the script through a `TASKRC` with `gc`, `recurrence` and `hooks` off and `TASKDATA` pointing at the copy, and review `git diff --stat -M`, the skipped cases, and `ztk2 check` before and after. The rehearsal copy is kept for Task 3.
2. **Repository skills and the skills check.** `skills/ztk/SKILL.md` (starting work with `info`, the Progress log fallback, the commands agents may run, notes written with their own tools, reading a paper), `skills/ztk-summary/SKILL.md`, `skills/ztk-planning/SKILL.md`, `skills/AGENTS.md`; the skills check in `scripts/native-smoke.sh`. Drafts of the three in-place edits (`start-session`, `finish-session`, `to-ztk`) are written into this plan for the user to apply on the switch day.
3. **Try-out on the rehearsal copy.** Use `ztk2` with the skills on the migrated copy (symlinked into a scratch skills location, never `~/.claude/skills/`), fix what feels off, delete the copy.
4. **Switch** (the user runs it, spec §12): the old weekly summary, commit, `migrate.sh`, review, `ztk2 check`, one link in nvim; skills symlinked and edited, old copies deleted, `AGENTS.md`/`CLAUDE.md` replaced, Telekasten pointed at `zettel/`; the bash script renamed `ztk.sh`, the binary installed as `ztk`. Then `migration/` is deleted.

## Switch-day edits (drafts, applied by the user in `~/.claude/skills/`)

**`start-session/SKILL.md`**: replace the two lines from "The user might provide a specific task ID." through "**Load files**: …" with:

```
Run `ztk info <task>` for the task the user names, `ztk current` otherwise.

**Load files**: read the task's last sessions and its note (`note`), and its effort's note when it has one (`ztk effort info <slug>`). With no sessions on older work, the history is in the Progress log (`progress*.md` at the knowledge base's root).
```

**`finish-session/SKILL.md`**: replace "Updates the progress.md file and the files associated with the task." with "Records the session in the journal and updates the files associated with the task.", and add before "Once the files are updated":

```
**Record the session**: `ztk session add <task> --title "<one line>" --done "…" --now "…" --next "…"`, a few lines per part; the details belong in the note. The note's path is `note` in `ztk info <task>`. Never write `progress.md`.
```

The three closing options stay; option 2 and 3 keep `ztk review <ID>` and `ztk done <ID>`.

**`to-ztk/SKILL.md`**, step 5: publish each task with `ztk add <description> project:<Project> --effort <slug>` (`--effort` only when the plan belongs to an effort), and replace the `FILE=$(ztk touch <ID>)` line with:

```
**Execution:** `ztk add` prints the new task's note path (`note` in its JSON); write the template into it with the edit tool.
```

**`to-ztk/task-reviewer-prompt.md`**: replace `` **Spec for reference:** `ztk cat [TASK_ID]` `` with `` **Spec for reference:** the note at `note` in `ztk info [TASK_ID]` ``, and "read the notes via `ztk cat <ID>`" in `SKILL.md` step 6 with "read the notes from `ztk info <ID>`".

## Done when

- The migrated knowledge base passes `ztk check` with no new `dangling-link`, every task with exactly one note has `note` set, and `git diff` shows only renames and the Taskwarrior database.
- The skills check passes in the native smoke test.
- Agents start from `ztk info <task>`, finish with `session add`, and nothing writes `progress.md`.
