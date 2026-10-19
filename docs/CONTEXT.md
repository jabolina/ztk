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
A task, note or project as one point of the graph.

**Kind**:
What a node is: a task, a note, a project, or a **Dangling link**'s target; the prefix of its node ID (`dangling:<target>` for the last). A note's **type** (zettel, paper, task note…) is a separate thing.
_Avoid_: entity, node type

**Node ID**:
The kind-prefixed name of a node, printed as `task:<uuid>`, `note:<path without .md>` or `project:<name>`; as input the prefix is optional for tasks (a number) and notes (a name).
_Avoid_: reference, handle

**Ambiguous link**:
A wikilink with no match in its own folder and several matches elsewhere.

## Relationships

- A **Paper** yields zero or more **Zettels**, each linking back to it; a **Task note** links to the **Zettels** it applies
- A **Note** contains zero or more **Wikilinks**
- A **Wikilink** resolves to the **Note** of that name in the linking note's own folder if there is one, else to every **Note** of that name (an **Ambiguous link** when more than one); failing that, a target shaped as a task UUID (full or 8-hex short) resolves to that **Task**; otherwise it is a **Dangling link**

## Example dialogue

> **Dev:** "This note has `[[ \"$CMDLINE\" == x ]]` in a bash block. Is that a **Wikilink**?"
> **Domain expert:** "No, nothing inside code counts. Outside code, a **Wikilink** to a missing note is kept as a **Dangling link**, and `check` reports it."

## Flagged ambiguities

- "progress" (`progress*.md`) and "weekly status" (`Weekly-Status-*.md`) are the bash tool's forms of **Session** and **Summary**; the new terms replace them.
- "tags" appeared in the design as a way to group notes; resolved: not a ztk concept. Projects and efforts group work, **Structure notes** group knowledge.
- "vault" is used in the design for the code that reads notes; the domain term is **Knowledge base**.
