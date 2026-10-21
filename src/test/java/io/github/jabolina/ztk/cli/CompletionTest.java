package io.github.jabolina.ztk.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.jabolina.ztk.CliHarness;
import io.github.jabolina.ztk.CliHarness.Result;
import io.github.jabolina.ztk.TestTaskwarrior;

/**
 * Completion candidates offered for ztk's arguments.
 *
 * <p>
 * The shell scripts and the matching of subcommands and options belong to Æsh. These tests cover the candidates ztk
 * computes itself from Taskwarrior, asked the way the shell scripts ask: {@code ztk --aesh-complete -- <words>}, where
 * the last word is the one being completed.
 * </p>
 */
class CompletionTest {

   @TempDir
   Path root;

   TestTaskwarrior taskwarrior;

   @BeforeEach
   void setUp() {
      taskwarrior = TestTaskwarrior.create(root);
   }

   @ParameterizedTest
   @ValueSource(strings = { "start", "review", "done", "info" })
   void commandsTakingTaskOfferPendingTaskIds(String command) {
      taskwarrior.task("add", "first", "project:Alpha");
      taskwarrior.task("add", "second", "project:Alpha");

      Result result = complete(command, "");

      assertThat(result.exitCode()).isZero();
      // Only the IDs: no file names, which the shell would otherwise offer next to them.
      assertThat(result.out().lines()).containsExactly("1", "2");
   }

   @Test
   void taskIdsAreFilteredByWhatWasTyped() {
      taskwarrior.task("add", "first", "project:Alpha");
      taskwarrior.task("add", "second", "project:Alpha");

      Result result = complete("done", "2");

      assertThat(result.exitCode()).isZero();
      assertThat(result.out().lines()).containsExactly("2");
   }

   @Test
   void addCompletesProjectAttributeWithExistingProjects() {
      taskwarrior.task("add", "first", "project:Alpha");
      taskwarrior.task("add", "second", "project:Beta");

      Result result = complete("add", "fix", "bug", "project:Al");

      assertThat(result.exitCode()).isZero();
      assertThat(result.out().lines()).containsExactly("project:Alpha");
   }

   @Test
   void addCompletesTagsInUseByOpenTasks() {
      taskwarrior.task("add", "first", "+week", "+review");
      taskwarrior.task("add", "second", "+archived");
      taskwarrior.task("2", "done");

      Result result = complete("add", "fix", "+");

      assertThat(result.exitCode()).isZero();
      // Neither the completed task's tag nor Taskwarrior's virtual tags (ACTIVE, OVERDUE...), which cannot be added.
      assertThat(result.out().lines()).containsExactly("+review", "+week");
   }

   @ParameterizedTest
   @ValueSource(strings = { "list", "week" })
   void listingsCompleteProjectNames(String command) {
      taskwarrior.task("add", "first", "project:Alpha");
      taskwarrior.task("add", "second", "project:Beta");

      Result result = complete(command, "Al");

      assertThat(result.exitCode()).isZero();
      assertThat(result.out().lines()).containsExactly("Alpha");
   }

   @Test
   void graphOffersNoteNamesTaskIdsAndProjects() throws IOException {
      taskwarrior.task("add", "first", "project:Alpha");
      note("zettel/20261015-causality.md");
      note("Alpha/fix-bug.md");

      Result result = complete("graph", "");

      assertThat(result.exitCode()).isZero();
      // Each candidate as graph takes it, and no file names next to them.
      assertThat(result.out().lines()).containsExactlyInAnyOrder("20261015-causality", "fix-bug", "1", "project:Alpha");
   }

   @Test
   void aNoteNameInSeveralFoldersIsOfferedByItsPaths() throws IOException {
      note("Alpha/summary.md");
      note("Beta/summary.md");
      note("zettel/20261015-causality.md");

      Result result = complete("graph", "");

      assertThat(result.exitCode()).isZero();
      // A bare "summary" names both notes, which graph rejects as ambiguous.
      assertThat(result.out().lines()).containsExactlyInAnyOrder("Alpha/summary", "Beta/summary", "20261015-causality");
   }

   @Test
   void aCommaListIsCompletedAsAWholeWord() {
      Result result = complete("graph", "fix-bug", "--kind", "note,t");

      assertThat(result.exitCode()).isZero();
      // The shells replace the whole word, so a bare "task" would not match what was typed, and be dropped.
      assertThat(result.out().lines()).containsExactly("note,task");
   }

   @ParameterizedTest
   @ValueSource(strings = { "goal set 2026-G1 --status", "goal list --status" })
   void goalStatusOffersTheGoalStatuses(String words) {
      Result result = complete(words(words, ""));

      assertThat(result.exitCode()).isZero();
      assertThat(result.out().lines()).containsExactlyInAnyOrder("open", "reached", "missed", "dropped");
   }

   @ParameterizedTest
   @ValueSource(strings = { "effort set demo --status", "effort list --status" })
   void effortStatusOffersTheEffortStatuses(String words) {
      Result result = complete(words(words, ""));

      assertThat(result.exitCode()).isZero();
      assertThat(result.out().lines()).containsExactlyInAnyOrder("planned", "active", "done", "dropped");
   }

   @ParameterizedTest
   @ValueSource(strings = { "set", "info" })
   void goalCommandsOfferEveryGoalId(String command) throws IOException {
      goal("goals/2025/2025-G1-old.md", "reached", null);
      goal("goals/2026/2026-G1-correct.md", "open", null);
      goal("goals/2026/Q4/2026-Q4-G1-bugs.md", "open", "2026-G1");
      // Material under a year's folder is no goal.
      note("goals/2026/data/2026-G9-notes.md");

      Result result = complete("goal", command, "");

      assertThat(result.exitCode()).isZero();
      // Closed goals too: they can still be shown and reopened.
      assertThat(result.out().lines()).containsExactlyInAnyOrder("2025-G1", "2026-G1", "2026-Q4-G1");
   }

   @ParameterizedTest
   @ValueSource(strings = { "goal new 2026-Q4 --title x --parent", "goal set 2026-Q4-G1 --parent" })
   void parentOffersOpenYearlyGoals(String words) throws IOException {
      goal("goals/2025/2025-G1-old.md", "reached", null);
      goal("goals/2026/2026-G1-correct.md", "open", null);
      goal("goals/2026/2026-G2-faster.md", "open", null);
      goal("goals/2026/Q4/2026-Q4-G1-bugs.md", "open", "2026-G1");

      Result result = complete(words(words, "2026"));

      assertThat(result.exitCode()).isZero();
      // Neither quarterly goals nor closed yearly goals can be a parent.
      assertThat(result.out().lines()).containsExactlyInAnyOrder("2026-G1", "2026-G2");
   }

   @ParameterizedTest
   @ValueSource(strings = { "effort new demo --title x --goal", "effort set demo --goal", "effort list --goal" })
   void goalOptionOffersOpenQuarterlyGoals(String words) throws IOException {
      goal("goals/2026/2026-G1-correct.md", "open", null);
      goal("goals/2026/Q3/2026-Q3-G1-tests.md", "missed", "2026-G1");
      goal("goals/2026/Q4/2026-Q4-G1-bugs.md", "open", "2026-G1");
      goal("goals/2026/Q4/2026-Q4-G2-docs.md", "open", "2026-G1");

      Result result = complete(words(words, ""));

      assertThat(result.exitCode()).isZero();
      // Efforts move quarterly goals forward, and only open ones take new work.
      assertThat(result.out().lines()).containsExactlyInAnyOrder("2026-Q4-G1", "2026-Q4-G2");
   }

   @ParameterizedTest
   @ValueSource(strings = { "set", "info" })
   void effortCommandsOfferEveryEffort(String command) throws IOException {
      effort("efforts/demo.md", "active");
      effort("efforts/spec.md", "planned");
      effort("efforts/old-bugs.md", "done");
      // Material under the efforts folder is no effort.
      note("efforts/demo/notes.md");

      Result result = complete("effort", command, "");

      assertThat(result.exitCode()).isZero();
      // Closed efforts too: they can still be shown and reopened.
      assertThat(result.out().lines()).containsExactlyInAnyOrder("demo", "spec", "old-bugs");
   }

   @ParameterizedTest
   @ValueSource(strings = { "effort assign", "add fix project:Alpha --effort" })
   void joiningAnEffortOffersOpenEfforts(String words) throws IOException {
      effort("efforts/demo.md", "active");
      effort("efforts/spec.md", "planned");
      effort("efforts/old-bugs.md", "done");
      effort("efforts/dropped-idea.md", "dropped");

      Result result = complete(words(words, ""));

      assertThat(result.exitCode()).isZero();
      // A closed effort takes no new pending task.
      assertThat(result.out().lines()).containsExactlyInAnyOrder("demo", "spec");
   }

   @ParameterizedTest
   @ValueSource(strings = { "effort assign demo", "effort unassign" })
   void assignAndUnassignOfferPendingTaskIds(String words) throws IOException {
      effort("efforts/demo.md", "active");
      taskwarrior.task("add", "first", "project:Alpha");
      taskwarrior.task("add", "second", "project:Alpha");

      Result result = complete(words(words, ""));

      assertThat(result.exitCode()).isZero();
      assertThat(result.out().lines()).containsExactly("1", "2");
   }

   @Test
   void withoutGoalsGoalCompletionStaysQuiet() {
      Result result = complete("goal", "info", "");

      // Without goals Æsh offers the command's options instead, so stdout is not checked.
      assertThat(result.exitCode()).isZero();
      assertThat(result.err()).isEmpty();
   }

   @Test
   void withoutConfigurationCompletionStaysQuiet() {
      // No ZTKRC and an empty HOME: no configuration can be found.
      Properties env = new Properties();
      env.setProperty("HOME", root.resolve("empty-home").toString());

      Result result = complete(env, "done", "");

      // Completion runs on every Tab: an error there would be printed in the middle of the command being typed.
      // Without task IDs Æsh offers the command's options instead, so stdout is not checked.
      assertThat(result.exitCode()).isZero();
      assertThat(result.err()).isEmpty();
   }

   /**
    * Writes a note into the fixture's knowledge base.
    *
    * @param relative the note's path relative to the knowledge base, e.g. {@code Alpha/fix-bug.md}.
    * @throws IOException when the note cannot be written.
    */
   private void note(String relative) throws IOException {
      note(relative, "# " + Path.of(relative).getFileName() + "\n");
   }

   /**
    * Writes a note with the given content into the fixture's knowledge base.
    *
    * @param relative the note's path relative to the knowledge base, e.g. {@code Alpha/fix-bug.md}.
    * @param content the whole note.
    * @throws IOException when the note cannot be written.
    */
   private void note(String relative, String content) throws IOException {
      Path file = taskwarrior.dataLocation().resolve(relative);
      Files.createDirectories(file.getParent());
      Files.writeString(file, content);
   }

   /**
    * Writes a goal note, as {@code goal new} and {@code goal set} leave it.
    *
    * @param relative the note's path relative to the knowledge base, e.g. {@code goals/2026/2026-G1-correct.md}.
    * @param status the goal's status, e.g. {@code open}.
    * @param parent the yearly goal of a quarterly goal; {@code null} for a yearly goal.
    * @throws IOException when the note cannot be written.
    */
   private void goal(String relative, String status, String parent) throws IOException {
      note(relative, """
            ---
            title: A goal
            status: %s
            %sdate: 2026-01-05T09:00:00+01:00
            ---
            """.formatted(status, parent == null ? "" : "parent: " + parent + "\n"));
   }

   /**
    * Writes an effort note, as {@code effort new} and {@code effort set} leave it.
    *
    * @param relative the note's path relative to the knowledge base, e.g. {@code efforts/demo.md}.
    * @param status the effort's status, e.g. {@code active}.
    * @throws IOException when the note cannot be written.
    */
   private void effort(String relative, String status) throws IOException {
      note(relative, """
            ---
            title: An effort
            status: %s
            goal: 2026-Q4-G1
            date: 2026-10-05T09:00:00+01:00
            ---
            """.formatted(status));
   }

   /**
    * @param words the words before the one being completed, separated by spaces, e.g. {@code goal list --status}.
    * @param last the word being completed, e.g. {@code 2026}; may be empty.
    * @return every word, as {@link #complete(String...)} takes them.
    */
   private static String[] words(String words, String last) {
      String[] split = words.split(" ");
      String[] all = Arrays.copyOf(split, split.length + 1);
      all[split.length] = last;
      return all;
   }

   /**
    * Asks ztk for the candidates of the last word, as the shell completion scripts do, with the fixture's environment.
    *
    * @param words the words after the program name. The last one is being completed and may be empty.
    * @return the exit code and the candidates, one per line on stdout.
    */
   private Result complete(String... words) {
      return complete(taskwarrior.environment(), words);
   }

   /**
    * Asks ztk for the candidates of the last word, as the shell completion scripts do.
    *
    * @param env the process environment ztk runs with.
    * @param words the words after the program name. The last one is being completed and may be empty.
    * @return the exit code and the candidates, one per line on stdout.
    */
   private static Result complete(Properties env, String... words) {
      String[] args = new String[words.length + 2];
      args[0] = "--aesh-complete";
      args[1] = "--";
      System.arraycopy(words, 0, args, 2, words.length);
      return CliHarness.run(env, false, args);
   }
}
