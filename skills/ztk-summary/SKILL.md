---
name: ztk-summary
description: Use when the user asks for the weekly summary or report, or for a quarter's or year's retrospective, written from ztk's facts.
---

# ztk summary

ztk gathers a period's facts. You write the narrative from those facts only. ztk saves facts and narrative together. Never write or append to a summary file yourself.

## Weekly summary

1. Run `ztk summary --json` for the previous week, or `ztk summary <YYYY-Www> --json` for another one.
2. Read the previous week's `summaries/<YYYY-Www>.md`. A task it listed as under review that is now completed is "task fully closed".
3. Write the narrative in the format below.
   - The detail comes from each task's `sessions`: Done for completed tasks, Now and Next for tasks in progress.
   - For a task without sessions, write its description alone.
4. Show the narrative to the user and revise it until they approve.

```markdown
# Summary (October 12 - 18, 2026)

## Infinispan

**Completed**
- Conflict resolution on coordinator failover — task fully closed (merged upstream)
- awaitLeaveTransfer configuration — fixed the attribute missing from `attributeDefinitionSet()`, added it to the 16.3 XSD schema; 3 behavioral tests.

**Under Review**
- Per-node hot key tracker — PR submitted

**In Progress**
- Chunked snapshot transfer — leader side done; follower install next.
```

Formatting rules:

- One `##` per project. Use **Completed**, **Under Review** and **In Progress** only when they have content.
- Use bullets only, never numbers.
- Each bullet is the task title, a dash, then the technical detail: what was built, design decisions, bugs found, test counts, measurements. Never repeat only the title.
- Put no task IDs in titles, and no emojis.

## Quarter or year retrospective

1. Run `ztk summary <YYYY-Qq|YYYY> --json`.
   - If `needsClosing` lists goals, the user closes them first, and settles each carried-over effort (moved on, done or dropped).
   - The narrative explains a goal's status. It never sets one.
2. For each goal, read:
   - its note, for what it aimed for;
   - its efforts' notes, for `## Outcome` and Decisions;
   - its tasks' `sessions` in the JSON.

   A period that starts before ztk also reads the **Progress log** (`progress*.md` at the root) for its dates.
3. Interview the user one goal at a time, asking why it got its verdict. The reasoning is theirs: ask, don't infer.
4. Write the narrative in short paragraphs, never bullets:

```markdown
# Retrospective (2026-Q4)

## 2026-Q4-G1 · <title> (reached)
What it aimed for, what happened, why the verdict, how each carried-over effort was settled.

## General work
One paragraph on the work outside the plan.

## What didn't work
Approaches, estimates and habits that failed, across goals.

## Looking ahead
What the next planning should take from this.
```

A year has one section per yearly goal, citing its quarterly goals' verdicts.

## Save

Once the user approves, save with `ztk summary <period> --write --narrative -`, the narrative on stdin:

```bash
ztk summary 2026-W42 --write --narrative - <<'EOF'
# Summary (October 12 - 18, 2026)
…
EOF
```

It refuses (exit 3) in three cases. Tell the user what it printed. Don't work around it.

- The period has not ended.
- A goal still needs closing.
- The file already exists. The user edits or deletes it.
