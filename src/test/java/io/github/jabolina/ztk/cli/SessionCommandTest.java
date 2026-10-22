package io.github.jabolina.ztk.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jabolina.ztk.CliHarness;
import io.github.jabolina.ztk.CliHarness.Result;
import io.github.jabolina.ztk.TestTaskwarrior;
import io.github.jabolina.ztk.ZtkException;
import io.github.jabolina.ztk.configuration.ConfigurationLoader;
import io.github.jabolina.ztk.taskwarrior.TaskStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Recording work sessions in the journal from the command line, and reading a task's last ones back with {@code info}.
 *
 * <p>
 * Sessions of past days are written straight into the journal, as earlier runs left them, with tasks entered on an
 * earlier day so those days are read.
 * </p>
 */
class SessionCommandTest {

   private static final JsonMapper JSON = JsonMapper.shared();

   @TempDir
   Path root;

   TestTaskwarrior taskwarrior;
   TaskStore store;

   @BeforeEach
   void setUp() {
      taskwarrior = TestTaskwarrior.create(root);
      store = new TaskStore(ConfigurationLoader.load(taskwarrior.environment()));
      taskwarrior.task("add", "Fix flaky test", "project:Alpha");
   }

   @Test
   void sessionAddWritesTodaysJournalUnderTheTaskAndPrintsItsPath() {
      Result result = CliHarness.run(taskwarrior.environment(), true, "session", "add", "1",
            "--title", "Review round 1",
            "--done", "Addressed the reviewer's comments.",
            "--now", "Waiting for round 2.",
            "--next", "Rebase on main.");

      assertThat(result.exitCode()).isZero();
      String today = LocalDate.now().toString();
      Path journal = taskwarrior.dataLocation().resolve("journal").resolve(today + ".md");
      // A path result: only the path on stdout; the human message goes to stderr.
      assertThat(result.out()).isEqualTo(journal + System.lineSeparator());
      assertThat(result.err()).contains(today + "#1", "Task 1");
      assertThat(journal).content().isEqualTo("""
            # %s

            ## [[%s]] · Review round 1
            **Done:** Addressed the reviewer's comments.
            **Now:** Waiting for round 2.
            **Next:** Rebase on main.
            """.formatted(today, store.get("1").uuid()));
   }

   @Test
   void sessionAddAppendsTheNextSessionOfTheDayAndPrintsItAsJsonWhenPiped() {
      taskwarrior.task("add", "Write spec", "project:Alpha");
      session("1", "Review round 1");

      Result result = session("2", "First draft");

      assertThat(result.exitCode()).isZero();
      String today = LocalDate.now().toString();
      Path journal = taskwarrior.dataLocation().resolve("journal").resolve(today + ".md");
      JsonNode json = JSON.readTree(result.out());
      assertThat(json.path("schema").asInt()).isEqualTo(1);
      // Numbered by its position in the day's file.
      assertThat(json.path("id").asString()).isEqualTo(today + "#2");
      assertThat(json.path("path").asString()).isEqualTo(journal.toString());
      assertThat(json.path("task").asString()).isEqualTo(store.get("2").uuid());
      assertThat(journal).content().isEqualTo("""
            # %s

            ## [[%s]] · Review round 1
            **Done:** Did it.
            **Now:** Here.
            **Next:** There.

            ## [[%s]] · First draft
            **Done:** Did it.
            **Now:** Here.
            **Next:** There.
            """.formatted(today, store.get("1").uuid(), store.get("2").uuid()));
   }

   @Test
   void sessionAddReadsAPartGivenAsDashFromStdin() {
      Result result = CliHarness.runWithStdin("Rebased on main.\nTests are green.\n", taskwarrior.environment(), false,
            "session", "add", "1", "--title", "Rebase", "--done", "-", "--now", "Here.", "--next", "There.");

      assertThat(result.exitCode()).isZero();
      // The trailing line break of the piped text is not part of the session.
      assertThat(journal()).content().contains("""
            **Done:** Rebased on main.
            Tests are green.
            **Now:** Here.
            """);
   }

   @Test
   void sessionAddRefusesMoreThanOnePartFromStdin() {
      Result result = CliHarness.runWithStdin("Both.", taskwarrior.environment(), false,
            "session", "add", "1", "--title", "Rebase", "--done", "-", "--now", "-", "--next", "There.");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.USAGE.exitCode());
      assertThat(result.out()).isEmpty();
      assertThat(result.err()).contains("--done", "--now", "stdin");
      assertThat(journal()).doesNotExist();
   }

   @Test
   void sessionAddRefusesABlankPart() {
      Result result = CliHarness.run(taskwarrior.environment(), false, "session", "add", "1",
            "--title", "Rebase", "--done", "Did it.", "--now", "  ", "--next", "There.");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.USAGE.exitCode());
      assertThat(result.err()).contains("--now", "blank");
      assertThat(journal()).doesNotExist();
   }

   @Test
   void sessionAddRefusesATitleOfSeveralLines() {
      Result result = CliHarness.run(taskwarrior.environment(), false, "session", "add", "1",
            "--title", "Rebase\nand more", "--done", "Did it.", "--now", "Here.", "--next", "There.");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.USAGE.exitCode());
      // The title is the session's heading; the hint says where the rest goes.
      assertThat(result.err()).contains("--title", "one line", "--done");
      assertThat(journal()).doesNotExist();
   }

   @Test
   void sessionAddWritesAPartLongerThanThreeLinesWithAWarning() {
      String done = "One.\nTwo.\nThree.\nFour.";

      Result result = CliHarness.run(taskwarrior.environment(), false, "session", "add", "1",
            "--title", "Rebase", "--done", done, "--now", "Here.", "--next", "There.");

      assertThat(result.exitCode()).isZero();
      assertThat(journal()).content().contains("**Done:** " + done + "\n");
      // On stderr, so the JSON result stays as it is.
      assertThat(result.err()).contains("warning", "--done", "task note");
      assertThat(JSON.readTree(result.out()).path("id").asString()).endsWith("#1");
   }

   @Test
   void sessionAddRefusesADeletedTask() {
      String uuid = store.get("1").uuid();
      taskwarrior.task("1", "delete");

      Result result = session(uuid, "Rebase");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.NOT_FOUND.exitCode());
      assertThat(result.err()).contains("deleted", "ztk list");
      assertThat(journal()).doesNotExist();
   }

   @Test
   void sessionAddAcceptsACompletedTaskByItsUuid() {
      String uuid = store.get("1").uuid();
      taskwarrior.task("1", "done");

      Result result = session(uuid, "Follow-up on the merged fix");

      assertThat(result.exitCode()).isZero();
      assertThat(journal()).content().contains("## [[" + uuid + "]] · Follow-up on the merged fix");
   }

   @Test
   void infoGivesTheLastThreeSessionsOfTheTaskNewestFirst() throws IOException {
      String uuid = olderTask();
      String other = store.get("1").uuid();
      day("2026-10-02", written(uuid, "First"));
      day("2026-10-03", written(uuid, "Second"));
      day("2026-10-05", written(other, "Not this task") + written(uuid, "Third"));
      day("2026-10-09", """

            ## [[%s]] · Fourth
            **Done:** Rebased on main.
            Tests are green.
            **Now:** Waiting for review.
            **Next:** Merge.
            """.formatted(uuid));

      Result result = CliHarness.run(taskwarrior.environment(), false, "info", "2");

      assertThat(result.exitCode()).isZero();
      JsonNode sessions = JSON.readTree(result.out()).path("sessions");
      assertThat(sessions).hasSize(3);
      // The ID counts every session of the day, of any task.
      assertThat(sessions.valueStream().map(found -> found.path("id").asString()))
            .containsExactly("2026-10-09#1", "2026-10-05#2", "2026-10-03#1");
      JsonNode newest = sessions.get(0);
      assertThat(newest.path("title").asString()).isEqualTo("Fourth");
      assertThat(newest.path("done").asString()).isEqualTo("Rebased on main.\nTests are green.");
      assertThat(newest.path("now").asString()).isEqualTo("Waiting for review.");
      assertThat(newest.path("next").asString()).isEqualTo("Merge.");
   }

   @Test
   void infoShowsTheSessionsOnTerminal() throws IOException {
      String uuid = olderTask();
      day("2026-10-09", written(uuid, "Fourth"));

      Result result = CliHarness.run(taskwarrior.environment(), true, "info", "2");

      assertThat(result.exitCode()).isZero();
      assertThat(result.out()).contains("Sessions", "2026-10-09#1", "Fourth", "Did it.", "Here.", "There.");
   }

   @Test
   void infoNeverReadsTheJournalBeforeTheTaskWasEntered() throws IOException {
      String uuid = olderTask();
      // Written by hand on a day before the task existed: out of the task's life, so not read.
      day("2026-09-30", written(uuid, "Before the task"));

      Result result = CliHarness.run(taskwarrior.environment(), false, "info", "2");

      assertThat(result.exitCode()).isZero();
      assertThat(JSON.readTree(result.out()).path("sessions").isArray()).isTrue();
      assertThat(JSON.readTree(result.out()).path("sessions")).isEmpty();
   }

   @Test
   void infoWithoutAJournalGivesNoSessions() {
      Result result = CliHarness.run(taskwarrior.environment(), false, "info", "1");

      assertThat(result.exitCode()).isZero();
      // Always present, so agents can rely on the key.
      assertThat(JSON.readTree(result.out()).path("sessions").isArray()).isTrue();
      assertThat(JSON.readTree(result.out()).path("sessions")).isEmpty();
   }

   /**
    * Adds a task entered before the journal days the tests write, as task 2.
    *
    * @return the task's UUID.
    */
   private String olderTask() {
      taskwarrior.task("add", "Old work", "project:Alpha", "entry:2026-10-01");
      return store.get("2").uuid();
   }

   /**
    * Writes a day's journal file as ztk writes it.
    *
    * @param day the day, e.g. {@code 2026-10-02}.
    * @param sessions the sessions of the day, each starting with a blank line.
    * @throws IOException when the file cannot be written.
    */
   private void day(String day, String sessions) throws IOException {
      Path file = taskwarrior.dataLocation().resolve("journal").resolve(day + ".md");
      Files.createDirectories(file.getParent());
      Files.writeString(file, "# " + day + "\n" + sessions);
   }

   /**
    * @param uuid the UUID of the task worked on.
    * @param title the session's title.
    * @return a session with fixed parts, as ztk writes it, starting with a blank line.
    */
   private static String written(String uuid, String title) {
      return """

            ## [[%s]] · %s
            **Done:** Did it.
            **Now:** Here.
            **Next:** There.
            """.formatted(uuid, title);
   }

   /**
    * @return today's journal file, which may not exist.
    */
   private Path journal() {
      return taskwarrior.dataLocation().resolve("journal").resolve(LocalDate.now() + ".md");
   }

   /**
    * Records a session with fixed parts, as an agent would through a pipe.
    *
    * @param task the task ID or UUID.
    * @param title the session's title.
    * @return the outcome of the command.
    */
   private Result session(String task, String title) {
      return CliHarness.run(taskwarrior.environment(), false, "session", "add", task,
            "--title", title, "--done", "Did it.", "--now", "Here.", "--next", "There.");
   }
}
