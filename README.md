# ztk

A Zettelkasten and GTD task tool for the terminal, built for humans and AI coding agents alike.

ztk keeps **notes** as plain Markdown, **tasks** in [Taskwarrior](https://taskwarrior.org), and connects them: tasks to
their notes, notes to the papers and ideas they cite, and work to the goals it serves.

- **Plain files, no database.** Taskwarrior stores the tasks; everything else is Markdown you can read, grep and edit.
- **Goals to tasks.** Yearly goals break into quarterly goals, quarterly goals into efforts, efforts into tasks.
- **Knowledge next to work.** Zettels, paper notes and task notes live in one knowledge base, linked with `[[wikilinks]]`.
- **Agent-friendly.** Stable JSON output, and [skills](skills) that teach coding agents to use it.
- **Facts, not prose.** Weekly, quarterly and yearly summaries come from what was recorded. ztk never calls an LLM.
- **Native binary.** Java 25 compiled with GraalVM, so it starts instantly.

## Install

On Linux or macOS:

```sh
curl -fsSL https://raw.githubusercontent.com/jabolina/ztk/main/scripts/install.sh | sh
```

It installs the latest release into `~/.ztk`, with shell completion. Run it again to upgrade. ztk needs
[Taskwarrior](https://taskwarrior.org) 3.

## Documentation

The [manual](docs/manual/manual.adoc) has tutorials, how-to guides, reference and explanation.
Build it as HTML with `mvn prepare-package -Pmanual`, into `target/doc/manual/manual.html`.

## Build

```sh
mvn package -Pnative   # produces target/ztk
```

Requires GraalVM for Java 25.
