# AGENTS.md

This is a second brain built on Getting Things Done and Zettelkasten. Tasks live in Taskwarrior and notes as Markdown, both reached through `ztk` (see the `ztk` skill). The user edits notes in Neovim with Telekasten.

Your goal is always to investigate, brainstorm and discuss. Write code only when asked directly. Snippets that illustrate a point are fine.

## Starting

Run `ztk info <task>` for the task you are given, or `ztk current` otherwise. Read its sessions and its note. For work from before ztk, the history is in the **Progress log**: the `progress*.md` files at the root. Read them only when you need that history, and never write to them.

## Forbidden

In a code project with the user, never:

- modify files without discussion and an **explicit** request;
- compile or run tests. Ask the user to run them and report back;
- run git operations that check out files, undo changes or switch branches;
- use `var` in Java;
- read large source files whole. Search with `graphify` or `grep` and read only the relevant lines.

In the knowledge base, never:

- run ztk with `--editor` or `-e`;
- run `task` directly, for reads too: ztk reads and writes the tasks;
- edit `journal/` or `summaries/`.

## Layout

- `<Project>/` holds task notes, named `YYYYMMDD-<slug>.md` with no spaces. Each project has:
  - `summary.md`: the project and its remote repository;
  - `ideas.md`: ideas from brainstorming;
  - `adr/`: architectural decisions.

  Some projects are related.
- `zettel/` holds standalone knowledge notes, one idea each.
- `papers/<venue>/<year>/` holds each paper's PDF next to its note.
- `goals/` and `efforts/` hold the plan (see the `ztk-planning` skill).
- `journal/YYYY-MM-DD.md` holds the work sessions, written only by `ztk session add`.
- `summaries/` holds the weekly summaries (`YYYY-Www.md`) and the quarter and year retrospectives (see the `ztk-summary` skill).
- `progress*.md` is the Progress log, the history from before ztk.

`GH-<NUMBER>` names a GitHub issue in the project's repository, which its `summary.md` gives. For example, GH-17015 in Infinispan is https://github.com/infinispan/infinispan/issues/17015.

## Brainstorming

After a brainstorming session:

1. Write the date and a summary of the discussion into the task's note, or into the project's `ideas.md` when no task applies.
2. If the session was on a task, record it with `ztk session add`.
