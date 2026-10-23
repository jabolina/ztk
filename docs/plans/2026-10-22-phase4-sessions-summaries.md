# ztk Phase 4 (Sessions and summaries) Implementation Plan

**Goal:** Record what was done: sessions written by agents at the end of their work on a task, shown by `info` to the next agent; and summaries of a week, a quarter or a year, gathered by fixed rules and saved once with an agent's narrative. Enough to replace reading `progress.md` and the bash `report-file` with the `weekly-summary` skill.

**Spec:** `docs/specs/2026-10-15-ztk-design.md` §1, §4.2 (Session), §4.5 (goal templates without a retrospective), §4.6 (journal format), §5 and §6.2 (`graph` leaves journal and summary notes out, `info` with sessions, `session add`, `summary`), §6.3 (summary periods and rules), §6.4, §10 (`ztk`, `ztk-summary`), §13. Domain terms: `docs/CONTEXT.md` (Sessions and summaries).

**Out of scope:** the migration of `progress*.md` and `Weekly-Status-*.md` (Phase 5, with the one-week shift noted in §13) and the skills (Phase 5). Dropped: `recent` and `context` (`info` gives a task's last sessions; the rest was one call away), `summary --since` and the `week` keyword (no argument means the previous week), `--force` on `summary --write`, the `report-file` alias, summary notes as graph hubs, the goals' `## Retrospective` section, a lock on the journal.

**Plan status:** a draft. The order below is a suggestion; classes and interfaces are decided during development, following the `tdd` and `improve-codebase-architecture` skills.

## Where we left off (2026-10-22, Task 3 done)

The design was settled in an interview on 2026-10-22 and written into the spec and `CONTEXT.md`. Task 1 (sessions) is done: `session add`, `info` with the last 3 sessions, the `journal` note type, and journal notes left out of `graph`. Task 2 (weekly summary) is done: `summary [YYYY-Www] [--write] [--narrative text|-]`, the `summary` note type, and summary notes left out of `graph`. Task 3 (quarter and year summaries) is done: `summary YYYY-Qq|YYYY`, the plan tree, other goals, general work, the needs-closing refusal on `--write`, and goal templates without `## Retrospective`. Task 4 (completion and native smoke test) is done: `session add` completes task IDs, `summary`'s period offers no file names, and the smoke test covers sessions and summaries. Phase 4 is done. The end-of-phase review was skipped: its items (`graph` knowing every module's note types, the `STDIN` constant repeated in `SessionCommand` and `SummaryCommand`) wait for the project-wide redesign. Next: Phase 5 (migration and skills).

How Task 1 is put together (keep it this way):

- `journal.Journal` owns the session's shape, writing and reading it: `add(task, title, done, now, next)`, `last(task, count)` and `sessions(from, to)`. `journal.Session` is the record, its `writeJson` the shape `info` prints. The command checks what was typed (one part from stdin, blank parts, a one-line title, the length warning); `Journal` refuses deleted tasks.
- Sessions are appended to the day's file, never rewriting it; two written at once may share an `#n` (the spec has the trade-off). `last` walks the day files newest first, from the task's `entry` day, parses only files holding the UUID, and stops at the count. `sessions(from, to)` opens each day's file by name, without listing the folder.
- Stdin is read by `ZtkInvocation.stdin()` from `System.in`: Æsh's `getStdin()` only sees its console's own pipes, never the process's (`docs/personal.md`, item 5).
- `graph.Graph` leaves the note types in `GENERATED` (`JOURNAL`, `SUMMARY`) out of every walk except as the start node.

How Task 2 is put together (keep it this way):

- `reports.Summaries` holds the rules: `week(Week)` gathers a `WeeklySummary` from one Taskwarrior export (`( status:pending or status:waiting or modified.after:<week start> )`, then the exact rules in Java), the week's sessions indexed by task, and the plan for each task's goal; `write(summary, narrative)` refuses a week not ended or a file already written (`CREATE_NEW`), and appends `---` and the narrative. `lastWeek()` is the default period.
- `reports.Week` is the ISO week (`parse`, `previous(clock)`, `first`, `last`); quarters and years are `planning.Period` (`start()` added).
- `WeeklySummary` (with `Project` and `Item`) is the model, written through `util.JsonWritable` and `util.MarkdownWritable` (new; `writeMarkdown(StringBuilder)`). The human output and the file are the same Markdown.
- `Task` now carries `modified`, `started` (replacing the `active` component; `active()` is a method) and `end`.
- Agreed rules beyond the spec: a task completed before the week is left out even when touched in it; deleted tasks are left out; waiting counts as pending; projects by name, no project last; tasks oldest `entry` first. Each task's JSON carries `untouched` (the day it was last modified, when it is in only for being on the plate).
- Tests import tasks with `task import`, the only way to give Taskwarrior a past `modified`.

How Task 3 is put together (keep it this way):

- `Summaries.of(period)` is the one way in: a `YYYY-Www` goes to the weekly rules, a `YYYY-Qq` or `YYYY` to the plan's, anything else gets one usage error naming the three. It returns the sealed `reports.Summary` (`WeeklySummary`, `PlanSummary`: `name()`, `last()`, JSON and Markdown), which `write` takes. `week` and `plan` are private.
- The private record `Summaries.Activity` (the period's bounds and its sessions by task) holds the sign-of-life rule both kinds share; `untouched` is "not alive".
- `plan(Period)` makes one pass over the Taskwarrior export with `Collectors.teeing`: tasks whose effort hangs under a known goal go into an index of `Item`s by effort (open untouched ones too, since an effort may be listed for another of its tasks), the rest into `ProjectFacts` per project. The private record `Summaries.Tree` (period, plan, index) holds the listing rules: which efforts are listed, the period's goals (recursing from a yearly goal to its quarterly goals), the other goals, and carried over as of the period's end. The user asked for the fewest copies and no intermediate collections; keep it that way.
- `reports.Item` is the task of either summary (`sessions` is empty when not gathered, and left out of the JSON); `writeLine(target, details)` is the one line format, each summary passing its own details. `Goal.ORDER` (moved from `Goals`) orders goals everywhere.
- Agreed beyond the original spec (now in §6.3): every other goal holding a listed effort is listed, later ones too (`in <quarter>`), so work on an effort moved on by the review before writing stays in the period; carried-over efforts are always listed under their earlier goal; tasks whose effort or goal does not exist are general work; needs closing only counts goals listed in the summary, so yearly goals block only the year's; `carriedOver` in a summary's JSON is as of the period's end.

How Task 4 is put together (keep it this way):

- `session add` already had `TaskCompleter`; only its test was new. Æsh falls back to file names for every `String` argument without a completer (`ProcessedOptionBuilder`), so `summary`'s period sets `completeFallback = NONE`. Text options (`--title`, `--narrative`, …) keep the fallback, as in every earlier command.
- The smoke script generates the journal as files, three sessions a day on one task for 300 days, and imports a task entered 300 days ago with one session the next day: `info` on it reads every day back to its entry. It writes last week's summary with a narrative from stdin and last quarter's without one, and checks that an open quarter is refused. First native timings: `info` 16 ms, weekly and quarterly `summary` 16 ms each, `session add` completion 13 ms.

## Decisions (summary; the spec has the details)

- **A session is of one named task,** active or not (agents often work on a task without starting it); a deleted task is refused. No default from the active task.
- **ztk owns the session's shape:** `session add <task> --title … --done … --now … --next …`, every part required, any one `-` for stdin. Written as `## [[<uuid>]] · <title>` and three labeled lines in `journal/YYYY-MM-DD.md`, ID `YYYY-MM-DD#n` by position. Free-form summaries drift: agents write after the entries they read, and `progress.md` entries grew from a few lines to 68. A part over 3 lines is written with a warning on stderr, never refused.
- **No `recent`, no `context`:** reading other tasks' sessions loads unrelated work into an agent's context, and everything else `context` bundled was one call away. `info <task>` gives the task's last 3 sessions, reading the journal from the newest day back to the task's `entry` day and parsing only files holding its UUID.
- **Journal and summary notes are out of `graph`:** never listed nor walked through (a task worked over many days or weeks would list each), still searchable and checked, still accepted as the start node. Projects are the only hubs.
- **Weekly summary:** no argument means the previous ISO week, whatever day it runs (a holiday Monday moves the run, not the week); `YYYY-Www` for a missed one; the file is named after the week it covers. A task is in it when completed, given a session or modified in the week, or pending now with `+review`/`+week`. Grouped by project: completed, under review, in progress (the last two as of the day taken). Each task carries its effort and goal; an untouched one is marked `(untouched since <day>)`.
- **Quarter and year summaries** are the retrospective's facts, the same filter over one quarter or four: goals of the period → efforts → tasks (each with its sessions in JSON), then older goals still holding carried-over efforts (`from <quarter>`), then general work per project without sessions. A year adds the yearly level on top.
- **JSON is the whole of the facts,** sessions included; human output and the written file are one Markdown without sessions, with frontmatter `title`, `period`, `date`.
- **Writing:** `--write --narrative -` writes facts and narrative together, so agents never edit the file and it is never half written. Written once, never overwritten, no `--force`. Only an ended period is written. A quarter or a year is not written while a goal needs closing: the review closes them, and settles every carried-over effort, which otherwise keeps its goal open.
- **Retrospective** lives in the quarter's or year's summary; goal templates drop `## Retrospective`, the closing status is the verdict. The effort's `## Outcome` stays.
- **No lock on the journal:** sessions are written one at a time in practice; the trade-off is in the spec.

## Tasks

Each task is one complete piece of functionality and ends with a commit. Tasks are deliberately large.

1. **Sessions.** The journal format (writing a day's file, reading sessions back by task), `session add` (task lookup, every part required, stdin for one part, the length warning, path-result output), `info` with the last 3 sessions in both output modes, and journal notes left out of `graph` (start node still accepted). First test: `session add 1 --title … --done … --now … --next …` writes today's journal file with the session under `[[<uuid>]]`.
2. **Weekly summary.** Periods (default previous week, `YYYY-Www`), the inclusion rules over tasks, journal and `modified`, the per-project Markdown and the JSON, `--write` and `--narrative -`, the refusals (file exists, period not ended), and summary notes left out of `graph`.
3. **Quarter and year summaries.** The plan tree (goals → efforts → tasks with sessions), older goals with carried-over efforts, general work, the year's extra level, the needs-closing refusal on `--write`; goal templates without `## Retrospective`.
4. **Completion and native smoke test.** Task IDs for `session add`; the smoke script writes sessions and summaries on the scratch knowledge base and times `info` against a journal of a few hundred days, and `summary` for a week and a quarter.

## Done when

- Every command above works as the spec says, in both output modes, with actionable errors.
- A summary is reproducible: the same knowledge base and Taskwarrior data give the same facts.
- The native smoke test passes, with `info` and a weekly `summary` well under the 300 ms target.
