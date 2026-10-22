# ztk Phase 3 (Goals and efforts) Implementation Plan

**Goal:** Plan work in ztk: yearly goals, quarterly goals and efforts as Markdown notes, tasks joined to efforts through the `effort` attribute, the commands to create, change and list them, and their place in `graph`, `check` and completion. Enough for an agent to manage the plan from a chat, and for the Phase 4 summaries.

**Spec:** `docs/specs/2026-10-15-ztk-design.md` §4.1, §4.2 (entities, levels and material, status, `closed`, strict rules), §4.4, §4.5 (templates), §5 (kinds, edges), §6.2 (Goals and efforts, common task shape, `graph`, `check`), §6.3, §7.2, §10 (`ztk-planning`). Domain terms: `docs/CONTEXT.md` (Planning).

**Out of scope:** summaries and `context` (Phase 4, which reads `closed`, carried over and needs closing); the `ztk-planning` skill (Phase 5). Dropped: generated sections in goal and effort notes, `goal tree`/`goal show` (now `goal list`/`goal info`), `task-effort` (now `effort assign`/`unassign`), the effort `quarter` field, efforts without a goal, renaming and deleting through ztk, fiscal quarters.

**Plan status:** a draft. The order below is a suggestion; classes and interfaces are decided during development, following the `tdd` and `improve-codebase-architecture` skills.

## Where we left off (2026-10-21, Phase 3 done)

The design was settled in an interview on 2026-10-20 and written into the spec and `CONTEXT.md`. All four tasks are done: goals (`goal new|set|list|info`), efforts and their tasks (`effort new|set|list|info|assign|unassign`, `add --effort`), graph and check (`goal`/`effort` nodes and edges, the nine planning codes), and completion with the native smoke test. The end-of-phase review then did #2 (below). The review is closed. Next: Phase 4.

End-of-phase review (started 2026-10-21): the user judges the architecture crooked from the start (organized around how things are read, with no joined model of the knowledge base, so `graph` became where everything meets). Agreed: only #2 now, one model of the plan's rules used both to refuse changes and to report problems, as the rules are written twice and already differ. Dropped: notes, tasks and planning contributing to the graph through a seam (moves code, adds indirection). Deferred to a project-wide redesign once every phase has landed and the CLI tests cover it all: one read of every note typed by its owner, and the joined model itself.

Design of #2 (agreed in a grilling session on 2026-10-21; the spec §4.2 and §6.2 `check` have the rules):

- Open work never sits under something closed, at all three levels; closing a goal or effort with open direct children is refused (any closed status, no `--force`), listing each child with its fix. `open-task-in-closed-effort` becomes `open-under-closed`, an error at every level, one problem per open child.
- The rules live once in `planning`, returning problems with code, severity, item, message and hint; all nine planning codes move there. `planning.Plan` grows from a reader into the loaded plan (goals by ID with duplicates, efforts by slug, tasks when given). `graph.Check` asks it for its problems; `Problem`'s planning records collapse into one wrapping a plan problem (a task's problem keeps its task, for `--project`).
- A planning write applies the change to the plan in memory and is refused when it has an error that was not there before (compared by code, item and the item it names); new warnings never block. The write side keeps no rule code. Tasks are read only by changes that can affect them (`effort set --status`, `effort assign`, `add --effort`); `check` passes its own export. `effort unassign` is not checked.
- Creation (`goal new`, `effort new`, `add --effort`) asks the same rules but words the refusal itself ("Not created:", the goals it could choose, or how to reopen), since the plan's hints change items that exist. Command-line shape (a quarterly goal needs `--parent`, a yearly goal takes none) stays a `USAGE` check in the use cases.
- Done (2026-10-21) in three TDD steps: (1) `open-under-closed` in `check` at three levels, moving the model and the planning problems into `planning` under the existing check tests; (2) closing refused with open children; (3) every other planning write through "no new errors", deleting the old rule code and updating refusal tests to the shared wording.

How `planning` is put together (agreed before Task 2; keep it this way):

- Use cases on top, readers underneath, and use cases never call each other. Public: `Goals`, `Efforts` (use cases), `Levels` (the only `notes.PlanningNotes` adapter: which notes are goals or efforts), and value types (`Goal`, `Effort`, `GoalStatus`, `EffortStatus`, `Period`, `Saved`, `TaskCounts`, the nested records). Package-private readers: `GoalNotes` (`find`, `of(period)`, `period(id)`), `EffortNotes` (`find`, `all`; works out quarter and carried over from the goal ID), `EffortTasks` (one export: `counts()`, `of(slug)` with `effort.is:`). `PlanningStatus` holds the `closed` rule for both statuses. `Plan` (public) holds the plan's rules for every reader and writer.
- `Goals` reads efforts through `EffortNotes`/`EffortTasks`, not `Efforts`; `Efforts` reads goals through `GoalNotes`. Planning depends on tasks (`Efforts.add` calls `Tasks.add`), never the reverse. Both reach Taskwarrior through `Supplier<TaskStore>`, so commands that need no tasks never start it.
- `PlanningNotes.planned(Path)` returns the note type and node ID; `Note.Planning` is the variant for goal and effort notes. A broader "note types as a seam" refactor of `KnowledgeBase` was discussed and put off: assess at the end of Phase 3 with the rest of the codebase.

How Task 3 joined planning to the graph:

- `graph` depends on `planning`, never the reverse. Since the review, `planning.Plan` is the loaded plan (`Plan.read`: goals by ID with every file, efforts by slug, misnamed goal-level notes) with lookups, `problems(tasks)` (every planning rule, as `PlanProblem`s) and `added(changed, before, after)` (the errors a change would add). The graph keeps the `Plan` and wraps goals and efforts as `Node.OfGoal`/`Node.OfEffort` when asked; two files with one goal ID make one node, the first read.
- `graph.Check` asks the plan for its problems, passing its own task export, and wraps each in `Problem.InPlan` (with the task's node, for `--project`). Each rule yields an `Optional`; nothing is collected in between.
- Setting the current closed status again writes today's `closed` when it is missing, so `closed-mismatch` is fixed by a command.
- Goal and effort files are still read twice (wikilinks by the knowledge base, frontmatter by `Plan`); unreadable goal or effort files still fail the command, naming the file.

What Task 4 builds on: `--kind`/`--edges`/`check --code` already list the new values as fixed lists; `graph` start nodes accept `goal:<id>`, a bare goal ID, `effort:<slug>` and an effort's bare slug.
- Taskwarrior: `effort:x` filters by prefix (use `effort.is:`); `modify effort:` clears it; abbreviations down to `ef:` set it.
- `checked(slug)` stays in both `Goals` and `Efforts`: the hints differ with how the slug is given (`--slug` or an argument), and merging them would put command-line wording in `util` to save a few lines. The status parsers in `GoalCommand`/`EffortCommand` shrank to one line each once Æsh validated `--status` (Task 4).
How Task 4 completes the plan:

- `cli.completers.GoalCompleter` (every goal; nested `Yearly`/`Quarterly`: open goals of that level) and `EffortCompleter` (every effort; nested `Open`) read through `Context.plan()`. Closed items are offered where they can still be shown or reopened, only open ones where new work goes under them.
- `--status` uses `allowedValues`, so Æsh rejects unknown statuses with the valid ones listed; the commands' own status errors are gone. A completion test keeps the lists in step with the enums.
- Æsh prints its file sentinel from the command's positional argument alone, even while completing an option's value (`docs/personal.md`, item 4); every planning command sets `completeFallback = NONE` on it.
- Native smoke test (2026-10-21): completion of goals and efforts 6 ms, `goal list` 6 ms, `effort info` 15 ms, `graph` from a goal 44 ms.

- For the end-of-phase review (raised at the start of Task 3): `notes` and `planning` each know their own items, yet `graph` must know every kind (node variants, edges, check rules), so planning knowledge leaks into it. Task 3 took the direct route; the review dropped the seam and moved only the planning rules into `planning` (#2).

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
- **Check:** errors `unknown-effort`, `missing-goal`, `unknown-goal`, `invalid-status`, `invalid-goal-name`, `duplicate-goal`; `open-under-closed` (an error since the review; it was the warning `open-task-in-closed-effort`); warnings `needs-closing`, `closed-mismatch`. Carried over is not a problem.

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
