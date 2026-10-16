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
| Completion | Bash completion script emitted by `ztk`; ztk words via hidden `ztk __complete`; Taskwarrior pass-through args delegated to Taskwarrior's own bash completion. |

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
| `folder.zettel` | `zettel` | Folders are relative to `data.location`. |
| `folder.papers` | `papers` | |
| `folder.goals` | `goals` | |
| `folder.efforts` | `efforts` | |
| `folder.journal` | `journal` | |
| `folder.summaries` | `summaries` | |

Any other top-level directory under `data.location` is a **project folder** (named after the Taskwarrior project). Hidden directories are ignored.

If no config file is found, or `data.location` is missing or not a directory, every command except `help`, `completion` and `config` exits with code 1 and prints where it looked and a sample config. `ztk config show` prints the effective configuration and which file it came from.

`task.rc`/`task.data` let tests and alternative setups run fully isolated from the user's real Taskwarrior data.

## 4. Data model

### 4.1 Layout (under `data.location`)

```
goals/
  2026/
    G1-infinispan-provably-correct.md      # yearly goal 2026-G1
    Q4/
      G1-fix-found-data-loss-bugs.md       # quarterly goal 2026-Q4-G1
efforts/
  infinijepsen-demo.md                     # effort
<Project>/                                 # task notes (unchanged)
  20260526-....md
papers/<venue>/<year>/<name>.md            # paper notes next to PDFs (unchanged)
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
| Yearly goal | `goals/YYYY/Gn-<slug>.md` | `YYYY-Gn` | — |
| Quarterly goal | `goals/YYYY/Qq/Gn-<slug>.md` | `YYYY-Qq-Gn` | frontmatter `parent: YYYY-Gm` (required) |
| Effort | `efforts/<slug>.md` | `<slug>` | frontmatter `goal: YYYY-Qq-Gn` (optional), `status`, `quarter` |
| Task | Taskwarrior | id / uuid | `project` (required), UDA `effort` (optional), UDA `note` |
| Task note | `<Project>/*.md` | path | frontmatter `task_uuid` |
| Zettel | `zettel/*.md` | path | `tags`, `[[links]]` |
| Paper | `papers/**/*.md` | path | `tags`, `venue`, `authors`, `[[links]]` |
| Session | section in `journal/YYYY-MM-DD.md` | `YYYY-MM-DD#n` | task uuid, title, summary |

Goal numbers (`Gn`) are allocated sequentially per year (yearly) or per quarter (quarterly). Slugs are lowercase `[a-z0-9-]`; goal file slugs are derived from `--title` (lowercased, non-alphanumerics collapsed to `-`, max 60 chars) unless `--slug` is given. Effort slugs are given explicitly.

An effort's `quarter` must equal its goal's quarter; `effort new`/`effort set` exit 3 on a mismatch. Carrying an effort over to a new quarter therefore means setting both `--quarter` and a goal in that quarter (or clearing `--goal none`).

**Effort status:** `planned | active | done | dropped`. An effort whose quarter has passed and is not `done`/`dropped` is reported as "carried over" in summaries (see §6.3).

### 4.3 Taskwarrior UDAs

`ztk` defines these on every `task` invocation as `rc.` overrides on the command line; it never writes them to the user's `.taskrc`:

- `uda.effort.type=string`, `uda.effort.label=Effort`
- `uda.note.type=string`, `uda.note.label=Note` — path relative to `data.location`

Without the definition, Taskwarrior treats `note:x` as description text (`modify note:x` replaces the description). Values written by `ztk` are kept by Taskwarrior and exported even when the user's own `task` runs without the definition; users who want them in their own reports can add the two lines to their `.taskrc`. Existing UDAs (`issue`, `priority`, `reviewed`) are untouched.

### 4.4 Note types

Type is inferred from location; existing notes need no frontmatter edits:

- under `folder.goals` → goal (yearly or quarterly by depth)
- under `folder.efforts` → effort
- under `folder.papers` → paper (any `.md` except `TEMPLATE.md` and `CLAUDE.md`)
- under `folder.zettel` → zettel
- under `folder.journal` → journal
- under `folder.summaries` → summary
- under a project folder → task note if it has `task_uuid`, otherwise a project note
- root-level `.md` files → `other` (indexed and searchable, but not typed further)

### 4.5 Templates

Goal note body:

```
## Why it matters
## What done looks like
## Out of scope
## Efforts
<!-- ztk:begin efforts -->
<!-- ztk:end efforts -->
## Retrospective
```

Effort note body:

```
## Context
## Outcome / definition of done
## Approach
## Risks / open questions
## Tasks
<!-- ztk:begin tasks -->
<!-- ztk:end tasks -->
```

Content between `ztk:begin`/`ztk:end` markers is generated and rewritten by `ztk` (for example by `effort show --refresh`, `goal tree --refresh`, and any command that links a task or effort). Everything outside the markers belongs to the user or agent and is never modified.

Task-note, zettel and paper templates keep their current shape (paper uses `papers/TEMPLATE.md`).

### 4.6 Journal format

```
# 2026-10-15

## 14:32 · task:<uuid> · Review round 1 for CONFIG listener
Short summary, a few lines: what was done, where things are, what is next.
```

## 5. Graph and cross-referencing

Every entity is a node with a uniform ID:

`task:<id|uuid>`, `effort:<slug>`, `goal:<YYYY-Gn|YYYY-Qq-Gn>`, `note:<path-without-.md>`, `project:<name>`.

Commands accept these IDs; bare task numbers are accepted where a task is expected.

**Edges** (all derived; nothing extra is maintained by hand):

| Edge | Source |
|---|---|
| note ↔ note | `[[wikilinks]]`, indexed in both directions (links + backlinks) |
| task ↔ task note | UDA `note`, with frontmatter `task_uuid` as fallback |
| task → effort | UDA `effort` |
| effort → quarterly goal → yearly goal | frontmatter `goal`, `parent` |
| task → project | Taskwarrior `project` |
| goal/effort note ↔ any note | `[[wikilinks]]` in their bodies |
| note ~ note (weak) | shared `tags` |

Wikilinks resolve by file name (without `.md`) across all folders, matching Telekasten. A name matching more than one file is reported by `ztk check` and resolves to all matches.

The graph is built in memory per invocation by reading the notes plus one `task export`. A persistent cache may be added only if measured latency calls for it (target: under 300 ms for `graph`/`search` on about 1,000 notes, native build).

## 6. Commands

### 6.1 Output contract

- Interactive session (stdin and stdout are terminals) → human output (tables, trees, color). `--json`, or stdin or stdout piped or redirected → JSON. `--human` forces human output otherwise. Colors appear only in an interactive session without `NO_COLOR` set.
- **Path-result commands** (`add`, `begin`, `new`, `paper new`, `goal new`, `effort new`, `summary --write`, `report-file`) follow the same rule: on a terminal (or with `--human`) they print only the bare path on stdout; as JSON they print `{"schema":1,"id":…,"path":…}` (plus command-specific fields, e.g. `uuid` for tasks). Scripts that want the bare path pass `--human`, e.g. `FILE=$(ztk add "Fix bug" project:X --human)`, or read `.path` from the JSON. Their human messages (e.g. "Created task 3.") always go to stderr.
- JSON always includes node IDs and absolute file paths. JSON shapes are versioned (`"schema": 1`) and covered by golden-file tests.
- Exit codes: `0` ok, `1` usage/config error, `2` not found, `3` validation failure (e.g. unknown effort, missing project), `4` Taskwarrior error (its stderr is included).
- Any argument starting with `-` that is not a recognized option prints the command's usage and exits 1 without side effects. This includes arguments passed through to Taskwarrior; tag removal (`-tag`) is not supported via `ztk add`. `--help`/`-h` print usage and exit 0. The guard applies to positional arguments only: values of recognized options are exempt, and a lone `-` is allowed as the stdin marker (e.g. `--summary -`).
- Commands that open the editor exit 1 with a pointer to the agent-safe alternative when stdout is not a TTY.

### 6.2 Command set

**Tasks** (existing behavior kept unless noted)

| Command | Behavior |
|---|---|
| `add <desc> project:X [taskwarrior attrs] [--effort <slug>]` | Creates the task **and its note** (`<project>/<YYYYMMDD>-<slug>.md`, linked through UDA `note`) and prints the note path. Requires a literal `project:<name>` and fails with code 3 without it, or if the effort does not exist, before anything is written. Sets UDA `effort`. Replaces the old `add` + `touch` sequence. |
| `start <id>` / `begin <id>` | Single-active-task rule; adds `+week`, removes `+review`; `start` opens the note, `begin` prints its path. Creates the note and sets UDA `note` if missing (tasks added before ztk2 or with plain `task`). |
| `stop` | Stops all active tasks, then shows `week`. |
| `review <id>` | Stop, add `+review`. |
| `done <id>` | Removes `+week`, completes the task. |
| `current` | Active task(s) with their note paths. |
| `list [project]`, `week [project]` | As today. |

**Notes**

| Command | Behavior |
|---|---|
| `note <id>` / `open <id>` | Open the task note in the editor. |
| `cat <node>` | Print any note (task, effort, goal, zettel, paper). |
| `new <title>` | Create a zettel in `folder.zettel`; print path (opens editor only on TTY). |
| `paper new <pdf-path> --title … [--venue …] [--tags …]` | Create a reading note next to the PDF from `papers/TEMPLATE.md`. |

**Goals and efforts**

| Command | Behavior |
|---|---|
| `goal new <YYYY\|YYYY-Qq> --title … [--parent YYYY-Gn] [--slug …]` | Creates the goal note from the template, allocates the ID, and prints the path (ID via `--json`, see §6.1). `--parent` required for quarterly goals. |
| `goal list [YYYY\|YYYY-Qq]` | Goals in the period. |
| `goal tree [YYYY]` | Yearly → quarterly → efforts → task counts by status. |
| `goal show <id>` | Goal note plus its efforts and progress. |
| `effort new <slug> --title … [--goal YYYY-Qq-Gn] [--quarter YYYY-Qq]` | Creates the effort note; `--quarter` defaults to the goal's quarter, or the current one. |
| `effort show <slug>` | Goal chain, tasks by status, linked notes. |
| `effort set <slug> [--status …] [--goal <id\|none>] [--quarter …]` | Updates frontmatter (validated as in §4.2). |
| `effort list [--quarter …] [--status …]` | List efforts. |
| `task-effort <task> <slug\|none>` | Sets or clears a task's effort. |

All create/update commands are non-interactive when given their flags, so agents can use them directly. `goal new`/`effort new` follow the path-result rule in §6.1. `effort set` and `task-effort` print the updated node ID (human mode) or `{"schema":1,"id":…,"path":…}` (JSON mode).

**Navigation**

| Command | Behavior |
|---|---|
| `graph <node> [--depth N] [--type t1,t2] [--edges e1,e2]` | Neighborhood of a node. Each result includes the edge path from the start node. Default depth 2. |
| `search <text> [--type …] [--project …] [--tag …]` | Case-insensitive full-text search across notes; returns node IDs and line snippets. |
| `link <nodeA> <nodeB>` | Adds `[[B]]` under a `## Related` section of A (creating the section if absent). Both must be notes, or a task (resolved to its note). |
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
| `check` | Broken links, ambiguous link names, unknown efforts/goals, quarterly goals without valid parent, tasks without project, task notes whose `task_uuid` matches no task, tasks whose `note` points to a missing file. Exit 3 if any errors. |

**Maintenance**

| Command | Behavior |
|---|---|
| `migrate [--dry-run]` | See §9. |
| `completion bash` | Prints the bash completion script. |
| `install-skills [--target DIR]` | Symlinks `skills/*` into `~/.claude/skills/` (default). |
| `config show` | Effective configuration. |
| `help [command]`, `<command> --help` | Usage. |

### 6.3 Summary periods

- `week` = the ISO week (Mon 00:00 – next Mon 00:00, local time) containing today. `YYYY-Www` = that ISO week. `YYYY-Qq` = calendar quarter (Q1 = Jan–Mar). `YYYY` = calendar year.
- `--since <date|duration>` = rolling window from that point until now (a Taskwarrior-style duration such as `10d` or `1wk`, or an ISO date). Its file name is `summaries/since-<start-date>.md`.
- **Completed** = tasks with `status:completed` and `end` in [start, end).
- **In progress** and **review queue** = current state (`status:pending +week -review` / `status:pending +review`). Shown only when the period includes today; past periods do not reconstruct historical state.
- **Efforts listed** under a quarterly goal = efforts whose `quarter` overlaps the period, **or** that have a task completed in the period.
- **Carried over** = efforts whose `quarter` ended on or before the period's end and whose status is neither `done` nor `dropped`. Stale efforts therefore keep appearing until they are moved, closed or dropped.

### 6.4 Retired commands

**Retired / aliased:** `report-file [time]` → alias of `summary week --write` with no argument, or `summary --since <time> --write` with one. It prints the written file's path, as today, and is deprecated (removed after one release). Output moves from root `Weekly-Status-YYYY-WW.md` to `summaries/YYYY-Www.md`; the existing `Weekly-Status-*.md` files stay where they are as archives. `setup` → README. `000-Project-Index.md` is no longer generated; `goal tree`, `graph` and `search` replace it.

## 7. Architecture

Maven project, Java 25, GraalVM native-image profile.

| Module (package) | Responsibility | Interface (sketch) |
|---|---|---|
| `config` | Locate, parse and validate the config file. | `Config.load(args, env)` |
| `taskwarrior` | The **only** code that runs `task`. JSON → `Task` records; writes via `add`/`modify`/`start`/`stop`/`done`; ensures UDAs. Applies `task.rc`/`task.data`. | `TaskStore.query(filter)`, `add(…)`, `modify(ref, changes)`, `start/stop/done(ref)` |
| `vault` | Read/write notes under `data.location`: frontmatter, wikilinks, type inference, templates, managed sections, atomic writes. | `Vault.load()`, `note(id)`, `create(type, …)`, `updateManagedSection(note, name, content)`, `appendSection(note, heading, text)` |
| `graph` | Build the typed graph from `Vault` + `TaskStore`; traversal with edge paths. | `Graph.build(vault, tasks)`, `neighbors(node, depth, filters)` |
| `search` | Full-text search over vault notes. | `Search.find(query, filters)` |
| `planning` | Goals/efforts: ID allocation, validation, tree. | `Planning.newGoal(…)`, `newEffort(…)`, `tree(year)` |
| `reports` | `summary`, `recent`, `context`, `check` as pure functions returning models. | `Summary.of(period)` → `SummaryModel` |
| `cli` | Æsh commands, argument guard, completion (`__complete`, `completion bash`), `Renderer` (human/JSON). Commands return models; only the renderer prints. | — |

Dependencies point downward: `cli → reports/planning/graph/search → vault/taskwarrior → config`.

### 7.1 Taskwarrior integration

- Reads: `task rc.verbose=nothing rc.json.array=on <filter> export`, parsed with Jackson.
- Writes: `task rc.confirmation=off rc.verbose=nothing <ref> <command> <args>`; args passed as a list, never through a shell.
- Completion data: `task _projects`, `task _tags`, `task _ids`.
- Requires Taskwarrior ≥ 3.0; `ztk` checks `task --version` on first use per invocation and exits 4 with a clear message otherwise.

### 7.2 Bash completion

`ztk completion bash` prints a function registered with `complete -F`. It:

1. Calls `ztk __complete <cword> <words…>`, which returns candidates for ztk subcommands, options, node IDs, effort slugs, goal IDs, note names, and task IDs.
2. If `__complete` answers with the sentinel `@taskwarrior`, which it does for positions that pass through to Taskwarrior (e.g. attributes after `ztk add <desc>`, filters after `ztk list`), it rewrites `COMP_WORDS` to the equivalent `task …` command and calls Taskwarrior's bash completion function, if loaded.

The native binary keeps per-Tab latency low; the target is under 50 ms per `__complete` call.

**Spike first:** confirm Æsh can parse commands without starting its interactive console and works under native image. If not, switch the command layer to Picocli; the rest of the design is unchanged.

## 8. Error handling

- **Taskwarrior failures:** exit 4, include `task`'s stderr; no partial writes (validate first, write last).
- **Malformed frontmatter or unreadable note:** warning on stderr (or in the JSON `warnings` array), note skipped; other notes still load. `check` lists all of them.
- **Writes:** atomic (temp file in the same directory + rename). Re-read a file right before modifying it, since it may be open in nvim. Managed sections are replaced only between their markers; missing markers are appended at the end of the matching heading, or of the file if the heading is absent.
- **Validation:** unknown effort/goal/parent, missing project, or a duplicate slug → exit 3 with the valid options listed.
- **No silent creation:** referencing a non-existent effort never auto-creates it.

## 9. Migration (`ztk migrate`)

Runs idempotently; `--dry-run` prints every change without making it.

1. For each task without a `note` UDA: find a note with matching `task_uuid` and set `note`.
2. Move root-level zettels (`.md` with a `uuid` frontmatter and no `task_uuid`, excluding known files such as `progress*.md`, `AGENTS.md`, `CLAUDE.md`, `Weekly-Status-*.md`, `000-Project-Index.md`) into `folder.zettel`.
3. Report task projects that differ only by case (e.g. `infinispan` vs `Infinispan`) for manual fixing; never change them automatically.
4. Leave `progress*.md`, `Weekly-Status-*.md` and `000-Project-Index.md` in place as archives.

Wikilinks resolve by name, so moving files does not change link text. Before running step 3 for real, verify that Telekasten in nvim follows a bare-name link to a file in a subfolder (task notes in project subfolders are already linked this way from `000-Project-Index.md`), and point Telekasten's new-note location at `zettel/`.

## 10. Agent integration (bundled skills)

Repository `skills/` folder, installed with `ztk install-skills`:

| Skill | Purpose |
|---|---|
| `ztk` | Everyday agent usage: `recent` at session start, `context`, `add` (prints the new task's note path)/`cat`, `session add` at session end, `graph`/`search` for lookups, never run editor commands. Replaces `~/.claude/skills/ztk`. |
| `ztk-planning` | Collaborative goal → effort → task flow: read `goal tree` + last quarter's `summary`; brainstorm one goal at a time with the user; create it with `goal new`, then write Why / What done looks like together; propose efforts one at a time, creating each only after agreement and writing its note; break an effort into tasks (`add … --effort`) only after its note is agreed. The agent never creates goals or efforts without the user's explicit agreement. |
| `ztk-summary` | Expand `summary <period> --json` into a narrative for weekly/quarterly/yearly reviews, using only gathered facts, then save with `summary --write` or edit the written file. Replaces `weekly-summary`. |

Also shipped: an `AGENTS.md` template for `data.location` whose startup instruction is `ztk recent` instead of reading `progress.md`.

## 11. Testing

- **Unit:** config parsing, frontmatter/wikilink parsing, type inference, managed-section rewriting, ID allocation, graph traversal, summary models. Uses fixture vaults under `src/test/resources`.
- **Integration:** against the real `task` binary with `task.rc`/`task.data` pointing to a temp directory. Never touches the user's data; tests fail if `TASKRC`/`TASKDATA` would resolve to the real files.
- **Golden files:** JSON output of every read command. Shape changes require updating goldens and bumping `schema` if incompatible.
- **CLI:** argument guard (`--help`, unknown flags have no side effects), exit codes, non-TTY editor refusal, and path-result commands printing a bare path on stdout in both TTY and non-TTY mode with human messages on stderr.
- **Native smoke test** (`-Pnative`): build the binary, run `help`, `__complete`, `recent`, `graph` on the fixture vault, and assert latency targets.

## 12. Rollout

1. Spike: Æsh command parsing without the interactive console, plus a native-image build.
2. Build as `ztk2` alongside the bash `ztk`, pointing at the real `~/cerebro` via config.
3. `ztk2 migrate --dry-run`, review, then run it.
4. Update skills and `AGENTS.md`; install the skills.
5. Once the new tool matches the old one, install the binary as `ztk`, rename the bash script to `ztk.sh`, and keep it for one release cycle.

Suggested build phases for the plan: (1) config + Taskwarrior + task commands, (2) vault + graph + search + notes, (3) goals/efforts, (4) sessions + reports, (5) migration + skills + completion polish.

## 13. Open items

- Æsh vs Picocli: decided by the spike (§7.2).
- Persistent graph cache: only if the latency targets in §5 are missed.
