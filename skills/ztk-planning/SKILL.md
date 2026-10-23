---
name: ztk-planning
description: Use when the user wants to plan yearly or quarterly goals, efforts toward them, or the tasks of an effort with ztk, or to change or close a goal or effort.
---

# ztk planning

A plan has three levels:

- **Yearly goals**: big and open-ended, agreed with the user's manager.
- **Quarterly goals**: achievable and checkable, each moving a yearly goal forward.
- **Efforts**: the work toward a quarterly goal. Tasks join an effort.

The user decides. You interview, propose and write. Never create, change or close a goal or effort without the user's explicit agreement to that exact one.

## Gather context

Read before proposing anything:

- `ztk goal list <YYYY|YYYY-Qq>` and `ztk effort list` for the plan as it stands;
- the last quarter's retrospective, in `summaries/<YYYY-Qq>.md`.

For a yearly goal, start from what the user agreed with their manager.

## Goals

1. Discuss one goal at a time. Ask one question at a time, each with your recommended answer, until the goal is clear: what it is, why it matters, when it is done, and what it leaves out.
2. Once the user agrees, create it:
   - yearly: `ztk goal new <YYYY> --title "<title>"`;
   - quarterly: `ztk goal new <YYYY-Qq> --title "<title>" --parent <YYYY-Gn>`.
3. Write every section of the printed note from the discussion. Replace each italic guidance line with the content.

## Efforts

1. Propose efforts for a quarterly goal one at a time.
2. Once the user agrees, run `ztk effort new <slug> --title "<title>" --goal <YYYY-Qq-Gn>`. The slug is lowercase `[a-z0-9-]`.
3. Write the note's Context, Done when, Approach, and Risks and open questions.
   - Decisions is a dated log: `2026-11-02: chose X over Y, because …`.
   - Outcome stays empty until the effort closes.

## Tasks

Break an effort into tasks only after its note is agreed.

- New task: `ztk add <description> project:<Project> --effort <slug>`.
- Existing tasks: `ztk effort assign <slug> <task…>`.
- Use the `to-ztk` skill for a breakdown into many tasks.

## Change or close

- `ztk goal set <goal> --status <open|reached|missed|dropped>`
- `ztk effort set <slug> --status <planned|active|done|dropped>`
- `--title` renames either one. `--goal` moves an effort to a later quarter's goal, carrying it over.

Before closing an effort, write its `## Outcome`: what was delivered, its impact, and links to the evidence.

When ztk refuses a write (exit 3), it lists each problem with its fix. Show the user and settle them together. For example, open work under a goal being closed has to move on or close first.
