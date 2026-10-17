# ztk Phase 2 (Notes) Implementation Plan

**Goal:** Read the knowledge base: `search`, `graph` and `check` over the notes and their links, plus `paper new`. Everything is read-only on existing notes; `paper new` only creates files.

**Spec:** `docs/specs/2026-10-15-ztk-design.md` §3 (folders), §4.4 (types), §4.5 (templates), §5 (node IDs, wikilinks, edges), §6.2 (Notes, Navigation, `check`), §7.2 (completion), §11. Domain terms: `docs/CONTEXT.md`.

**Out of scope:** `context`, sessions, summaries (Phase 4); goal and effort nodes and their `check` problems (Phase 3); migration and skills (Phase 5). Dropped: `link` and `new --source` (users and agents write wikilinks themselves), tags (not a ztk concept).

**Plan status:** a draft. The order below is a suggestion; classes and interfaces are decided during development, following the `tdd` and `improve-codebase-architecture` skills.

## Where we left off (2026-10-17, Task 1 done)

Phase 2's design was settled in an interview on 2026-10-17; the spec and `docs/CONTEXT.md` hold the result. Task 1 is done: the six folders live in a `Folders` record on `Configuration`, always inside `data.location`; absolute, escaping and duplicate folders are rejected; unknown keys are ignored; `config show` lists every folder. The reserved first segments move to Task 2, where the reader uses them.

**Next steps, in order:**
1. Task 2 (`search`, reading the knowledge base), TDD. Open question: reject a folder nested inside another (`notes` and `notes/papers`)? Not done; the spec only rejects the same folder.

**Working agreement:** TDD, one test at a time; Claude writes tests and snippets, the user writes production code and runs the tests. Commit once a whole command is done. Verify Æsh/Taskwarrior behavior in the scratchpad, never against real data. Real notes in `~/cerebro` are only read (surveys, final checks), never written; nothing about this work goes there.

## Decisions (summary; the spec has the details)

- **Reading:** every `.md` under `data.location`, recursively, skipping hidden directories and `AGENTS.md`/`CLAUDE.md`; legacy `papers/TEMPLATE.md` is not a paper. Types by location only. Malformed frontmatter or an unreadable file: warning, note skipped.
- **Wikilinks:** outside fenced code and inline code; target before `|`/`#`; exact file-name match. Resolution: same folder first, then all matches (ambiguous), then task UUID (full or 8-hex short), else dangling. Surveyed on real notes: skipping code removes all false positives (bash `[[ … ]]`); 15 UUID links; `summary`/`ideas` repeat per project.
- **Node IDs:** printed `task:<uuid>`, `note:<relative path without .md>`, `project:<name>`. Input also takes `task:<id|short>`, bare numbers, bare names; no file paths. Several matches exit 3 listing IDs; none exit 2.
- **`search`:** words ANDed across the note, `"phrase"`, body + `title`, no regex or fuzzy; up to 3 snippets with line numbers; `--type`, `--project`; never calls Taskwarrior.
- **`graph`:** agent-first. Edges `link` (out/in), `note`, `project`; breadth-first, depth 2; projects are hubs; filters restrict the walk; flat JSON with paths, cap 50 with `truncated`.
- **`check`:** errors `dangling-link`, `missing-note`, `task-without-project` (open tasks); warnings `orphan-task-note`, `ambiguous-link`, `unreadable-note`; hints; no `--fix`.
- **`paper new`:** PDF from anywhere, copied to `papers/<venue|others>/<year>/<slug>.pdf`, note next to it from the built-in template, `pdf` frontmatter; refuses existing targets.
- **Folders:** all six `folder.*` keys stay, always inside `data.location` (absolute or escaping values are a config error; today `Path.resolve` lets an absolute `folder.zettel` escape). Reserved first segments; duplicates are an error.
- **Performance:** no cache; notes read on virtual threads; one `task export -DELETED` (274 tasks today) for `graph`/`check`; 300 ms budget on 1,000 notes in the smoke test.

## Tasks

Each task is one complete piece of functionality and ends with a commit.

1. **Folder configuration.** The six keys with defaults; reject absolute and escaping values, and duplicates; reserved names; `config show` lists them; unknown keys are ignored. First test: an absolute `folder.zettel` is a configuration error.
2. **`search`.** Brings in reading the knowledge base (walk, types, title, warnings). Tracer bullet: one word found in one note. Then AND across lines, phrase, snippets with line numbers, ordering, `--type`, `--project`, task of a task note, no-match exit 0, human table.
3. **`paper new`.** Built-in template, PDF copy, target from `--venue`/`--year`/title, `pdf` field, existing target exit 3 with nothing written, `-e`. Papers in `search` results carry `pdf`.
4. **`graph`.** Wikilink parsing (code skipped, `|`/`#`), resolution order, UUID links, `note`/`project` edges from the export, node ID input and its errors, hubs, `--depth`/`--type`/`--edges`, cap and `truncated`, dangling nodes, human table.
5. **`check`.** On the same resolution as `graph`; severities, exit 3, hints, `--kind`/`--project`, human table.
6. **Completion.** `graph` node names (file names only, folder-qualified when repeated), task IDs, `project:`; fixed lists for `--type`/`--edges`/`--kind`; `--project`; comma lists.
7. **Native smoke test.** The new commands on the binary; `search`/`graph` under 300 ms on a generated 1,000-note knowledge base; completion still under 50 ms.

## Done when

- `mvn -q test` and `scripts/native-smoke.sh` pass.
- `ztk2 search`, `graph` and `check` run read-only on the real knowledge base, and `check`'s findings are reviewed with the user.
- `paper new` is tried only in a scratch knowledge base.
