# ztk Phase 2 (Notes) Implementation Plan

**Goal:** Read the knowledge base: `search`, `graph` and `check` over the notes and their links, plus `paper new`. Everything is read-only on existing notes; `paper new` only creates files.

**Spec:** `docs/specs/2026-10-15-ztk-design.md` §3 (folders), §4.4 (types), §4.5 (templates), §5 (node IDs, wikilinks, edges), §6.2 (Notes, Navigation, `check`), §7.2 (completion), §11. Domain terms: `docs/CONTEXT.md`.

**Out of scope:** `context`, sessions, summaries (Phase 4); goal and effort nodes and their `check` problems (Phase 3); migration and skills (Phase 5). Dropped: `link` and `new --source` (users and agents write wikilinks themselves), tags (not a ztk concept).

**Plan status:** a draft. The order below is a suggestion; classes and interfaces are decided during development, following the `tdd` and `improve-codebase-architecture` skills.

## Where we left off (2026-10-19, Phase 2 done)

Tasks 1 to 3 are done and committed. Task 2 brought in reading the knowledge base and `search`:

- `notes.KnowledgeBase.read` walks lazily (recursive `Files.list`, hidden folders never entered, `AGENTS.md`/`CLAUDE.md` excluded) and streams each note's body lines to a caller-supplied reader; bodies are never held in memory. `read(project, …)` walks only that project folder (`projectFolder(project)`, never a reserved one).
- `Note` is a sealed interface: `Note.Header` (name, path, title, modified) plus variants `Note.Task` (UUID), `Note.Paper` (PDF) and `Note.Plain`; `NoteType` is the enum behind `--type`. Types: zettel, paper, task, project, other (reserved folders are `other` until their own types exist).
- Frontmatter is YAML, falling back to flat `key: value` lines (56 real titles with an unquoted `: `). Unreadable notes (not UTF-8, no permission) are skipped with a `NoteWarning` in JSON `warnings` or on stderr (`ZtkInvocation.warnings(json)`, shared by `search` and `graph`).
- `search.Search.find(Query, warnings)` returns a sorted stream of `Match`; the command collects it for the JSON and the human `cli.renderer.Table`.

Task 3 brought in `paper new` (`notes.Papers`, `cli.commands.PaperCommand`).

Task 4 (`graph`) is done and committed (175 tests in the suite).

- `notes.Wikilinks.links(lines)` (each a `Wikilink(target, line)`): `[[target]]` before `|`/`#`, outside fenced (```` ``` ````/`~~~`, by matching character) and inline code; `[[#heading]]` names nothing.
- `graph.KnowledgeGraph.read(knowledgeBase, store)` (built by `Context.graph()`, on every call) reads the notes once (headers and wikilinks only, `NoteLinks`, built with `collect`; eight maps), then one `task export` of non-deleted tasks. It keeps the notes it skipped (`skipped()`, public: `graph` copies them into its `warnings`) and, while reading, the facts only it can tell: tasks whose note attribute names no readable note, orphan task notes. Package-private, for `Graph`: `find(id)`, `edges(node)` (every edge as a `graph.Edge` seen from the node, a project's tasks and a dangling target's linking notes included); for `Check`: `links()` (each wikilink resolved), `tasks()`, `tasksWithMissingNote()`, `orphanTaskNotes()`, `project(name)` (a task's project, or a project folder). The task-to-note rules (attribute relative to the knowledge base, then `task_uuid`) live only in `read`. No walk policy, no notion of problems.
- `graph.Graph.walk(Query)` walks a `KnowledgeGraph` lazily, one depth at a time (`Stream.iterate` over depths), so `--limit` stops the walk; projects are expanded only at depth 0 and dangling targets never (`Graph.around`).
- `graph.Node` is sealed: `OfNote`, `OfTask` (with its note: UDA `note`, else `task_uuid`), `OfProject` (with its folder), `OfDangling`. `graph.Reached(node, depth, via)`, where `via` is the `graph.Edge(from, to, kind, direction)` it was reached through (JSON `{from, edge, direction}`).
- `KnowledgeGraph.resolve(from, target)` is the only place links are resolved, for both directions: same folder first, then every note of that name, then a task by full or 8-hex UUID, else dangling.
- `KnowledgeGraph.find(id)` takes `note:<path|name>`, a bare name (as a link at the root), `task:<uuid|short|number>`, a bare number, `project:<name>`; none exits 2, several exit 3 listing the IDs.
- `Graph.Query(start, depth, kinds, types, edges)`: filters apply before `seen`, so excluded nodes are neither listed nor walked through. `--kind`/`--edges` use Æsh `allowedValues`; `--type` reuses `NoteTypeConverter`.
- `cli.commands.GraphCommand`: `--depth` (default 2, 0 or more), `--limit` (default 50, 1 or more), `truncated` and `hint` in the JSON, the hint also on stderr for people; table DEPTH, KIND, TYPE, TITLE, PATH.
- Naming: node `kind` (`note`, `task`, `project`, `dangling`) versus note `type`; `check` problems use `code` (not `kind`), so the two never collide.
- Verified end to end in a scratch knowledge base with its own `TASKRC`/`TASKDATA` (never the real Taskwarrior: `graph` runs `task export`).

Task 5 (`check`) is done and committed (188 tests in the suite). A short UUID of eight digits used to read as a task number only; `matches` now tries both (a flaky Task 4 test, made deterministic with `task import`).

- `graph.Problem` is sealed, one record per code: `DanglingLink`, `AmbiguousLink` (with `candidates`), `MissingNote`, `TaskWithoutProject`, `OrphanTaskNote`, `UnreadableNote`; sub-interfaces `InLink` (id, path, line from the wikilink) and `InTask` (id, path from the task). Each record writes its own message and hint; `writeJson` writes every field, empty ones as null.
- `graph.Check(graph).problems(Filter)`: one detector per fact (links, missing notes and tasks without project of open tasks — `Task.open()` —, orphan task notes, skipped notes), filtered by `Check.Filter(codes, project)` and sorted errors first, then path (none last), line, id, code. `--project` keeps a task's problems by its project and the rest by path under the project folder; a project with no task and no folder exits 2.
- `cli.commands.CheckCommand`: `--code` (Æsh `allowedValues`), `--project`; skipped notes are reported as `unreadable-note` problems, so the JSON has no `warnings`; exit 3 after printing when errors remain after filtering. `NoteWarning` gained a `hint`, also printed for `search`/`graph`.
- One problem per wikilink occurrence, at its line. No `invalid-frontmatter`: the fallback reads those notes correctly, and the migration quotes them.
- Run read-only on the real base (a copy of the Taskwarrior database, `gc`/`recurrence`/`hooks` off): 7 `dangling-link` and 21 `ambiguous-link`, all in `000-Project-Index` (its `[[SKILL]]`/`[[CLAUDE]]`/`[[AGENTS]]` links and per-project `[[ideas]]`/`[[summary]]`/`[[CONTEXT]]`), which the user deletes; 3 `orphan-task-note`. No task problems.

Task 6 (completion) is done and committed (191 tests in the suite). Only behavior ztk controls is tested; Æsh's own matching and fixed lists are not.

- Completers live in `cli.completers` (`ZtkCompleter`, `ZtkCompleterInvocation` and every completer). `NodeCompleter` on `graph`'s argument offers note names from `KnowledgeBase.names()` (files listed, never read; the note filter is shared with the walk), by file name or, when notes in several folders share it, by path; pending task IDs; `project:<name>`. `NoteTypeCompleter` on `--type` (`graph`, `search`); `ProjectCompleter` on `check --project`. `search`'s words and `graph`'s node turn off the file fallback.
- `TaskStore.ids()` dropped the empty line `task _ids` prints without tasks (an empty candidate for `done <TAB>` too).
- Comma lists: Æsh's `--aesh-complete` prints a list value without the values before it (`task` for `note,t`), which bash and zsh drop. `Ztk.ContainerBuilder.completers` wraps every list option's completer in `WholeWords`, which prefixes the values Æsh parsed for the option (`ProcessedOption.getValues()`, `getEndsWithSeparator()`). A repeated list option (`--kind a --kind b`) gets no completion. `CompletionTest.aCommaListIsCompletedAsAWholeWord` fails once Æsh prints whole words itself: then delete the wrapper. Reproducer and other Æsh rough edges: `docs/personal.md` (not committed).
- Real base, read-only (copy of the Taskwarrior database, deleted after): `graph <TAB>` offers 416 candidates (327 bare names, 22 paths for `summary`/`ideas`/`CONTEXT`, 60 IDs, 7 projects), no duplicates; 35 sampled candidates, all paths included, resolve with `graph`.

Task 7 (native smoke test) is done: `scripts/native-smoke.sh` also runs `paper new` (built-in template in the native image), `search`, `graph` and `check` on a generated knowledge base of 1,000 linked notes, `graph` node and comma-list completion, and times them. Native binary, 2026-10-19: completion 2 ms (subcommands), 13 ms (task IDs), 21 ms (graph nodes), against 50 ms; `search` 34 ms and `graph` 46 ms on 1,000 notes, against 300 ms. Every "Done when" item below holds, so Phase 2 is complete.

**Next steps, in order:**
1. Phase 3 (goals and efforts): designed on 2026-10-20; continue in `docs/plans/2026-10-20-phase3-planning.md`.
2. Open question, not done: reject a folder nested inside another (`notes` and `notes/papers`)? The spec only rejects the same folder.
3. Maybe: `--year twenty` prints only Æsh's "Invalid integer value"; a `YearConverter` could name the option and give an example.
4. Phase 4: summary notes are hubs in `graph`, like projects (spec §6.2). Seen on the real base: from a task note, depth 2 walks into `000-Project-Index` and `Weekly-Status-*` and out to every task they list; `--depth 1` or `--edges note,project` avoid it until the migration.
5. Phase 5 migration: existing paper notes have no `pdf` field; set it where a PDF with the same base name sits next to the note.
6. The "relative path without `.md`" naming rule is in both `KnowledgeBase.name` and `Papers.create`; `graph` did not need it (it resolves the UDA with `KnowledgeBase.resolve`).

**Working agreement:** TDD, one test at a time; Claude writes tests and snippets, the user writes production code and runs the tests. Commit once a whole command is done. Verify Æsh/Taskwarrior behavior in the scratchpad, never against real data. Real notes in `~/cerebro` are only read (surveys, final checks), never written; nothing about this work goes there.

## Decisions (summary; the spec has the details)

- **Reading:** every `.md` under `data.location`, recursively, skipping hidden directories and `AGENTS.md`/`CLAUDE.md`. Types by location only. Frontmatter that is not valid YAML (55 bash-written titles with an unquoted `: `) falls back to flat `key: value` lines; an unreadable file: warning, note skipped.
- **Wikilinks:** outside fenced code and inline code; target before `|`/`#`; exact file-name match. Resolution: same folder first, then all matches (ambiguous), then task UUID (full or 8-hex short), else dangling. Surveyed on real notes: skipping code removes all false positives (bash `[[ … ]]`); 15 UUID links; `summary`/`ideas` repeat per project.
- **Node IDs:** printed `task:<uuid>`, `note:<relative path without .md>`, `project:<name>`. Input also takes `task:<id|short>`, bare numbers, bare names; no file paths. Several matches exit 3 listing IDs; none exit 2.
- **`search`:** words ANDed across the note, `"phrase"`, body + `title`, no regex or fuzzy; up to 3 snippets with line numbers; `--type`, `--project`; never calls Taskwarrior.
- **`graph`:** agent-first. Edges `link` (out/in), `note`, `project`; breadth-first, depth 2; projects are hubs; filters restrict the walk; flat JSON with paths, cap 50 with `truncated`.
- **`check`:** errors `dangling-link`, `missing-note`, `task-without-project` (open tasks); warnings `orphan-task-note`, `ambiguous-link`, `unreadable-note`; hints, `candidates` for ambiguous links; no `--fix`. No `invalid-frontmatter`: the fallback reads those notes correctly, and the migration quotes them. The whole knowledge base is read; `--code`/`--project` only filter.
- **`paper new`:** PDF from anywhere, copied to `papers/<venue|others>/<year>/<slug>.pdf`, note next to it from the built-in template, `pdf` frontmatter; refuses existing targets.
- **Folders:** all six `folder.*` keys stay, always inside `data.location` (absolute or escaping values are a config error; today `Path.resolve` lets an absolute `folder.zettel` escape). Reserved first segments; duplicates are an error.
- **Performance:** no cache; notes read on virtual threads; one `task export -DELETED` (274 tasks today) for `graph`/`check`; 300 ms budget on 1,000 notes in the smoke test.

## Tasks

Each task is one complete piece of functionality and ends with a commit.

1. **Folder configuration.** The six keys with defaults; reject absolute and escaping values, and duplicates; reserved names; `config show` lists them; unknown keys are ignored. First test: an absolute `folder.zettel` is a configuration error.
2. **`search`.** Brings in reading the knowledge base (walk, types, title, warnings). Tracer bullet: one word found in one note. Then AND across lines, phrase, snippets with line numbers, ordering, `--type`, `--project`, task of a task note, no-match exit 0, human table.
3. **`paper new`.** Built-in template, PDF copy, target from `--venue`/`--year`/title, `pdf` field, existing target exit 3 with nothing written, `-e`. Papers in `search` results carry `pdf`.
4. **`graph`.** Wikilink parsing (code skipped, `|`/`#`), resolution order, UUID links, `note`/`project` edges from the export, node ID input and its errors, hubs, `--depth`/`--type`/`--edges`, cap and `truncated`, dangling nodes, human table.
5. **`check`.** On the same resolution as `graph`; severities, exit 3, hints, `--code`/`--project`, human table.
6. **Completion.** `graph` node names (file names only, folder-qualified when repeated), task IDs, `project:`; fixed lists for `--kind`/`--type`/`--edges` and `check --code`; `--project`; comma lists.
7. **Native smoke test.** The new commands on the binary; `search`/`graph` under 300 ms on a generated 1,000-note knowledge base; completion still under 50 ms.

## Done when

- `mvn -q test` and `scripts/native-smoke.sh` pass.
- `ztk2 search`, `graph` and `check` run read-only on the real knowledge base, and `check`'s findings are reviewed with the user.
- `paper new` is tried only in a scratch knowledge base.
