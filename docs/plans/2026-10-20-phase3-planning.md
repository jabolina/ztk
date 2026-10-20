# ztk Phase 3 (Goals and efforts) Implementation Plan

**Goal:** Plan work in ztk: yearly goals, quarterly goals and efforts as Markdown notes, tasks joined to efforts through the `effort` attribute, the commands to create, change and list them, and their place in `graph`, `check` and completion. Enough for an agent to manage the plan from a chat, and for the Phase 4 summaries.

**Spec:** `docs/specs/2026-10-15-ztk-design.md` §4.1, §4.2 (entities, levels and material, status, `closed`, strict rules), §4.4, §4.5 (templates), §5 (kinds, edges), §6.2 (Goals and efforts, common task shape, `graph`, `check`), §6.3, §7.2, §10 (`ztk-planning`). Domain terms: `docs/CONTEXT.md` (Planning).

**Out of scope:** summaries and `context` (Phase 4, which reads `closed`, carried over and needs closing); the `ztk-planning` skill (Phase 5). Dropped: generated sections in goal and effort notes, `goal tree`/`goal show` (now `goal list`/`goal info`), `task-effort` (now `effort assign`/`unassign`), the effort `quarter` field, efforts without a goal, renaming and deleting through ztk, fiscal quarters.

**Plan status:** a draft. The order below is a suggestion; classes and interfaces are decided during development, following the `tdd` and `improve-codebase-architecture` skills.

## Where we left off (2026-10-20, Task 1 done)

The design was settled in an interview on 2026-10-20 and written into the spec and `CONTEXT.md`. Task 1 (Goals) is done: `goal new|set|list|info`, and goal notes read as `goal:` nodes in `search`. Next: Task 2 (Efforts and their tasks).

What Task 1 left in place for the next tasks:

- `planning` package: `Goals` (create, update, list, info; implements `notes.PlanningNotes`), `Goal`, `GoalStatus` (`Labeled`, `closed()`), sealed `Period` (`OfYear`/`OfQuarter`, `folder`, `end`, `ofYear`, `quarters`), package-private `Levels` (which files are goals). `Goals` methods take records (`Details`, `Changes`), never `Optional` parameters.
- `notes.PlanningNotes` is how the knowledge base learns which notes are goals without knowing the layout; Task 2 adds `effort(Path)` to it, and may let it skip material folders during scans.
- `util`: `Frontmatter` (read, parse, render, `update` in place keeping the body byte for byte, `timestamp`), `Slugs` (`of`, `of(text, max)`, `valid`), `Labeled` (lowercase enum labels, `parse`, `labels`).
- Until Task 3, a goal note is a `note`-kind graph node with a `goal:` ID, and `graph` does not find it by file name.
- Unreadable goal files fail the command, naming the file; Task 3's `check` reports them.

**Working agreement:** TDD; Claude writes tests and snippets, the user writes production code and runs the tests. Tasks are large: inside one, walk in larger strides (several behaviors per step when they are mechanical). Commit once a whole task is done. Verify Æsh/Taskwarrior behavior in the scratchpad, never against real data. Real notes in `~/cerebro` are only read, never written; nothing about this work goes there.

## Decisions (summary; the spec has the details)

- **Hierarchy:** yearly goals (several per year, open-ended, agreed with the manager) → quarterly goals (achievable steps, exactly one yearly parent of the same year) → efforts (exactly one quarterly goal; tasks may span projects) → tasks. Tasks without an effort are general work, never flagged; a small group of it is a note linking its tasks by UUID.
- **Files and IDs:** `goals/2026/2026-G1-<slug>.md`, `goals/2026/Q4/2026-Q4-G1-<slug>.md`, `efforts/<slug>.md`. The ID comes from the file name; the folder must agree. Numbers: highest in use + 1. Effort slugs are unique across the knowledge base. Slugs and IDs never change through ztk. Calendar quarters.
- **Levels and material:** every `.md` directly in `goals/<YYYY>/`, `goals/<YYYY>/Q<q>/` or `efforts/` is a goal or effort; any other folder there is material (plain notes, type `other`), as the user keeps task material in `<Project>/data/<uuid>/`.
- **Frontmatter:** `title`, `status`, `closed`, `date`; `parent` (quarterly goals) and `goal` (efforts) hold plain IDs. No `uuid`, no `quarter`.
- **Status:** goals `open | reached | missed | dropped`, efforts `planned | active | done | dropped`, set by hand only. `closed` is written and removed with the status. Strict: no open work under something closed, with the reopening command in the error.
- **Templates:** built in, AI-friendly and career-oriented (Agreement, Done when checklists, Decisions log, Retrospective/Outcome with evidence). No generated sections: ztk never touches a body after creation.
- **Commands:** `goal new|set|list|info`, `effort new|set|list|info|assign|unassign`, `add --effort`; `effort` in the common task shape. `goal list` defaults to the current year, `effort list` to every open effort.
- **Graph:** kinds `goal`, `effort`; edges `effort`, `goal`; bare goal IDs as input; not hubs.
- **Check:** errors `unknown-effort`, `missing-goal`, `unknown-goal`, `invalid-status`, `invalid-goal-name`, `duplicate-goal`; warnings `needs-closing`, `closed-mismatch`, `open-task-in-closed-effort`. Carried over is not a problem.

## Tasks

Each task is one complete piece of functionality and ends with a commit. Tasks are deliberately large.

1. **Goals.** Reading goal notes (levels, IDs from file names, frontmatter, material as `other`; `search --type goal` with `goal:` IDs), then `goal new` (numbering, slug from title, template, parent validation, path-result output), `goal set` (status, `closed`, title, parent, strict rules), `goal list` (period, defaults, nesting, needs closing) and `goal info` (subtree; efforts and task counts arrive with Task 2, so start with quarterly goals). First test: `goal new 2026 --title …` creates `goals/2026/2026-G1-<slug>.md` and prints its path.
2. **Efforts and their tasks.** Reading effort notes (top level of `efforts/`; `search --type effort`), `effort new`/`set` (slug rules, goal validation, carrying over, strict rules), `effort` in the common task shape, `effort assign`/`unassign`, `add --effort`, `effort list` (defaults, carried over) and `effort info` (goal chain, tasks by status); then `goal info` and `goal list` gain efforts and task counts. Touches Phase 1's task code.
3. **Graph and check.** `goal`/`effort` kinds and edges, bare goal IDs, filters; the nine `check` codes. Built on the model from Tasks 1–2.
4. **Completion and native smoke test.** Goal IDs (yearly for `--parent`, quarterly for `--goal`), effort slugs, `--status` values; the smoke script creates goals and efforts on the scratch knowledge base, assigns tasks, and times `goal list`/`effort info`/`graph` against the existing targets.

## Done when

- Every command above works as the spec says, in both output modes, with actionable errors.
- `check` reports the planning problems on a scratch knowledge base built to have each of them.
- The native smoke test passes, with completion under 50 ms and `graph` under 300 ms.
