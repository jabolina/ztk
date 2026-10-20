package io.github.jabolina.ztk.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jabolina.ztk.CliHarness;
import io.github.jabolina.ztk.CliHarness.Result;
import io.github.jabolina.ztk.TestTaskwarrior;
import io.github.jabolina.ztk.ZtkException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Searching the notes of the knowledge base from the command line.
 *
 * <p>
 * Notes are written straight into the fixture's knowledge base, as users and agents write them, rather than through
 * ztk: search reads whatever is there.
 * </p>
 */
class SearchCommandTest {

   private static final JsonMapper JSON = JsonMapper.shared();

   @TempDir
   Path root;

   TestTaskwarrior taskwarrior;

   @BeforeEach
   void setUp() {
      taskwarrior = TestTaskwarrior.create(root);
   }

   @Test
   void searchFindsTheNoteContainingTheWord() throws IOException {
      Path causality = note("zettel/20261015-causality-and-time.md", """
            ---
            title: Causality and Time
            date: 2026-10-15T09:30:00+02:00
            ---

            Lamport clocks order events by causality.
            """);
      note("zettel/20261016-consensus.md", """
            ---
            title: Consensus
            date: 2026-10-16T09:30:00+02:00
            ---

            Paxos needs a majority of acceptors.
            """);

      // Lower case on purpose: matching ignores case.
      Result result = CliHarness.run(taskwarrior.environment(), false, "search", "lamport");

      assertThat(result.exitCode()).isZero();
      JsonNode json = JSON.readTree(result.out());
      assertThat(json.path("schema").asInt()).isEqualTo(1);
      JsonNode results = json.path("results");
      assertThat(results).hasSize(1);
      JsonNode found = results.get(0);
      assertThat(found.path("id").asString()).isEqualTo("note:zettel/20261015-causality-and-time");
      assertThat(found.path("path").asString()).isEqualTo(causality.toString());
      assertThat(found.path("type").asString()).isEqualTo("zettel");
      assertThat(found.path("title").asString()).isEqualTo("Causality and Time");
   }

   @Test
   void searchShowsTheMatchingLinesWithTheirLineNumbers() throws IOException {
      note("zettel/20261015-clocks.md", """
            ---
            title: Clocks
            date: 2026-10-15T09:30:00+02:00
            ---

            Lamport clocks order events.

            Paxos numbers its ballots with them.
            """);
      // Every word is here, but not the phrase: "lamport clocks" is split across two lines.
      note("zettel/20261016-history.md", """
            ---
            title: History
            date: 2026-10-16T09:30:00+02:00
            ---

            Lamport
            clocks came before Paxos.
            """);

      // A quoted argument is a phrase; separate arguments may be on different lines.
      Result result = CliHarness.run(taskwarrior.environment(), false, "search", "lamport clocks", "paxos");

      assertThat(result.exitCode()).isZero();
      JsonNode results = JSON.readTree(result.out()).path("results");
      assertThat(results).hasSize(1);
      JsonNode found = results.get(0);
      assertThat(found.path("id").asString()).isEqualTo("note:zettel/20261015-clocks");
      // Line numbers count the frontmatter, so they point into the file, e.g. for nvim +6.
      JsonNode lines = found.path("lines");
      assertThat(lines).hasSize(2);
      assertThat(lines.get(0).path("line").asInt()).isEqualTo(6);
      assertThat(lines.get(0).path("text").asString()).isEqualTo("Lamport clocks order events.");
      assertThat(lines.get(1).path("line").asInt()).isEqualTo(8);
      assertThat(lines.get(1).path("text").asString()).isEqualTo("Paxos numbers its ballots with them.");
   }

   @Test
   void searchListsTitleMatchesFirstThenMostMatchingLinesThenMostRecentlyModified() throws IOException {
      // Written in an order different from the expected one, with the modification times set explicitly.
      Path olderOneLine = note("zettel/older-one-line.md", """
            ---
            title: Older
            ---

            Paxos is mentioned once.
            """);
      Path twoLines = note("zettel/two-lines.md", """
            ---
            title: Consensus
            ---

            Paxos has two phases.
            Multi-Paxos skips the first one.
            """);
      Path titleMatch = note("zettel/title-match.md", """
            ---
            title: Paxos
            ---

            Mentioned once in the body too: paxos.
            """);
      Path newerOneLine = note("zettel/newer-one-line.md", """
            ---
            title: Newer
            ---

            Paxos is mentioned once.
            """);
      modified(titleMatch, "2026-10-01T00:00:00Z");
      modified(twoLines, "2026-10-02T00:00:00Z");
      modified(olderOneLine, "2026-10-03T00:00:00Z");
      modified(newerOneLine, "2026-10-04T00:00:00Z");

      Result result = CliHarness.run(taskwarrior.environment(), false, "search", "paxos");

      assertThat(result.exitCode()).isZero();
      // The title match wins although it is the oldest; then two lines beat one; then the newer of the two.
      assertThat(JSON.readTree(result.out()).path("results"))
            .map(found -> found.path("id").asString())
            .containsExactly("note:zettel/title-match", "note:zettel/two-lines", "note:zettel/newer-one-line",
                  "note:zettel/older-one-line");
   }

   @Test
   void typeRestrictsTheResultsToTheListedNoteTypes() throws IOException {
      note("zettel/20261015-raft.md", "Raft elects a leader.\n");
      note("papers/others/2014/in-search-of-an-understandable-consensus-algorithm.md", "Raft elects a leader.\n");
      // A root-level note is of type other.
      note("raft-reading-list.md", "Raft elects a leader.\n");

      Result result = CliHarness.run(taskwarrior.environment(), false, "search", "raft", "--type", "zettel,paper");

      assertThat(result.exitCode()).isZero();
      assertThat(JSON.readTree(result.out()).path("results"))
            .map(found -> found.path("type").asString() + " " + found.path("id").asString())
            .containsExactlyInAnyOrder(
                  "zettel note:zettel/20261015-raft",
                  "paper note:papers/others/2014/in-search-of-an-understandable-consensus-algorithm");
   }

   @Test
   void unknownTypeIsAUsageErrorListingTheTypes() {
      Result result = CliHarness.run(taskwarrior.environment(), false, "search", "raft", "--type", "zettel,zetel");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.USAGE.exitCode());
      assertThat(result.out()).isEmpty();
      // Names the wrong value and lists the valid ones, so the typo is easy to fix.
      // Types are added over time, so only check the list is there, not its full content.
      assertThat(result.err()).contains("zetel", "--type", "Valid types:", "paper", "other");
   }

   @Test
   void projectRestrictsTheResultsToThatProjectFolder() throws IOException {
      note("Alpha/20261015-elect-a-leader.md", "Raft elects a leader.\n");
      // Subfolders of a project folder belong to the project too.
      note("Alpha/adr/0001-use-raft.md", "Raft elects a leader.\n");
      note("Beta/20261015-replace-raft.md", "Raft elects a leader.\n");
      note("zettel/20261015-raft.md", "Raft elects a leader.\n");

      Result result = CliHarness.run(taskwarrior.environment(), false, "search", "raft", "--project", "Alpha");

      assertThat(result.exitCode()).isZero();
      assertThat(JSON.readTree(result.out()).path("results"))
            .map(found -> found.path("id").asString())
            .containsExactlyInAnyOrder("note:Alpha/20261015-elect-a-leader", "note:Alpha/adr/0001-use-raft");
   }

   @Test
   void notesInAProjectFolderAreTaskNotesWhenTheyNameATask() throws IOException {
      // The frontmatter ztk writes for a task note; search reads it without asking Taskwarrior.
      note("Alpha/20261015-elect-a-leader.md", """
            ---
            title: Elect a leader
            date: 2026-10-15T09:30:00+02:00
            task_uuid: 0b5e8a52-5c1f-4c43-9d7a-3f1d2b6a9e10
            project: Alpha
            ---

            Raft elects a leader.
            """);
      note("Alpha/ideas.md", "Raft elects a leader.\n");

      Result result = CliHarness.run(taskwarrior.environment(), false, "search", "raft");

      assertThat(result.exitCode()).isZero();
      assertThat(JSON.readTree(result.out()).path("results"))
            .map(found -> found.path("id").asString() + " " + found.path("type").asString() + " "
                  + found.path("task").asString("-"))
            .containsExactlyInAnyOrder(
                  "note:Alpha/20261015-elect-a-leader task 0b5e8a52-5c1f-4c43-9d7a-3f1d2b6a9e10",
                  // Not a task note: no task field at all.
                  "note:Alpha/ideas project -");
   }

   @Test
   void configuredFoldersAreNeverProjectFolders() throws IOException {
      // Nested: "notes" is then reserved too, although only notes/zettel holds zettels.
      Files.writeString(taskwarrior.ztkrc(), "folder.zettel=notes/zettel\n", StandardOpenOption.APPEND);
      note("goals/2026.md", "Raft everywhere.\n");
      note("efforts/consensus.md", "Raft everywhere.\n");
      note("journal/2026-10-17.md", "Raft everywhere.\n");
      note("summaries/2026-W42.md", "Raft everywhere.\n");
      note("notes/scratch.md", "Raft everywhere.\n");
      note("Alpha/ideas.md", "Raft everywhere.\n");

      Result result = CliHarness.run(taskwarrior.environment(), false, "search", "raft");

      assertThat(result.exitCode()).isZero();
      // Typed by their own folder's rules, or not at all, but never mistaken for projects named "goals" or "notes".
      assertThat(JSON.readTree(result.out()).path("results"))
            .map(found -> found.path("id").asString() + " " + found.path("type").asString())
            .containsExactlyInAnyOrder(
                  "note:goals/2026 other",
                  "effort:consensus effort",
                  "note:journal/2026-10-17 other",
                  "note:summaries/2026-W42 other",
                  "note:notes/scratch other",
                  "note:Alpha/ideas project");
   }

   @Test
   void aConfiguredFolderIsNotAProject() throws IOException {
      note("goals/2026.md", "Raft everywhere.\n");

      Result result = CliHarness.run(taskwarrior.environment(), false, "search", "raft", "--project", "goals");

      // Same answer as any project without notes: nothing found.
      assertThat(result.exitCode()).isZero();
      assertThat(JSON.readTree(result.out()).path("results")).isEmpty();
   }

   @Test
   void hiddenFoldersAreNeverRead() throws IOException {
      // Tool state, not knowledge: a git repository, an editor's workspace, a hidden folder inside a project.
      note(".git/raft.md", "Raft everywhere.\n");
      note(".obsidian/workspace.md", "Raft everywhere.\n");
      note("Alpha/.drafts/raft.md", "Raft everywhere.\n");
      note("Alpha/ideas.md", "Raft everywhere.\n");

      Result result = CliHarness.run(taskwarrior.environment(), false, "search", "raft");

      assertThat(result.exitCode()).isZero();
      assertThat(JSON.readTree(result.out()).path("results"))
            .map(found -> found.path("id").asString())
            .containsExactly("note:Alpha/ideas");
   }

   @Test
   void agentInstructionsAreNeverNotes() throws IOException {
      // At the root and inside a project: instructions for agents, not knowledge.
      note("AGENTS.md", "Search with ztk, never with grep.\n");
      note("Alpha/CLAUDE.md", "Search with ztk, never with grep.\n");
      note("Alpha/ideas.md", "Search with ztk, never with grep.\n");

      Result result = CliHarness.run(taskwarrior.environment(), false, "search", "grep");

      assertThat(result.exitCode()).isZero();
      assertThat(JSON.readTree(result.out()).path("results"))
            .map(found -> found.path("id").asString())
            .containsExactly("note:Alpha/ideas");
   }

   @Test
   void aTitleWithAnUnquotedColonIsStillRead() throws IOException {
      // As the bash script wrote it: not valid YAML, since the second ": " reads as a nested mapping.
      note("Alpha/20261015-ispn-1234-fix-the-merge.md", """
            ---
            title: ISPN-1234: Fix the merge
            date: Tue May 12 03:14:15 PM -03 2026
            task_uuid: 0b5e8a52-5c1f-4c43-9d7a-3f1d2b6a9e10
            project: Alpha
            ---

            Raft elects a leader.
            """);

      Result result = CliHarness.run(taskwarrior.environment(), false, "search", "raft");

      assertThat(result.exitCode()).isZero();
      JsonNode results = JSON.readTree(result.out()).path("results");
      assertThat(results).hasSize(1);
      JsonNode found = results.get(0);
      assertThat(found.path("title").asString()).isEqualTo("ISPN-1234: Fix the merge");
      assertThat(found.path("type").asString()).isEqualTo("task");
      assertThat(found.path("task").asString()).isEqualTo("0b5e8a52-5c1f-4c43-9d7a-3f1d2b6a9e10");
   }

   @Test
   void aNoteThatIsNotUtf8IsSkippedWithAWarning() throws IOException {
      // "Raft elects a leader in São Paulo." saved as Latin-1: the "ã" is a byte UTF-8 rejects.
      Path latin1 = taskwarrior.dataLocation().resolve("zettel/20261015-latin1.md");
      Files.createDirectories(latin1.getParent());
      Files.write(latin1, "Raft elects a leader in São Paulo.\n".getBytes(StandardCharsets.ISO_8859_1));
      note("zettel/20261016-raft.md", "Raft elects a leader.\n");

      Result result = CliHarness.run(taskwarrior.environment(), false, "search", "raft");

      // One unreadable note does not fail the search: the others are still found.
      assertThat(result.exitCode()).isZero();
      JsonNode json = JSON.readTree(result.out());
      assertThat(json.path("results"))
            .map(found -> found.path("id").asString())
            .containsExactly("note:zettel/20261016-raft");
      // Agents read the warning next to the results, with the file to fix and how.
      JsonNode warnings = json.path("warnings");
      assertThat(warnings).hasSize(1);
      assertThat(warnings.get(0).path("path").asString()).isEqualTo(latin1.toString());
      assertThat(warnings.get(0).path("message").asString()).contains("UTF-8", "skipped");
      assertThat(warnings.get(0).path("hint").asString()).contains("iconv");
   }

   @Test
   void onATerminalResultsAreATableWithTheMatchingLinesUnderEachNote() throws IOException {
      // Ranked first: the title matches.
      note("zettel/20261015-raft.md", """
            ---
            title: Raft
            ---

            Raft elects a leader.
            Raft replicates a log.
            """);
      // No frontmatter: the title is the file name.
      note("Alpha/20261016-ideas.md", "Try raft for the store.\n");

      Result result = CliHarness.run(taskwarrior.environment(), true, "search", "raft");

      assertThat(result.exitCode()).isZero();
      Path data = taskwarrior.dataLocation();
      // Columns padded to their widest cell; matching lines aligned with the title, as "line: text".
      assertThat(result.out()).isEqualTo("""
            TYPE     TITLE           PATH
            zettel   Raft            %s
                     5: Raft elects a leader.
                     6: Raft replicates a log.
            project  20261016-ideas  %s
                     1: Try raft for the store.
            """.formatted(data.resolve("zettel/20261015-raft.md"), data.resolve("Alpha/20261016-ideas.md")));
   }

   @Test
   void everyTermMustBeInTheSameNote() throws IOException {
      // Each term is in some note, but no note has both: whatever the order the notes are read in, nothing matches.
      note("zettel/20261015-raft.md", "Raft elects a leader.\n");
      note("zettel/20261016-paxos.md", "Paxos needs a majority.\n");

      Result result = CliHarness.run(taskwarrior.environment(), false, "search", "raft", "paxos");

      assertThat(result.exitCode()).isZero();
      assertThat(JSON.readTree(result.out()).path("results")).isEmpty();
   }

   @Test
   void paperResultsCarryTheirPdfResolvedNextToTheNote() throws IOException {
      Path filed = note("papers/ATC/2014/raft.md", """
            ---
            title: In Search of an Understandable Consensus Algorithm
            pdf: raft.pdf
            ---

            Raft elects a leader.
            """);
      // Filed by hand before ztk, with no pdf field.
      Path older = note("papers/others/paxos.md", "Raft and Paxos.\n");
      Path zettel = note("zettel/20261015-raft.md", "Raft elects a leader.\n");

      Result result = CliHarness.run(taskwarrior.environment(), false, "search", "raft");

      assertThat(result.exitCode()).isZero();
      Map<String, JsonNode> byPath = JSON.readTree(result.out()).path("results").valueStream()
            .collect(Collectors.toMap(match -> match.path("path").asString(), match -> match));
      // Absolute, like the note's own path, so it can be opened from anywhere.
      assertThat(byPath.get(filed.toString()).path("pdf").asString())
            .isEqualTo(filed.resolveSibling("raft.pdf").toString());
      // A paper without one still has the key, so every paper result reads the same.
      JsonNode withoutPdf = byPath.get(older.toString());
      assertThat(withoutPdf.has("pdf")).isTrue();
      assertThat(withoutPdf.path("pdf").isNull()).isTrue();
      // Only papers have a PDF.
      assertThat(byPath.get(zettel.toString()).has("pdf")).isFalse();
   }

   @Test
   void searchNamesGoalsByTheirGoalIdAndReadsEverythingElseUnderGoalsAsOther() throws IOException {
      String content = """
            ---
            title: Replication
            ---

            Replication must survive partitions.
            """;
      Path yearly = note("goals/2026/2026-G1-provably-correct.md", content);
      Path quarterly = note("goals/2026/Q4/2026-Q4-G1-fix-bugs.md", content);
      // Material: any folder inside a level, even holding a file named like a goal.
      Path material = note("goals/2026/kickoff/notes.md", content);
      Path materialNamedLikeAGoal = note("goals/2026/kickoff/2026-G5-draft.md", content);
      // Directly in a level, but not named after a goal ID of that level.
      Path unnamed = note("goals/2026/draft.md", content);
      Path wrongLevel = note("goals/2026/Q4/2026-G2-misplaced.md", content);
      Path wrongYear = note("goals/2026/2025-G3-last-year.md", content);
      Path outsideLevels = note("goals/README.md", content);

      Result result = CliHarness.run(taskwarrior.environment(), false, "search", "replication");

      assertThat(result.exitCode()).isZero();
      Map<String, JsonNode> byPath = byPath(JSON.readTree(result.out()).path("results"));
      assertThat(byPath).hasSize(8);
      assertThat(byPath.get(yearly.toString()).path("id").asString()).isEqualTo("goal:2026-G1");
      assertThat(byPath.get(yearly.toString()).path("type").asString()).isEqualTo("goal");
      assertThat(byPath.get(quarterly.toString()).path("id").asString()).isEqualTo("goal:2026-Q4-G1");
      assertThat(byPath.get(quarterly.toString()).path("type").asString()).isEqualTo("goal");
      for (Path other : new Path[] { material, materialNamedLikeAGoal, unnamed, wrongLevel, wrongYear, outsideLevels }) {
         JsonNode found = byPath.get(other.toString());
         assertThat(found.path("type").asString()).as(other.toString()).isEqualTo("other");
         assertThat(found.path("id").asString()).as(other.toString()).startsWith("note:goals/");
      }
   }

   @Test
   void searchWithTypeGoalFindsOnlyGoals() throws IOException {
      String content = """
            ---
            title: Replication
            ---

            Replication must survive partitions.
            """;
      note("goals/2026/2026-G1-provably-correct.md", content);
      note("goals/2026/Q4/2026-Q4-G1-fix-bugs.md", content);
      note("goals/2026/kickoff/notes.md", content);
      note("zettel/20261015-replication.md", content);

      Result result = CliHarness.run(taskwarrior.environment(), false, "search", "replication", "--type", "goal");

      assertThat(result.exitCode()).isZero();
      assertThat(JSON.readTree(result.out()).path("results").valueStream().map(found -> found.path("id").asString()))
            .containsExactlyInAnyOrder("goal:2026-G1", "goal:2026-Q4-G1");
   }

   @Test
   void searchNamesEffortsByTheirSlugAndReadsTheirMaterialAsOther() throws IOException {
      String content = """
            ---
            title: Replication
            ---

            Replication must survive partitions.
            """;
      Path effort = note("efforts/infinijepsen-demo.md", content);
      // Material: any folder inside the efforts folder, even holding a file named like an effort.
      Path material = note("efforts/infinijepsen-demo/plan.md", content);
      Path materialNamedLikeAnEffort = note("efforts/infinijepsen-demo/raft-everywhere.md", content);

      Result result = CliHarness.run(taskwarrior.environment(), false, "search", "replication");

      assertThat(result.exitCode()).isZero();
      Map<String, JsonNode> byPath = byPath(JSON.readTree(result.out()).path("results"));
      assertThat(byPath).hasSize(3);
      assertThat(byPath.get(effort.toString()).path("id").asString()).isEqualTo("effort:infinijepsen-demo");
      assertThat(byPath.get(effort.toString()).path("type").asString()).isEqualTo("effort");
      for (Path other : new Path[] { material, materialNamedLikeAnEffort }) {
         JsonNode found = byPath.get(other.toString());
         assertThat(found.path("type").asString()).as(other.toString()).isEqualTo("other");
         assertThat(found.path("id").asString()).as(other.toString()).startsWith("note:efforts/infinijepsen-demo/");
      }
   }

   @Test
   void searchWithTypeEffortFindsOnlyEfforts() throws IOException {
      String content = """
            ---
            title: Replication
            ---

            Replication must survive partitions.
            """;
      note("efforts/infinijepsen-demo.md", content);
      note("efforts/infinijepsen-demo/plan.md", content);
      note("goals/2026/2026-G1-provably-correct.md", content);

      Result result = CliHarness.run(taskwarrior.environment(), false, "search", "replication", "--type", "effort");

      assertThat(result.exitCode()).isZero();
      assertThat(JSON.readTree(result.out()).path("results").valueStream().map(found -> found.path("id").asString()))
            .containsExactly("effort:infinijepsen-demo");
   }

   /**
    * Indexes search results by their path.
    *
    * @param results the {@code results} array.
    * @return each result under its absolute path.
    */
   private static Map<String, JsonNode> byPath(JsonNode results) {
      return results.valueStream().collect(Collectors.toMap(found -> found.path("path").asString(), found -> found));
   }

   /**
    * Sets a note's modification time, which orders search results that are otherwise equal.
    *
    * @param note the note's file.
    * @param instant the modification time, e.g. {@code 2026-10-01T00:00:00Z}.
    * @throws IOException when the time cannot be set.
    */
   private static void modified(Path note, String instant) throws IOException {
      Files.setLastModifiedTime(note, FileTime.from(Instant.parse(instant)));
   }

   /**
    * Writes a note into the knowledge base, creating its folders.
    *
    * @param relative the note's path relative to {@code data.location}, e.g. {@code zettel/20261015-causality.md}.
    * @param content the whole file, frontmatter included.
    * @return the absolute path of the note.
    * @throws IOException when the note cannot be written.
    */
   private Path note(String relative, String content) throws IOException {
      Path note = taskwarrior.dataLocation().resolve(relative);
      Files.createDirectories(note.getParent());
      return Files.writeString(note, content);
   }
}
