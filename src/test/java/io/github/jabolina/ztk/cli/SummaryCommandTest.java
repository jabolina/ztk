package io.github.jabolina.ztk.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.List;

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
 * The facts of a period gathered by {@code ztk summary}, for the weekly review.
 *
 * <p>
 * The tasks are imported, as Taskwarrior only takes a past {@code modified} that way. The week read is 2026-W41,
 * Monday 2026-10-05 to Sunday 2026-10-11. Times are at 10:00 UTC, the same day in any usual time zone.
 * </p>
 */
class SummaryCommandTest {

   private static final JsonMapper JSON = JsonMapper.shared();

   private static final String FIX = "aaaaaaaa-0000-0000-0000-000000000001";
   private static final String OLD_RELEASE = "aaaaaaaa-0000-0000-0000-000000000002";
   private static final String REVIEW = "aaaaaaaa-0000-0000-0000-000000000003";
   private static final String ANNOTATED = "aaaaaaaa-0000-0000-0000-000000000004";
   private static final String PLAN = "bbbbbbbb-0000-0000-0000-000000000001";
   private static final String SPEC = "bbbbbbbb-0000-0000-0000-000000000002";
   private static final String IDLE = "bbbbbbbb-0000-0000-0000-000000000003";
   private static final String DROPPED = "bbbbbbbb-0000-0000-0000-000000000004";
   private static final String HAND = "cccccccc-0000-0000-0000-000000000001";

   @TempDir
   Path root;

   TestTaskwarrior taskwarrior;

   @BeforeEach
   void setUp() throws IOException {
      taskwarrior = TestTaskwarrior.create(root);
      note("efforts/infinijepsen-demo.md", """
            ---
            title: InfiniJepsen demo
            status: active
            goal: 2026-Q4-G1
            date: 2026-10-01T09:00:00+01:00
            ---

            ## Context
            """);
      imported("""
            {"uuid":"%s","description":"Fix flaky test","status":"completed","project":"Alpha",
             "entry":"20261001T100000Z","modified":"20261007T100000Z","end":"20261007T100000Z",
             "effort":"infinijepsen-demo","note":"Alpha/20261001-fix-flaky-test.md"}""".formatted(FIX),
            // Completed the week before.
            """
            {"uuid":"%s","description":"Old release","status":"completed","project":"Alpha",
             "entry":"20260920T100000Z","modified":"20260930T100000Z","end":"20260930T100000Z"}""".formatted(OLD_RELEASE),
            // Untouched in the week, but under review now.
            """
            {"uuid":"%s","description":"Review PR","status":"pending","project":"Alpha","tags":["review"],
             "entry":"20261001T100000Z","modified":"20261002T100000Z"}""".formatted(REVIEW),
            // Annotated in the week, but completed the week before.
            """
            {"uuid":"%s","description":"Annotated release","status":"completed","project":"Alpha",
             "entry":"20260920T100000Z","modified":"20261008T100000Z","end":"20260925T100000Z"}""".formatted(ANNOTATED),
            // Untouched in the week, but planned for this one.
            """
            {"uuid":"%s","description":"Plan week","status":"pending","project":"Beta","tags":["week"],
             "entry":"20261001T100000Z","modified":"20261001T100000Z"}""".formatted(PLAN),
            // Worked on in the week, with a session only.
            """
            {"uuid":"%s","description":"Write spec","status":"pending","project":"Beta",
             "entry":"20261002T100000Z","modified":"20261002T100000Z"}""".formatted(SPEC),
            // Sessions only before and after the week.
            """
            {"uuid":"%s","description":"Idle","status":"pending","project":"Beta",
             "entry":"20261002T100000Z","modified":"20261002T100000Z"}""".formatted(IDLE),
            """
            {"uuid":"%s","description":"Dropped idea","status":"deleted","project":"Beta",
             "entry":"20261002T100000Z","modified":"20261008T100000Z","end":"20261008T100000Z"}""".formatted(DROPPED),
            // Worked on by hand in the week, without a session.
            """
            {"uuid":"%s","description":"Hand work","status":"pending",
             "entry":"20261003T100000Z","modified":"20261008T100000Z"}""".formatted(HAND));
      day("2026-10-04", written(IDLE, "Before the week"));
      day("2026-10-06", written(HAND, "Unrelated") + written(SPEC, "First draft"));
      day("2026-10-12", written(IDLE, "After the week"));
   }

   @Test
   void summaryOfAWeekGathersPerProjectTheTasksWithASignOfLifeInItOrOnThePlateNow() {
      Result result = run("summary", "2026-W41");

      assertThat(result.exitCode()).isZero();
      JsonNode json = JSON.readTree(result.out());
      assertThat(json.path("schema").asInt()).isEqualTo(1);
      assertThat(json.path("period").asString()).isEqualTo("2026-W41");
      assertThat(json.path("from").asString()).isEqualTo("2026-10-05");
      assertThat(json.path("to").asString()).isEqualTo("2026-10-11");
      // The day it was taken: under review and in progress are as of then.
      assertThat(json.path("date").asString()).isEqualTo(LocalDate.now().toString());

      // By name, without a project last.
      JsonNode projects = json.path("projects");
      assertThat(projects.valueStream().map(project -> project.path("project").asString(null)).toList())
            .containsExactly("Alpha", "Beta", null);

      JsonNode alpha = projects.get(0);
      assertThat(uuids(alpha.path("completed"))).containsExactly(FIX);
      assertThat(uuids(alpha.path("review"))).containsExactly(REVIEW);
      assertThat(uuids(alpha.path("progress"))).isEmpty();

      JsonNode beta = projects.get(1);
      assertThat(uuids(beta.path("completed"))).isEmpty();
      assertThat(uuids(beta.path("review"))).isEmpty();
      // Oldest first.
      assertThat(uuids(beta.path("progress"))).containsExactly(PLAN, SPEC);

      assertThat(uuids(projects.get(2).path("progress"))).containsExactly(HAND);
   }

   @Test
   void summaryTasksCarryTheirEffortGoalNoteDatesAndSessionsOfTheWeek() {
      JsonNode projects = JSON.readTree(run("summary", "2026-W41").out()).path("projects");

      JsonNode fix = projects.get(0).path("completed").get(0);
      assertThat(fix.path("description").asString()).isEqualTo("Fix flaky test");
      assertThat(fix.path("status").asString()).isEqualTo("completed");
      assertThat(fix.path("effort").asString()).isEqualTo("infinijepsen-demo");
      // The goal of its effort.
      assertThat(fix.path("goal").asString()).isEqualTo("2026-Q4-G1");
      assertThat(fix.path("note").asString())
            .isEqualTo(taskwarrior.dataLocation().resolve("Alpha/20261001-fix-flaky-test.md").toString());
      assertThat(fix.path("modified").asString()).isEqualTo("2026-10-07T10:00:00Z");
      assertThat(fix.path("end").asString()).isEqualTo("2026-10-07T10:00:00Z");
      assertThat(fix.path("started").isNull()).isTrue();
      assertThat(fix.path("sessions").isEmpty()).isTrue();
      assertThat(fix.path("untouched").isNull()).isTrue();

      JsonNode review = projects.get(0).path("review").get(0);
      assertThat(review.path("effort").isNull()).isTrue();
      assertThat(review.path("goal").isNull()).isTrue();
      assertThat(review.path("note").isNull()).isTrue();
      assertThat(review.path("end").isNull()).isTrue();
      // In only for being under review: the day it was last changed.
      assertThat(review.path("untouched").asString()).isEqualTo("2026-10-02");

      JsonNode spec = projects.get(1).path("progress").get(1);
      JsonNode sessions = spec.path("sessions");
      assertThat(sessions.size()).isEqualTo(1);
      // Its ID is its position among every session of the day.
      assertThat(sessions.get(0).path("id").asString()).isEqualTo("2026-10-06#2");
      assertThat(sessions.get(0).path("title").asString()).isEqualTo("First draft");
      assertThat(sessions.get(0).path("done").asString()).isEqualTo("Did it.");
   }

   @Test
   void summaryForPeoplePrintsTheWeekAsMarkdownPerProject() {
      Result result = run("summary", "2026-W41", "--human");

      assertThat(result.exitCode()).isZero();
      String today = LocalDate.now().toString();
      // Empty groups are left out; a task with a note links it.
      assertThat(result.out()).isEqualTo("""
            ---
            title: Week 2026-W41
            period: 2026-W41
            date: %1$s
            ---

            # Week 2026-W41, 2026-10-05 to 2026-10-11

            ## Alpha

            ### Completed

            - [[20261001-fix-flaky-test|Fix flaky test]] · effort infinijepsen-demo · goal 2026-Q4-G1

            ### Under review as of %1$s

            - Review PR (untouched since 2026-10-02)

            ## Beta

            ### In progress as of %1$s

            - Plan week (untouched since 2026-10-01)
            - Write spec

            ## No project

            ### In progress as of %1$s

            - Hand work
            """.formatted(today));
   }

   @Test
   void summaryOfAWeekWithoutTasksSaysSo() {
      TestTaskwarrior empty = TestTaskwarrior.create(root.resolve("empty"));

      Result result = CliHarness.run(empty.environment(), false, "summary", "2026-W41", "--human");

      assertThat(result.exitCode()).isZero();
      assertThat(result.out()).endsWith("""
            # Week 2026-W41, 2026-10-05 to 2026-10-11

            No tasks in the week.
            """);
   }

   @Test
   void summaryWithoutAPeriodCoversThePreviousWeek() {
      LocalDate lastWeek = LocalDate.now().minusWeeks(1);
      String expected = "%d-W%02d".formatted(lastWeek.get(IsoFields.WEEK_BASED_YEAR),
            lastWeek.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));

      Result result = run("summary");

      assertThat(result.exitCode()).isZero();
      assertThat(JSON.readTree(result.out()).path("period").asString()).isEqualTo(expected);
   }

   @Test
   void summaryRefusesAPeriodThatIsNotAWeekOfItsYear() {
      // 2025 has 52 weeks.
      Result result = run("summary", "2025-W53");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.USAGE.exitCode());
      assertThat(result.err()).contains("2025-W53", "e.g. 2026-W42");
   }

   @Test
   void summaryWriteSavesTheFactsAndTheNarrativeFromStdinAndPrintsThePath() {
      Result result = CliHarness.runWithStdin("The week went well.\n\nMostly reviews.\n", taskwarrior.environment(),
            true, "summary", "2026-W41", "--write", "--narrative", "-");

      assertThat(result.exitCode()).isZero();
      Path file = summaries().resolve("2026-W41.md");
      // A path result: only the path on stdout; the human message goes to stderr.
      assertThat(result.out()).isEqualTo(file + System.lineSeparator());
      assertThat(result.err()).contains("2026-W41");
      // The facts as printed, then the narrative below a line.
      assertThat(file).content().isEqualTo(run("summary", "2026-W41", "--human").out() + """

            ---

            The week went well.

            Mostly reviews.
            """);
   }

   @Test
   void summaryWriteWithoutNarrativeSavesTheFactsAndPrintsIdAndPathAsJsonWhenPiped() {
      Result result = run("summary", "2026-W41", "--write");

      assertThat(result.exitCode()).isZero();
      Path file = summaries().resolve("2026-W41.md");
      JsonNode json = JSON.readTree(result.out());
      assertThat(json.path("schema").asInt()).isEqualTo(1);
      assertThat(json.path("id").asString()).isEqualTo("2026-W41");
      assertThat(json.path("path").asString()).isEqualTo(file.toString());
      assertThat(file).content().isEqualTo(run("summary", "2026-W41", "--human").out());
   }

   @Test
   void summaryWriteNeverOverwritesAWrittenSummary() throws IOException {
      run("summary", "2026-W41", "--write");
      Path file = summaries().resolve("2026-W41.md");
      Files.writeString(file, "Edited by hand.\n");

      Result result = run("summary", "2026-W41", "--write");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.VALIDATION.exitCode());
      assertThat(result.err()).contains(file.toString(), "delete it");
      assertThat(file).content().isEqualTo("Edited by hand.\n");
   }

   @Test
   void summaryWriteRefusesAWeekThatHasNotEnded() {
      LocalDate today = LocalDate.now();
      String week = "%d-W%02d".formatted(today.get(IsoFields.WEEK_BASED_YEAR),
            today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
      LocalDate sunday = today.with(DayOfWeek.SUNDAY);

      Result result = run("summary", week, "--write");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.VALIDATION.exitCode());
      // The day it ends, and that it can still be read.
      assertThat(result.err()).contains(week, sunday.toString(), "ztk summary " + week);
      assertThat(summaries().resolve(week + ".md")).doesNotExist();
   }

   @Test
   void summaryRefusesANarrativeWithoutWrite() {
      Result result = run("summary", "2026-W41", "--narrative", "Went well.");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.USAGE.exitCode());
      assertThat(result.err()).contains("--write");
   }

   @Test
   void summaryWriteRefusesABlankNarrative() {
      Result result = CliHarness.runWithStdin("  \n", taskwarrior.environment(), false,
            "summary", "2026-W41", "--write", "--narrative", "-");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.USAGE.exitCode());
      assertThat(result.err()).contains("--narrative");
      assertThat(summaries().resolve("2026-W41.md")).doesNotExist();
   }

   /**
    * @return the summaries folder, which may not exist.
    */
   private Path summaries() {
      return taskwarrior.dataLocation().resolve("summaries");
   }

   /**
    * Imports tasks into the fixture as Taskwarrior exports them.
    *
    * @param tasks each task as a JSON object.
    * @throws IOException when the import file cannot be written.
    */
   private void imported(String... tasks) throws IOException {
      Path file = Files.writeString(root.resolve("import.json"), "[" + String.join(",", tasks) + "]");
      taskwarrior.task("import", file.toString());
   }

   /**
    * Writes a day's journal file as ztk writes it.
    *
    * @param day the day, e.g. {@code 2026-10-06}.
    * @param sessions the sessions of the day, each starting with a blank line.
    * @throws IOException when the file cannot be written.
    */
   private void day(String day, String sessions) throws IOException {
      note("journal/" + day + ".md", "# " + day + "\n" + sessions);
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
    * Writes a note by hand, creating its folders.
    *
    * @param relative the note's path relative to {@code data.location}.
    * @param content the whole file.
    * @throws IOException when the note cannot be written.
    */
   private void note(String relative, String content) throws IOException {
      Path note = taskwarrior.dataLocation().resolve(relative);
      Files.createDirectories(note.getParent());
      Files.writeString(note, content);
   }

   /**
    * @param tasks a group of tasks of a JSON result.
    * @return their UUIDs, in order.
    */
   private static List<String> uuids(JsonNode tasks) {
      return tasks.valueStream().map(task -> task.path("uuid").asString()).toList();
   }

   private Result run(String... args) {
      return CliHarness.run(taskwarrior.environment(), false, args);
   }
}
