---
name: ztk
description: Use for the user's tasks and notes through the ztk CLI (Taskwarrior plus a Zettelkasten knowledge base in ~/cerebro) — resuming a task, reading or updating its note, recording a work session, adding a task, looking up notes, or reading a paper.
---

# ztk

ztk keeps tasks in Taskwarrior and notes as Markdown files in the knowledge base. Piped output is JSON. ztk gives you paths. You read, grep and edit the files with your own tools.

## Resume a task

1. Run `ztk info <task>` for the task the user names, or `ztk current` when they name none. It gives the task, its effort, its note path (`note`) and its last three sessions, newest first.
2. Read the sessions, then the note.
3. If the task has an effort, read the effort's note (`ztk effort info <slug>` gives its path). Its Approach and Decisions say how the work is meant to go.

No sessions on a task worked on before may mean its history predates ztk. Look in the **Progress log**: the `## YYYY-MM-DD:` entries in `progress.md` at the knowledge base's root (older ones in `progress-YYYY-Qq.md`). Search them for the task's name. Never write to them.

## Notes

- A task's note is the `note` path printed by `ztk info <task>`, `ztk current`, `ztk add` and `ztk start`.
- Keep the note current: replace what is outdated instead of only appending, and keep what a later session needs.
- Link with `[[wikilinks]]` by file name without `.md`, for example `[[20260204-causality]]` or `[[20260204-causality|causality]]`.
- `ztk new "<title>"` creates a zettel (one atomic idea, in `zettel/`) and prints its `path`.
- Never edit `journal/` or `summaries/`: ztk writes them.
- Never edit the frontmatter of goals and efforts. Their commands change it.

## Record a session

At the end of a session, run `ztk session add <task> --title "<one line>" --done "…" --now "…" --next "…"`.

- **Done:** what was done.
- **Now:** where things stand.
- **Next:** what comes next.

Each part is a few lines. Details belong in the task note. Any part can be `-` to read it from stdin.

## Look things up

- `ztk search <words…>` finds notes containing every word. Narrow it with `--type zettel,paper` or `--project <Project>`.
- `ztk graph <node>` lists what is linked around a node. A node is a note name, a task ID, `project:<Project>`, a goal ID or an effort slug. Narrow it with `--depth`, `--kind` and `--limit`.
- `ztk list [project]` lists pending tasks by urgency. `ztk week [project]` lists the week's tasks.
- `ztk check` reports broken links and plan problems, each with the fix in its hint.
- `ztk goal list` and `ztk effort list` show the plan. `ztk goal info <goal>` and `ztk effort info <slug>` show one goal or effort.
- `ztk summary [period]` shows a week's, quarter's or year's facts.

## What you may run

| On your own | Only when the user says so | Never |
|---|---|---|
| Every read above | `ztk start <task>`, `ztk stop` | `--editor` or `-e` on any command |
| `ztk add <description> project:<Project>`, then tell the user which task you created | `ztk done <task>`, `ztk review <task>` | Task writes through `task` directly |
| `ztk session add`, `ztk new`, `ztk paper new` | | |
| Edits to notes with your own tools | | |

Goal and effort writes (`ztk goal new`, `ztk goal set`, `ztk effort new`, `ztk effort set`, `ztk effort assign`, `ztk effort unassign`) belong to the `ztk-planning` skill. `ztk summary --write` belongs to `ztk-summary`.

## Read a paper

1. `ztk paper new <pdf> --title "<title>" --venue <venue> --year <year> --authors "<authors>"` files the PDF and creates its note. It prints the note's `path` and the `pdf` path.
2. Read the PDF and write the summary into the note's sections.
3. Pull out each idea worth keeping as a zettel with `ztk new`, linking back to the paper's note.
4. Link those zettels from the task notes that apply them.
