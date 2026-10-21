# ztk — Design Spec

- **Date:** 2026-10-15
- **Status:** Draft (pending user review)
- **Replaces:** `ztk` (bash, ~730 lines)

## 1. Purpose

`ztk` is a personal knowledge and work-management tool combining a Zettelkasten second brain with GTD-style task tracking on top of Taskwarrior. The rewrite turns it from a bash wrapper into a native binary that:

1. Serves a **human** (readable search/browse output, nvim integration) and is **optimized for AI coding agents** (stable JSON, agent-safe commands, bundled skills).
2. Organizes **work notes by project**, linked to **yearly → quarterly goals** through **efforts**.
3. Holds **non-work knowledge**: zettels and paper reading notes.
4. Tracks efforts per project and per quarter and gathers the facts for **weekly, quarterly and yearly summaries**.
5. Lets humans and agents **cross-reference** across all note kinds ("which efforts and tasks relate to this zettel and the papers that mention it?").

### Non-goals

- No storage of its own: Taskwarrior stores tasks, Markdown files store everything else. No database.
- `ztk` never calls an LLM. Agents use `ztk`; `ztk` does not use agents.
- No semantic/embedding search (may be revisited later).
- No GUI or TUI beyond the CLI and nvim.

## 2. Decisions

| Topic | Decision |
|---|---|
| Hierarchy | Yearly goal → quarterly goals → efforts → tasks. A year may have several yearly goals. |
| Effort | Loose grouping of tasks toward one quarterly goal. Tasks in an effort need not depend on each other and may span projects. |
| Project | **Required** on every task. |
| Effort on task | **Optional.** Effort-less tasks are "general work" (support, bug fixes, releases) and are normal, not flagged. |
| Summaries | `ztk` gathers facts and renders a deterministic summary (Markdown or JSON); an agent skill may expand it into narrative using only those facts. |
| Agent interface | One CLI. `--json` (or non-TTY stdout) produces stable JSON. No MCP server for now. |
| Session log | Sessions are data (`journal/`), each with a short summary; `ztk recent` replaces reading `progress.md`. |
| Storage | Approach A: goals, efforts, sessions as Markdown; task relations as Taskwarrior UDAs; no duplicate index. |
| Stack | Java 25, Æsh for commands, Jackson for JSON, `task` CLI via `ProcessBuilder`, Maven, GraalVM native image from day one. No FFM. |
| Completion | Computed by ztk in Java and served through Æsh's `--aesh-complete`, so Æsh's generated script works in every shell it supports (bash, zsh, fish). No ztk-written shell script. |

## 3. Configuration

`ztk` reads a Taskwarrior-style config file.

**Lookup order** (first found wins): `--config <path>` flag → `$ZTKRC` → `$XDG_CONFIG_HOME/ztk/ztkrc` (default `~/.config/ztk/ztkrc`) → `~/.ztkrc`.

**Format:** Java properties: `key=value` lines, `#` comments, blank lines ignored. A leading `~` in a value is expanded to the home directory. There is no `include`: the file holds only ztk's own keys, and Taskwarrior itself is configured through its own `.taskrc` (optionally selected with `task.rc`).

| Key | Default | Meaning |
|---|---|---|
| `data.location` | *(required)* | Root of the knowledge base, e.g. `/home/jab/cerebro`. `~` is expanded. |
| `task.command` | `task` | Taskwarrior executable. |
| `task.rc` | *(unset)* | If set, exported as `TASKRC` to every `task` invocation. |
| `task.data` | *(unset)* | If set, exported as `TASKDATA` to every `task` invocation. |
| `editor` | `$EDITOR`, then `nvim` | Editor for interactive commands. |
| `folder.zettel` | `zettel` | Folders are always inside `data.location` (see below). |
| `folder.papers` | `papers` | |
| `folder.goals` | `goals` | |
| `folder.efforts` | `efforts` | |
| `folder.journal` | `journal` | |
| `folder.summaries` | `summaries` | |

A `folder.*` value is a relative path, possibly nested (`notes/zettel`). A value that is absolute or resolves outside `data.location` (`..`), or two keys naming the same folder, is a configuration error (exit 1, naming the key). The first segment of each configured folder is reserved; any other top-level directory under `data.location` is a **project folder** (named after the Taskwarrior project). Hidden directories are ignored. Unknown keys are ignored; `config show` prints the value in effect for every key.

If no config file is found, or `data.location` is missing or not a directory, every command except `--help`, `config` and shell completion exits with code 1 and prints where it looked and a sample config. `ztk config show` prints the effective configuration and which file it came from.

`task.rc`/`task.data` let tests and alternative setups run fully isolated from the user's real Taskwarrior data.

## 4. Data model

### 4.1 Layout (under `data.location`)

```
goals/
  2026/
    2026-G1-infinispan-provably-correct.md # yearly goal 2026-G1
    kickoff/                               # material: any other folder
    Q4/
      2026-Q4-G1-fix-found-data-loss-bugs.md # quarterly goal 2026-Q4-G1
efforts/
  infinijepsen-demo.md                     # effort infinijepsen-demo
  infinijepsen-demo/                       # material
<Project>/                                 # task notes (unchanged)
  20260526-....md
papers/<venue>/<year>/<slug>.{pdf,md}      # PDF and its paper note, filed by `paper new`
zettel/                                    # standalone knowledge notes
  20260204-causality.md
journal/
  2026-10-15.md                            # session entries for that day
summaries/
  2026-W42.md, 2026-Q4.md, 2026.md         # summaries (agent-expanded)
```

### 4.2 Entities

| Entity | Stored in | ID | Relations |
|---|---|---|---|
| Yearly goal | `goals/YYYY/YYYY-Gn-<slug>.md` | `YYYY-Gn` | frontmatter `title`, `status`, `closed`, `date` |
| Quarterly goal | `goals/YYYY/Qq/YYYY-Qq-Gn-<slug>.md` | `YYYY-Qq-Gn` | frontmatter `parent: YYYY-Gm` (required, same year), `title`, `status`, `closed`, `date` |
| Effort | `efforts/<slug>.md` | `<slug>` | frontmatter `goal: YYYY-Qq-Gn` (required), `title`, `status`, `closed`, `date` |
| Task | Taskwarrior | id / uuid | `project` (required), UDA `effort` (optional), UDA `note` |
| Task note | `<Project>/*.md` | path | frontmatter `task_uuid` |
| Zettel | `zettel/*.md` | path | `[[links]]` |
| Paper | `papers/**/*.md` | path | `venue`, `authors`, `pdf` (relative to the note), `[[links]]` |
| Session | section in `journal/YYYY-MM-DD.md` | `YYYY-MM-DD#n` | task uuid, title, summary |

Quarters are calendar quarters (Q1 = Jan–Mar) of calendar years. A goal's ID is read from its file name alone, which starts with it; the folder must agree (`check` reports `invalid-goal-name` otherwise). Goal numbers (`Gn`) are allocated per year (yearly) or per quarter (quarterly) as the highest number in use plus one, so a dropped goal keeps its number. Slugs are lowercase `[a-z0-9-]`; goal file slugs are derived from `--title` (lowercased, non-alphanumerics collapsed to `-`, max 60 chars) unless `--slug` is given. Effort slugs are given explicitly and must not be the name of any note in the knowledge base, so a wikilink to an effort is never ambiguous. Neither the slug nor the ID ever changes through ztk: tasks, efforts and wikilinks point to them, and ztk rewrites none of those (`--title` changes only `title`).

**Levels and material.** `goals/<YYYY>/`, `goals/<YYYY>/Q<q>/` and `efforts/` are levels: every `.md` directly in a level is a goal or effort. Any other folder inside them holds **material** (drafts, PDFs, notes related to one goal or effort, e.g. `efforts/infinijepsen-demo/`), whose Markdown files are plain notes of type `other`.

Every effort has a quarterly goal, and its quarter is its goal's: there is no `quarter` field. Carrying an effort over to a new quarter means pointing it at a goal of that quarter (`effort set --goal`); the file never moves.

**Status**, set only by the user (never derived from tasks, never changed as a side effect):

- Goals (both levels): `open | reached | missed | dropped`. `missed` means tried and not made; `dropped` means it no longer matters. An `open` goal whose quarter (or year) has ended **needs closing**.
- Efforts: `planned | active | done | dropped`. An effort that is neither `done` nor `dropped` and whose goal's quarter has ended is **carried over** (see §6.3).
- `closed` holds the day (`YYYY-MM-DD`) the status last moved to a closing one (`reached`/`missed`/`dropped`, `done`/`dropped`), written by `goal set`/`effort set` and removed when the status moves back to an open one. Setting the current closed status again keeps the date, or writes today's when there is none, which fixes a `closed-mismatch`. Summaries place a closed goal or effort in the period of its `closed`.
- Open work (an `open` quarterly goal, a `planned`/`active` effort, a pending or waiting task) never sits under something closed, at every level: quarterly goal under yearly goal, effort under quarterly goal, task under effort. ztk refuses to add or move open work under a closed goal or effort (a completed task may still be assigned to a closed effort after the fact), and refuses to close a goal or effort while open work is directly under it, whatever the closed status, with no way around it: closing is a review, and the refusal lists each open child with its fix. Completed and deleted tasks, and recurring templates, never block.
- The plan's rules are written once and serve both sides: `check` reports what is wrong, and a planning write (`goal new|set`, `effort new|set|assign`, `add --effort`) is refused when the plan after it would have an error the plan before it did not. The refusal exits 3 and lists those errors with `check`'s own message and hint; nothing is written. New warnings (e.g. a goal entered after its period, which needs closing) never block, and problems already in the plan never block an unrelated change.

### 4.3 Taskwarrior UDAs

`ztk` defines these on every `task` invocation as `rc.` overrides on the command line; it never writes them to the user's `.taskrc`:

- `uda.effort.type=string`, `uda.effort.label=Effort`
- `uda.note.type=string`, `uda.note.label=Note` — path relative to `data.location`

Without the definition, Taskwarrior treats `note:x` as description text (`modify note:x` replaces the description). Values written by `ztk` are kept by Taskwarrior and exported even when the user's own `task` runs without the definition; users who want them in their own reports can add the two lines to their `.taskrc`. Existing UDAs (`issue`, `priority`, `reviewed`) are untouched.

### 4.4 Note types

Type is inferred from location only, never from file names; existing notes need no frontmatter edits. ztk reads every `.md` under `data.location`, in every subfolder, skipping hidden directories and `AGENTS.md`/`CLAUDE.md` anywhere (instructions for agents, not knowledge). Frontmatter is read as YAML; when that fails, it is read as flat `key: value` lines (key before the first `: `), the only shape ztk and the bash script write, so a title the bash script left unquoted (`title: ISPN-1234: Fix the merge`, 55 of 276 notes today) is still read. A note that cannot be read at all (not UTF-8, no permission) is skipped with a warning.

- directly in a goal level (`folder.goals`/`YYYY`, `folder.goals`/`YYYY`/`Qq`) → goal (yearly or quarterly by level)
- directly in `folder.efforts` → effort
- anywhere else under `folder.goals` or `folder.efforts` → `other` (material, §4.2)
- under `folder.papers` → paper
- under `folder.zettel` → zettel
- under `folder.journal` → journal
- under `folder.summaries` → summary
- under a project folder → task note if it has `task_uuid`, otherwise a project note
- root-level `.md` files → `other` (indexed and searchable, but not typed further). This includes the bash tool's `progress*.md` and `Weekly-Status-*.md` until the migration moves them (§9).

Tags are not a ztk concept: projects and efforts group work, and a zettel linking the notes of one topic (a structure note) groups knowledge. A `tags` frontmatter field in an existing note is left alone and not read.

### 4.5 Templates

Goal and effort templates are built into ztk. Each section carries one italic line saying what it holds; the agent that discussed the goal with the user writes the content (see `ztk-planning`, §10). ztk writes no generated content into them: their tasks and efforts are always computed (`goal info`, `effort info`, `graph`), so nothing goes stale. After creation, ztk changes only their frontmatter.

Yearly goal (big and open-ended, agreed with the manager):

```
## Summary
_One paragraph a newcomer understands: what this goal is about._
## Why it matters
_For the team, the product and its users; and for you: the growth or visibility it brings._
## Agreement
_Who agreed (your manager), when, and what exactly was agreed, in their words where possible._
## What success looks like
_Signals that the goal was met, not hard metrics: it is open-ended on purpose._
## Out of scope
_What this goal deliberately leaves out, so nobody expects it later._
## Open questions
_What is still unclear, and who can answer it._
## Retrospective
_At the end of the year: the outcome, the evidence (efforts, summaries, links), what you learned, what you would do differently._
```

Quarterly goal (smaller, achievable, checkable):

```
## Summary
_One paragraph a newcomer understands: what this goal delivers this quarter._
## Contribution
_How reaching this moves the yearly goal forward._
## Done when
_A checklist of concrete, verifiable criteria; reached means every item holds._
## Out of scope
_What this goal deliberately leaves out, so nobody expects it this quarter._
## Risks and dependencies
_What could block it, and whom it depends on._
## Retrospective
_At the end of the quarter: each criterion met or not, the evidence, what you learned._
```

Effort (the working document agents read most):

```
## Context
_Why this effort exists, and what its quarterly goal needs from it._
## Done when
_A checklist, as for quarterly goals._
## Approach
_How the work gets there; rewritten when the plan changes, with the reason in Decisions._
## Decisions
_A dated log: "2026-11-02: chose X over Y, because …". Each session's agent reads it before changing course._
## Risks and open questions
_What could block it, and what is still unclear._
## Outcome
_When closed: what was delivered, its impact, links to the evidence. This feeds the quarterly retrospective._
```

The body holds no wikilink to the parent goal: the frontmatter is the only source, and a copied link would go stale when an effort is carried over.

Task-note, zettel and paper templates are built into ztk, with no user override. The paper template is based on the user's `papers/TEMPLATE.md` (frontmatter `title`, `authors`, `venue`, `link`, `date_read`, `pdf`, then the same eight sections, each with italic guidance on what it holds but without the original's examples, which named real topics and made every new paper note match searches for them); fields not given are left empty (`""`) instead of holding placeholders. `papers/TEMPLATE.md` becomes a leftover, with no special handling: the user deletes it (until then it reads as a paper).

### 4.6 Journal format

```
# 2026-10-15

## 14:32 · task:<uuid> · Review round 1 for CONFIG listener
Short summary, a few lines: what was done, where things are, what is next.
```

## 5. Graph and cross-referencing

Every entity is a node with a uniform ID. JSON always prints the canonical form: `task:<uuid>` (full UUID, since numeric IDs change), `note:<path relative to data.location, without .md>`, `project:<name>`, `effort:<slug>`, `goal:<YYYY-Gn|YYYY-Qq-Gn>`. A goal's or effort's own file is that goal or effort node, never a `note:` node; a wikilink to the file reaches it.

As input, commands accept the canonical forms plus `task:<id|short-uuid>`, a bare number (a task), a bare goal ID (`2026-Q4-G1`) and `note:<name>` or a bare name (a note or effort, resolved like a wikilink written at the root). A name matching several notes exits 3 listing their canonical IDs; a name matching none exits 2. File paths are not accepted: JSON carries the ID next to every path.

**Wikilinks:** `[[target]]` outside fenced code blocks (```` ``` ````/`~~~`) and inline code spans. The target is the text before any `|` or `#`, trimmed, and matches a note's file name without `.md`, exactly and case-sensitively. Frontmatter is not scanned. Resolution, in order:

1. A note of that name in the linking note's own folder: that note only.
2. Otherwise every note of that name; more than one is an **ambiguous link** (reported by `check`).
3. Otherwise, a target shaped as a full or 8-hex short task UUID resolves to that task (any status except deleted).
4. Otherwise a **dangling link**: kept, shown by `graph`, reported by `check`.

**Edges** (all derived; nothing extra is maintained by hand):

| Edge | Source |
|---|---|
| note ↔ note, note → task (`link`) | `[[wikilinks]]`, indexed in both directions (links + backlinks) |
| task ↔ task note (`note`) | UDA `note`, with frontmatter `task_uuid` as fallback |
| task → project (`project`) | Taskwarrior `project` |
| task → effort (`effort`) | UDA `effort` |
| effort → quarterly goal → yearly goal (`goal`) | frontmatter `goal`, `parent` |
| goal/effort note ↔ any note | `[[wikilinks]]` in their bodies |

ztk writes no links into existing notes: users and agents write wikilinks with their own tools.

The graph is built in memory per invocation by reading the notes (in parallel, on virtual threads) plus one `task export` of non-deleted tasks, run concurrently. `search` never calls Taskwarrior. A persistent cache may be added only if measured latency calls for it (target: under 300 ms for `graph`/`search` on about 1,000 notes, native build).

## 6. Commands

### 6.1 Output contract

- Interactive session (stdin and stdout are terminals) → human output (tables, trees, color). `--json`, or stdin or stdout piped or redirected → JSON. `--human` forces human output otherwise. Colors appear only in an interactive session without `NO_COLOR` set.
- **Path-result commands** (`add`, `start`, `new`, `paper new`, `goal new`, `effort new`, `summary --write`, `report-file`) follow the same rule: on a terminal (or with `--human`) they print only the bare path on stdout; as JSON they print `{"schema":1,"id":…,"path":…}` (plus command-specific fields, e.g. `uuid` for tasks). Scripts that want the bare path pass `--human`, e.g. `FILE=$(ztk add "Fix bug" project:X --human)`, or read `.path` from the JSON. Their human messages (e.g. "Created task 3.") always go to stderr.
- JSON always includes node IDs and absolute file paths. JSON shapes are versioned (`"schema": 1`) and covered by golden-file tests.
- Exit codes: `0` ok, `1` usage/config error, `2` not found, `3` validation failure (e.g. unknown effort, missing project), `4` Taskwarrior error (its stderr is included).
- Any argument starting with `-` that is not a recognized option prints the command's usage and exits 1 without side effects. This includes arguments passed through to Taskwarrior; tag removal (`-tag`) is not supported via `ztk add`. `--help`/`-h` print usage and exit 0. The guard applies to positional arguments only: values of recognized options are exempt, and a lone `-` is allowed as the stdin marker (e.g. `--summary -`).
- Commands that open the editor exit 1 with a pointer to the agent-safe alternative when stdout is not a TTY.

### 6.2 Command set

**Tasks** (existing behavior kept unless noted)

| Command | Behavior |
|---|---|
| `add <desc> project:X [taskwarrior attrs] [--effort <slug>]` | Creates the task **and its note** (`<project>/<YYYYMMDD>-<slug>.md`, linked through UDA `note`) and prints the note path. Requires a literal `project:<name>` and fails with code 3 without it, or if the effort does not exist or is `done`/`dropped`, before anything is written. Sets UDA `effort`. The effort is given only through `--effort`: an `effort:` word (or an abbreviation down to `ef:`, which Taskwarrior also takes) is a usage error naming the option, since it would skip both checks. Replaces the old `add` + `touch` sequence. |
| `start <id> [--editor]` | Single-active-task rule (exit 3 naming the active task); adds `+week`, removes `+review`; starting the active task again succeeds. Prints the note path; `--editor`/`-e` also opens it, and fails before starting outside an interactive session. Finds the note by UDA `note`, then by `task_uuid` frontmatter anywhere in the knowledge base (setting the UDA), and creates it otherwise; a task without a project fails with exit 3 before starting. Replaces the old `start` + `begin` pair. |
| `stop` | Stops all active tasks and lists them (JSON: `tasks` array, empty when nothing was active; not an error). No longer shows `week`; use `ztk stop && ztk week`. |
| `review <id>` | Stops the task if active (an inactive task is only tagged), adds `+review`, keeps its other tags (a week task stays in the week). Prints the task (JSON: `id`, `uuid`, `description`). |
| `done <id>` | Removes `+week` and completes the task in one Taskwarrior command (an active task is stopped). An already completed task fails with exit 3. Prints the task, named by its short UUID since completed tasks have no ID (JSON: `id` 0, `uuid`, `description`). |
| `current` | Lists the active task(s) with their note paths (JSON: `tasks` array of `id`, `uuid`, `description`, `note`). Read-only: a task started outside ztk shows no note (JSON `note: null`) and a hint to run `ztk start <id>`. Nothing active is not an error (exit 0, empty `tasks`); the note file's existence is not checked (`check` finds broken links). |
| `info <id>` | Shows one task with its note path and effort (JSON: the common task shape plus `note`, the absolute path or `null`); same rendering and read-only rule as `current`, on any task. An unknown task exits 2 with a hint to `ztk list`. This is how editors, scripts and agents reach a note: they open, grep or read the path with their own tools. |
| `week [project]` | Pending `+week` tasks, review tasks highlighted. Human output is Taskwarrior's own table (an ad hoc `weekview` report: ID, Active, Project, Issue, Description), rendered with forced colors and no wrapping; colors are stripped from human output outside a terminal or with `NO_COLOR` (forcing colors also overrides a `color=off` in the user's taskrc). JSON: `tasks` array, the common task shape. The project matches by prefix and includes subprojects, as Taskwarrior does. An empty week is not an error. |
| `list [project]` | Pending tasks that are not waiting, most urgent first, through the user's own `next` report, never cut at one screen (`limit:none`; `next` has `limit:page`). Same rendering, color, project matching and JSON rules as `week`. No match is not an error; the hint shows the `ztk add` to run. No fallthrough to Taskwarrior for unknown commands: same words would mean different things, and its output and exit codes break ztk's contracts. |

The common task shape (JSON of every task command) has `id`, `uuid`, `description`, `status`, `project`, `tags`, `active`, `issue` and `effort` (the slug, or `null` for general work). From a task, `effort info <slug>` gives the goal chain; `context` (Phase 4) bundles it in one call.

**Notes**

| Command | Behavior |
|---|---|
| `new <title…> [--editor]` | Creates a zettel in `folder.zettel` (`<YYYYMMDD>-<slug>.md`, frontmatter `title`, ISO `date`, `uuid`, then `# Notes`) and prints its path (JSON: `path`, `uuid`). The words form the title. A title already used that day gets a numbered file (`-2.md`); nothing is overwritten. `--editor`/`-e` also opens it, and fails before writing outside an interactive session. No title-less Telekasten mode (use `nvim -c "Telekasten new_note"`). |
| ~~`note`/`open`, `cat`~~ | Dropped: `cat` dumps a whole note, while a path lets agents read a range, grep or edit with their own tools; `note` is a human shortcut for `$EDITOR "$(ztk info N | jq -r .note)"` or `ztk start N -e`. |
| `paper new <pdf> --title … [--venue …] [--year …] [--authors …] [--link …] [--editor]` | Copies the PDF (any path) to `papers/<venue or others>/<year, default current>/<slug-of-title>.pdf` and creates the paper note `<slug>.md` next to it from the built-in template, with `date_read` today and `pdf: <slug>.pdf`. Never moves or deletes the original. An existing PDF or note at the target exits 3, a missing PDF exits 2, and a venue that is not a single visible folder name or a title without ASCII letters or digits exits 1, all before anything is written. Prints the note path (JSON: `id`, `path`, `pdf`). `--editor`/`-e` as for `new`. |

**Goals and efforts**

| Command | Behavior |
|---|---|
| `goal new <YYYY\|YYYY-Qq> --title … [--parent YYYY-Gn] [--slug …]` | Creates the goal note from its template (`status: open`) and allocates the ID (§4.2). `--parent` is required for a quarterly goal and must name an open yearly goal of the same year. |
| `goal set <id> [--status …] [--title …] [--parent …]` | Updates the frontmatter, writing or removing `closed` with the status (§4.2). |
| `goal list [YYYY\|YYYY-Qq] [--status …]` | Goals of the period (default: the current year, in any status); a quarter also lists its goals' yearly parents. The yearly parents of the listed quarterly goals are always listed, whatever their status. Human: yearly goals with their quarterly goals nested, each with its effort count and "needs closing" marked. JSON: `goals`, flat in the same order (each yearly goal followed by its quarterly goals), each with `id`, `title`, `status` (`null` when not a goal status), `closed`, `parent` (a node ID; `null` for yearly), `path`, `needsClosing`, `efforts` (count, in any status; a yearly goal counts the efforts of the quarterly goals listed with it, since efforts name quarterly goals). |
| `goal info <id>` | One goal (its fields as in `goal list`, at the top level) and its subtree: `efforts`, the goal's efforts by slug as `effort list` shows them, with `tasks` counts (empty for a yearly goal), and `goals`, a yearly goal's quarterly goals by quarter, each with its own `efforts` (empty for a quarterly goal). In `goal info`, `efforts` is that list, not a count. Human: the goals table, then the efforts table. An unknown goal exits 2. |
| `effort new <slug> --title … --goal YYYY-Qq-Gn` | Creates the effort note from its template (`status: planned`). The goal must be an open quarterly goal; the slug must be `[a-z0-9-]` and not the name of any note. |
| `effort set <slug> [--status …] [--title …] [--goal …]` | Updates the frontmatter, writing or removing `closed` with the status. A goal of another quarter carries the effort over. |
| `effort list [YYYY\|YYYY-Qq] [--status …] [--goal …]` | Default: every `planned`/`active` effort in any quarter, carried-over ones marked. With a quarter: the efforts whose goal is in it, in any status; a year means its four quarters. With `--goal`: that goal's efforts, in any status. `--status` narrows any of these. Ordered by their goal's quarter, then goal, then slug. JSON: `efforts`, each with `id`, `title`, `status` (`null` when not an effort status), `closed`, `goal` (a node ID), `quarter`, `carriedOver`, `path`, `tasks: {pending, completed}` (waiting tasks count as pending; deleted tasks and recurring templates are not counted). |
| `effort info <slug>` | One effort (`id`, `title`, `status`, `closed`, `path`), its goal chain (`goal: {id, title, status, path, parent: {…}}`) and its tasks by status (the common task shape plus `note`). An unknown effort exits 2. |
| `effort assign <slug> <task…>` | Sets the effort of one or more tasks; refusals as in §4.2. |
| `effort unassign <task…>` | Clears it: the tasks become general work. |

There are no delete commands: a goal or effort that no longer matters is `dropped`, which keeps it for summaries. Deleting its file by hand is the user's call; `check` then reports what still points to it.

All create/update commands are non-interactive when given their flags, so agents can use them directly. `goal new`/`effort new` follow the path-result rule in §6.1. `goal set` and `effort set` print the updated node ID (human mode) or `{"schema":1,"id":…,"path":…}` (JSON mode); `effort assign`/`unassign` print the tasks (JSON: `tasks`, the common task shape).

**Navigation**

| Command | Behavior |
|---|---|
| `graph <node> [--depth N] [--kind k1,k2] [--type t1,t2] [--edges e1,e2] [--limit N]` | Neighborhood of a node, for agents first. Edges `link` (with `direction` out/in), `note`, `project`, `effort` (task → effort), `goal` (effort → quarterly goal → yearly goal). Breadth-first, default depth 2; each node once, at its shortest depth, with the edge that reached it. Projects are hubs: reached but not expanded unless they are the start node. Goals and efforts are not hubs: from a task, depth 2 reaches its effort's other tasks and its goal. A `goal`/`effort` reference naming nothing gives no edge and no node (`check` reports it). From Phase 4, summary notes (type `summary`) are hubs too: a summary links every task of its period, so walking through it would bury the nodes related to the start under that period's unrelated tasks. Until the migration (§9) moves them, the bash script's `Weekly-Status-*.md` and `000-Project-Index.md` are plain root notes and are walked through. Dangling links are shown as `dangling:<target>` nodes (title: the target; `path: null`), never expanded; a dangling ID is not accepted as the start node. `--kind` (node kinds: `note`, `task`, `project`, `goal`, `effort`, `dangling`), `--type` (note types, as for `search`; nodes that are not notes are kept) and `--edges` (`link`, `note`, `project`, `effort`, `goal`) restrict the walk itself: a node left out is neither listed nor walked through; the start node is always listed. Edge directions: `out` when `from` points to the node, `in` when it is pointed to; a task holds its `note` and `project` edges. JSON: flat `nodes` list ordered by depth, each with `id`, `kind`, `type` for notes (as `search` prints it), `title` (a task's description, a project's name), absolute `path` (a task's note path, a goal's or effort's own note, a project's folder when it has one, otherwise `null`; `pdf` for papers), `status` for tasks, goals and efforts (`null` for a goal or effort whose status is missing or not one of its kind's), `depth`, and `via: {from, edge, direction}` (`null` for the start node; following `from` leads back to the start); then `warnings`, `truncated` and `hint` (`null` unless truncated). At most 50 nodes by default (`--limit`, 1 or more); a cut sets `truncated: true` with a hint to narrow, also printed on stderr for people. Human: a plain table (DEPTH, KIND, TYPE, TITLE, PATH). |
| `search <words…> [--type …] [--project …]` | Notes containing every word (case-insensitive, anywhere in the note); `"quoted words"` match a phrase. No regex, no fuzzy matching (compose with `fzf` over the JSON). Searches the body and the frontmatter `title`. JSON per note: `id`, `path`, `type`, `title`, `task` for task notes (from `task_uuid`), `pdf` for papers (absolute, resolved from the note's `pdf` field; `null` when it has none), up to 3 matching lines with line numbers. Order: title matches, then number of matching lines, then most recently modified file (the frontmatter `date` is not used: existing notes carry it in several non-ISO formats, or not at all). `--type` (comma-separated or repeated), `--project` (that project folder). No match exits 0 with an empty list. |
| `context <task>` | One bundle sized for an agent: task attributes, effort and goal chain, note path plus its last `# Claude Summary` section, last 3 sessions for the task, and directly linked notes (titles + paths). |

**Sessions**

| Command | Behavior |
|---|---|
| `session add <task> --title … --summary …` | Appends an entry to today's journal file. `--summary -` reads from stdin. |
| `recent [-n N]` | Active task, last N sessions (default 5) **with summaries**, next `+week` items. |

**Reports**

| Command | Behavior |
|---|---|
| `summary <week\|YYYY-Www\|YYYY-Qq\|YYYY>` or `summary --since <date\|duration>` | For the period (see §6.3): per quarterly goal → efforts → tasks completed and in progress (with dates and note paths); carried-over efforts; per project, "General work" (effort-less tasks); review queue. Yearly summaries group quarters under their yearly goals. `--write` saves the rendered Markdown to `summaries/<period>.md` and prints its path; it never overwrites an existing file without `--force`. |
| `check [--code …] [--project …]` | Errors (exit 3 if any): `dangling-link`, `missing-note` (an open task's `note` points to a missing file), `task-without-project` (pending and waiting tasks only). Warnings (exit 0 when there are only warnings): `orphan-task-note` (`task_uuid` matches no task), `ambiguous-link`, `unreadable-note`. Planning errors: `unknown-effort` (a task's `effort` names no effort; completed tasks included, since summaries count them), `missing-goal` (an effort without `goal`, a quarterly goal without `parent`), `unknown-goal` (`goal`/`parent` names no goal, or one of the wrong level or year), `invalid-status` (missing, or not one of its kind's), `invalid-goal-name` (a `.md` directly in a goal level whose name does not start with a goal ID of that level), `duplicate-goal` (two files with one goal ID), `open-under-closed` (open work under a closed goal or effort, at any level, reported once per open child: a quarterly goal, an effort or a pending task). Planning warnings: `needs-closing` (an `open` goal whose period ended), `closed-mismatch` (`closed` on an open status, or missing on a closed one). Carried-over efforts are not problems: summaries and `effort list` show them. The whole knowledge base is always read, since links resolve across folders; `--code` and `--project` (notes in the project's folder, tasks of the project) only filter what is reported, and the exit code follows the reported problems. One problem per wikilink, at its line: a target linked twice is two places to fix. JSON: `problems`, errors first then by path and line, each with `code`, `severity`, `id` (the node ID; `null` for an unreadable note), absolute `path`, `line` (`null` except for links), `message`, `hint`, `candidates` (the node IDs an ambiguous link could mean; `null` otherwise). Skipped notes are `unreadable-note` problems, so `check` prints no `warnings` array. `--project` keeps a task's problems by the task's project, the others by their path under the project folder; a project no task has and with no folder exits 2. Read-only, with no `--fix`: users and agents fix what it reports; every hint names the command or edit that fixes it. Human: a table (SEVERITY, CODE, PATH as `path:line` for links, MESSAGE followed by its hint); with errors, a count on stderr. |

**Maintenance**

| Command | Behavior |
|---|---|
| `migrate [--dry-run]` | See §9. |
| `install-skills [--target DIR]` | Symlinks `skills/*` into `~/.claude/skills/` (default). |
| `config show` | Effective configuration. |
| `help [command]`, `<command> --help` | Usage. |

### 6.3 Summary periods

- `week` = the ISO week (Mon 00:00 – next Mon 00:00, local time) containing today. `YYYY-Www` = that ISO week. `YYYY-Qq` = calendar quarter (Q1 = Jan–Mar). `YYYY` = calendar year.
- `--since <date|duration>` = rolling window from that point until now (a Taskwarrior-style duration such as `10d` or `1wk`, or an ISO date). Its file name is `summaries/since-<start-date>.md`.
- **Completed** = tasks with `status:completed` and `end` in [start, end).
- **In progress** and **review queue** = current state (`status:pending +week -review` / `status:pending +review`). Shown only when the period includes today; past periods do not reconstruct historical state.
- **Efforts listed** under a quarterly goal = efforts whose goal's quarter overlaps the period, **or** that have a task completed in the period, **or** whose `closed` falls in it.
- **Carried over** = efforts whose goal's quarter ended on or before the period's end and whose status is neither `done` nor `dropped`. Stale efforts therefore keep appearing until they are moved to a later goal, closed or dropped.
- **Goals closed** = goals whose `closed` falls in the period; **needs closing** = `open` goals whose period ended on or before the period's end.

### 6.4 Retired commands

**Retired / aliased:** `report-file [time]` → alias of `summary week --write` with no argument, or `summary --since <time> --write` with one. It prints the written file's path, as today, and is deprecated (removed after one release). Output moves from root `Weekly-Status-YYYY-WW.md` to `summaries/YYYY-Www.md`; the existing `Weekly-Status-*.md` files are moved by the migration (§9). `setup` → README. `000-Project-Index.md` is no longer generated and the user deletes it; `goal list`, `graph` and `search` replace it.

## 7. Architecture

Maven project, Java 25, GraalVM native-image profile.

| Module (package) | Responsibility | Interface (sketch) |
|---|---|---|
| `config` | Locate, parse and validate the config file. | `Config.load(args, env)` |
| `taskwarrior` | The **only** code that runs `task`. JSON → `Task` records; writes via `add`/`modify`/`start`/`stop`/`done`; ensures UDAs. Applies `task.rc`/`task.data`. | `TaskStore.query(filter)`, `add(…)`, `modify(ref, changes)`, `start/stop/done(ref)` |
| `notes` | Read and create notes under `data.location`: walk, frontmatter, wikilinks, type inference, built-in templates, frontmatter updates, atomic writes. No Taskwarrior. | `KnowledgeBase.load()` → notes, `Zettels`, `Papers` |
| `graph` | `KnowledgeGraph` builds the typed graph from the `KnowledgeBase` + `TaskStore` (one lazy read of the notes, keeping headers and link targets only, plus one task export): nodes, edges, link resolution, node ID input. `Graph` walks it breadth-first with the edge that reached each node; `Check` goes over its nodes and links and reports problems. | `Graph.walk(Query, warnings)` → lazy stream of `Reached`; `Check.problems(…)` |
| `search` | Word and phrase search over the notes. | `Search.find(query, filters)` |
| `planning` | Goals/efforts: ID allocation, validation, status and `closed`, lists and subtrees. | `Planning.newGoal(…)`, `newEffort(…)`, `list(period)` |
| `reports` | `summary`, `recent`, `context` as pure functions returning models. | `Summary.of(period)` → `SummaryModel` |
| `cli` | Æsh commands, argument guard, completers, `Renderer` (human/JSON). Commands return models; only the renderer prints. | — |

Dependencies point downward: `cli → reports/planning/graph/search → notes/taskwarrior → configuration`. The table is a sketch; classes are decided during development.

### 7.1 Taskwarrior integration

- Reads: `task rc.verbose=nothing rc.json.array=on <filter> export`, parsed with Jackson.
- Writes: `task rc.confirmation=off rc.verbose=nothing <ref> <command> <args>`; args passed as a list, never through a shell.
- Completion data: `task _ids`, `task _projects`, and the tags of the open tasks (`task _tags` also lists Taskwarrior's virtual tags, which cannot be added).
- Requires Taskwarrior ≥ 3.0; `ztk` checks `task --version` on first use per invocation and exits 4 with a clear message otherwise.

### 7.2 Shell completion

Æsh generates the shell script: `ztk --aesh-completion zsh|bash|fish`. For zsh it is an autoload file saved as `_ztk` on `fpath`. On every Tab the script calls `ztk --aesh-complete -- <words>`.

- Æsh completes subcommands and options.
- ztk completers compute argument values from Taskwarrior as configured for ztk: task IDs (`start`, `review`, `done`, `info`), `project:` and `+tag` attributes (`add`), project names (`list`, `week`). `graph` completes note names from file names alone (no parsing; a name in several folders is offered folder-qualified), pending task IDs and `project:` names; `--kind`, `--type`, `--edges` and `check --code` offer fixed lists, `--project` project names, with comma lists completed after the last comma (a repeated list option, such as `--kind note --kind t`, gets no completion: Æsh merges its values). Goal IDs, from the goal notes' file names and status: `goal info`/`set` offer every goal, `--parent` the open yearly goals, `--goal` (`effort new`/`set`/`list`) the open quarterly goals. Effort slugs: `effort info`/`set` offer every effort, `effort assign` and `add --effort` the open ones; the tasks of `effort assign`/`unassign` are pending task IDs. `--status` offers its kind's statuses, a fixed list.
- Completers receive the invocation's context through a ztk completer invocation. A failure, such as a missing configuration, gives no candidates and no message.
- Commands whose positional argument is never a file disable Æsh's file fallback on it, so the shell does not offer file names next to the values. Æsh reads only that argument's fallback, for option values too. Commands without positional arguments still get it: Æsh offers no way to turn it off there.

The native binary keeps per-Tab latency low; the target is under 50 ms per call (measured: ~2 ms for subcommands, ~13 ms for task IDs).

**Spike first:** confirm Æsh can parse commands without starting its interactive console and works under native image. If not, switch the command layer to Picocli; the rest of the design is unchanged.

## 8. Error handling

- **Taskwarrior failures:** exit 4, include `task`'s stderr; no partial writes (validate first, write last).
- **Unreadable note:** warning on stderr (or in the JSON `warnings` array, each with `path`, `message` and `hint`), note skipped; other notes still load. Frontmatter that is not valid YAML is read through the fallback without a warning or a `check` problem: ztk reads it correctly, and the migration (§9) quotes the values other tools would misread.
- **Writes:** new notes are created without overwriting an existing file. Updates to an existing note re-read it right before changing it (it may be open in nvim) and rewrite it in place, changing only its frontmatter: the body is kept byte for byte. Other frontmatter fields keep their values, though YAML may quote them differently.
- **Validation:** unknown effort/goal/parent, missing project, or a duplicate slug → exit 3 with the valid options listed.
- **No silent creation:** referencing a non-existent effort never auto-creates it.

## 9. Migration (`ztk migrate`)

Runs idempotently; `--dry-run` prints every change without making it.

1. For each task without a `note` UDA: find a note with matching `task_uuid` and set `note`.
2. Move root-level zettels (`.md` with a `uuid` frontmatter and no `task_uuid`, excluding known files such as `progress*.md`, `AGENTS.md`, `CLAUDE.md`, `Weekly-Status-*.md`, `000-Project-Index.md`) into `folder.zettel`.
3. Report task projects that differ only by case (e.g. `infinispan` vs `Infinispan`) for manual fixing; never change them automatically.
4. Move `Weekly-Status-YYYY-WW.md` to `summaries/YYYY-Www.md` (rename only) and `progress*.md` into `journal/` (open: split their `## YYYY-MM-DD: Project -- Title` entries into sessions, or move the files as they are).
5. Rewrite every frontmatter `date` to ISO 8601 with offset, as ztk writes it (`2026-10-15T09:30:00+02:00`). The bash script wrote `date` output in several locale-dependent layouts (`Tue May 12 03:14:15 PM -03 2026`, `Sat 14 Mar 2026 …`, `Sun May 3 2026`), and some notes have no `date`; report the ones it cannot parse instead of guessing. Quote every frontmatter value that is not valid YAML as written (titles with `: `), so the reading fallback (§4.4) eventually covers nothing.

Wikilinks resolve by name, so moving files does not change link text. Before running step 3 for real, verify that Telekasten in nvim follows a bare-name link to a file in a subfolder (task notes in project subfolders are already linked this way), check which file it opens for a name present in several folders (ztk prefers the linking note's folder, §5),, and point Telekasten's new-note location at `zettel/`.

## 10. Agent integration (bundled skills)

Repository `skills/` folder, installed with `ztk install-skills`:

| Skill | Purpose |
|---|---|
| `ztk` | Everyday agent usage: `recent` at session start, `context`, writing `[[wikilinks]]` into notes with its own tools, `add` (prints the new task's note path)/`info` (a task's note path), `session add` at session end, `graph`/`search` for lookups, never run editor commands. Replaces `~/.claude/skills/ztk`. |
| `ztk-planning` | Collaborative goal → effort → task flow, as a grill-me-style interview: read `goal list` + last quarter's `summary`; for a yearly goal, start from what was agreed with the manager; question the user one goal at a time until the goal is clear; create it with `goal new` and write every section of its template from the discussion; propose efforts one at a time, creating each only after agreement and writing its note; break an effort into tasks (`add … --effort`, `effort assign`) only after its note is agreed. The agent never creates goals or efforts without the user's explicit agreement. |
| `ztk-papers` | Reading flow: `paper new` files the PDF and note; the agent reads the PDF, writes the summary into the note, extracts atomic zettels (`new`) that link back to the paper, and links them from the task notes that apply them. |
| `ztk-summary` | Expand `summary <period> --json` into a narrative for weekly/quarterly/yearly reviews, using only gathered facts, then save with `summary --write` or edit the written file. Replaces `weekly-summary`. |

Also shipped: an `AGENTS.md` template for `data.location` whose startup instruction is `ztk recent` instead of reading `progress.md`.

## 11. Testing

- **Unit:** config parsing, frontmatter/wikilink parsing, type inference, frontmatter updates, ID allocation, graph traversal, summary models. Uses fixture vaults under `src/test/resources`.
- **Integration:** against the real `task` binary with `task.rc`/`task.data` pointing to a temp directory. Never touches the user's data; tests fail if `TASKRC`/`TASKDATA` would resolve to the real files.
- **Golden files:** JSON output of every read command. Shape changes require updating goldens and bumping `schema` if incompatible.
- **CLI:** argument guard (`--help`, unknown flags have no side effects), exit codes, non-TTY editor refusal, and path-result commands printing a bare path on stdout in both TTY and non-TTY mode with human messages on stderr.
- **Native smoke test** (`-Pnative`): build the binary, run `help`, completion, `search`, `graph`, `check` on a scratch knowledge base, create a yearly goal, a quarterly goal and an effort with tasks, and assert latency targets (completion under 50 ms, goal and effort completion included; `search`/`graph` under 300 ms on a generated 1,000-note knowledge base; `goal list`, `effort info` and `graph` from a goal under 300 ms).

## 12. Rollout

1. Spike: Æsh command parsing without the interactive console, plus a native-image build.
2. Build as `ztk2` alongside the bash `ztk`, pointing at the real `~/cerebro` via config.
3. `ztk2 migrate --dry-run`, review, then run it.
4. Update skills and `AGENTS.md`; install the skills.
5. Once the new tool matches the old one, install the binary as `ztk`, rename the bash script to `ztk.sh`, and keep it for one release cycle.

Suggested build phases for the plan: (1) config + Taskwarrior + task commands, (2) vault + graph + search + notes, (3) goals/efforts, (4) sessions + reports, (5) migration + skills.

## 13. Open items

- Æsh vs Picocli: decided by the spike: Æsh.
- Persistent graph cache: only if the latency targets in §5 are missed.
- `progress*.md` migration: split into sessions or move as is (§9), decided in the migration phase.
- Telekasten's choice among same-named notes in different folders, to compare with §5's same-folder rule.
