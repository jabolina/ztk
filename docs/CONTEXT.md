# ztk

A Zettelkasten and GTD task tool: Markdown notes in a knowledge base, tasks in Taskwarrior, and the links between them.

## Language

### Notes

**Knowledge base**:
The directory tree under `data.location` holding every note.
_Avoid_: cerebro (the user's instance, not the concept)

**Note**:
A Markdown file in the knowledge base, typed by its location.

**Wikilink**:
A `[[target]]` outside fenced code and inline code, naming a note by its file name without `.md`; text after `|` or `#` is ignored.
_Avoid_: link (alone, when it could mean another edge)

**Task**:
A Taskwarrior task in any status; ztk requires it to have a project.

**Task note**:
The note of one task, linked by the task's `note` attribute (or `task_uuid` frontmatter in older notes).

**Zettel**:
An atomic note explaining one concept, linking to the papers, zettels or tasks it relates to.
_Avoid_: knowledge note

**Paper**:
A reading note about one academic paper, kept next to its PDF.
_Avoid_: paper note, reading note

**Structure note**:
A zettel whose purpose is linking the notes of one topic; the way to group knowledge, by convention only.
_Avoid_: tag, index note, MOC

**Dangling link**:
A wikilink whose target matches no note.
_Avoid_: dead link

### Planning

**Yearly goal**:
An outcome to reach within one calendar year; a year has several, numbered in order: `2026-G1`, `2026-G2`. A number is never reused.
_Avoid_: objective, OKR

**Quarterly goal**:
An achievable step toward one **Yearly goal**, reached within one calendar quarter; numbered in order within the quarter: `2026-Q4-G1`. A number is never reused.
_Avoid_: milestone, key result

**Effort**:
A group of **Tasks** that together work toward one **Quarterly goal**, which it always has; its quarter is its goal's. Its tasks need not depend on each other and may span projects. Named by a slug unique across the knowledge base: `infinijepsen-demo`.
_Avoid_: epic, initiative

**Goal status**:
Where a goal stands, set by the user: `open`, `reached`, `missed` (tried, not made) or `dropped` (no longer matters). An `open` goal whose period has ended **needs closing**.
_Avoid_: state, progress

**Effort status**:
Where an effort stands, set by the user, never derived from its tasks: `planned`, `active`, `done` or `dropped`.
_Avoid_: state, progress

**Carried over**:
An effort neither `done` nor `dropped` whose goal's quarter has ended; it moves on by pointing it at a goal of a later quarter, or by closing it.
_Avoid_: stale, overdue

**Material**:
Files related to one goal, effort or task (drafts, PDFs, logs, thread dumps), kept in a folder of their own beside it; to ztk, its Markdown files are plain notes. Goals sit loose in `goals/<YYYY>/` and `goals/<YYYY>/Q<q>/`, efforts loose in `efforts/`; any other folder there is material.
_Avoid_: attachments, assets

**General work**:
**Tasks** outside any **Effort**: day-to-day work such as bug fixes, customer cases and releases. Normal, never flagged. A small group of it is a note linking its tasks, not an **Effort**.
_Avoid_: unplanned work, misc

### Sessions and summaries

**Session**:
A short record of one work session on a task (what was done, where things are, what is next), written by an agent at the session's end as a refresher for the next agent.
_Avoid_: progress entry

**Journal**:
The daily files holding sessions, one file per day.
_Avoid_: progress file

**Summary**:
A report for a week, quarter or year: facts gathered by ztk, optionally expanded into a narrative by an agent, and saved as a file.
_Avoid_: weekly status, report file

### Graph

**Node**:
A task, note, project, goal or effort as one point of the graph.

**Kind**:
What a node is: a task, a note, a project, a goal, an effort, or a **Dangling link**'s target; the prefix of its node ID (`dangling:<target>` for the last). A goal's or effort's own file is that goal or effort node, not a note node. A note's **type** (zettel, paper, task note…) is a separate thing.
_Avoid_: entity, node type

**Node ID**:
The kind-prefixed name of a node, printed as `task:<uuid>`, `note:<path without .md>`, `project:<name>`, `goal:<YYYY-Gn|YYYY-Qq-Gn>` or `effort:<slug>`; as input the prefix is optional for tasks (a number), notes and efforts (a name) and goals (their ID).
_Avoid_: reference, handle

**Ambiguous link**:
A wikilink with no match in its own folder and several matches elsewhere.

**Orphan task note**:
A **Task note** whose `task_uuid` names no task: the task was deleted, or the UUID is wrong.

### Checking

**Problem**:
Something wrong in the knowledge base that `check` reports, with what to do about it; each has a code (`dangling-link`, `missing-note`…) and a **Severity**. Users and agents fix problems themselves.
_Avoid_: issue (a tracker's), error (one of the severities)

**Severity**:
How much a **Problem** matters: an error means something is broken and fails `check`; a warning means it works, but likely not as meant.

## Relationships

- A **Paper** yields zero or more **Zettels**, each linking back to it; a **Task note** links to the **Zettels** it applies
- A **Yearly goal** has zero or more **Quarterly goals**, each with exactly one **Yearly goal**; a **Quarterly goal** has zero or more **Efforts**, each with exactly one **Quarterly goal**
- A **Task** belongs to at most one **Effort**; without one it is **General work**
- A **Note** contains zero or more **Wikilinks**
- An open **Task** has a project and, when its `note` attribute is set, a **Task note** there; every **Task note** has its **Task** unless it is an **Orphan task note**
- A **Wikilink** resolves to the **Note** of that name in the linking note's own folder if there is one, else to every **Note** of that name (an **Ambiguous link** when more than one); failing that, a target shaped as a task UUID (full or 8-hex short) resolves to that **Task**; otherwise it is a **Dangling link**

## Example dialogue

> **Dev:** "This note has `[[ \"$CMDLINE\" == x ]]` in a bash block. Is that a **Wikilink**?"
> **Domain expert:** "No, nothing inside code counts. Outside code, a **Wikilink** to a missing note is kept as a **Dangling link**, and `check` reports it."

## Flagged ambiguities

- "progress" (`progress*.md`) and "weekly status" (`Weekly-Status-*.md`) are the bash tool's forms of **Session** and **Summary**; the new terms replace them.
- "tags" appeared in the design as a way to group notes; resolved: not a ztk concept. Projects and efforts group work, **Structure notes** group knowledge.
- "vault" is used in the design for the code that reads notes; the domain term is **Knowledge base**.
