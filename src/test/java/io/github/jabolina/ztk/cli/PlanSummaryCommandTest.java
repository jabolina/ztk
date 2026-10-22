package io.github.jabolina.ztk.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
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
 * The facts of a quarter or a year gathered by {@code ztk summary}, for the retrospective of its goals.
 *
 * <p>
 * The tasks are imported, as Taskwarrior only takes a past {@code modified} that way. The quarter read is 2026-Q3,
 * 2026-07-01 to 2026-09-30. Times are at 10:00 UTC, the same day in any usual time zone.
 * </p>
 *
 * <p>
 * Besides the quarter's goals, the plan has goals of 2026-Q2, one holding an effort carried over and one an effort
 * closed in the quarter, and goals of 2026-Q4: one holding an effort worked on in the quarter, as when the review
 * moves it on before writing, and one pointing to no yearly goal of 2026.
 * </p>
 */
class PlanSummaryCommandTest {

   private static final JsonMapper JSON = JsonMapper.shared();

   private static final String CHECKER = "dddddddd-0000-0000-0000-000000000001";
   private static final String EARLIER = "dddddddd-0000-0000-0000-000000000002";
   private static final String WAITING = "dddddddd-0000-0000-0000-000000000003";
   private static final String REMOVED = "dddddddd-0000-0000-0000-000000000004";
   private static final String RELEASE = "eeeeeeee-0000-0000-0000-000000000001";
   private static final String BACKLOG = "eeeeeeee-0000-0000-0000-000000000002";
   private static final String HAND = "eeeeeeee-0000-0000-0000-000000000003";
   private static final String STRAY = "eeeeeeee-0000-0000-0000-000000000004";
   private static final String MOVED = "ffffffff-0000-0000-0000-000000000001";
   private static final String SIBLING = "ffffffff-0000-0000-0000-000000000002";
   private static final String FRESH = "ffffffff-0000-0000-0000-000000000003";

   @TempDir
   Path root;

   TestTaskwarrior taskwarrior;

   @BeforeEach
   void setUp() throws IOException {
      taskwarrior = TestTaskwarrior.create(root);
      note("goals/2026/2026-G1-provably-correct.md", """
            ---
            title: Provably correct
            status: open
            ---
            """);
      note("goals/2026/Q3/2026-Q3-G1-find-bugs.md", """
            ---
            title: Find bugs
            status: reached
            closed: 2026-09-28
            parent: 2026-G1
            ---
            """);
      note("goals/2026/Q3/2026-Q3-G2-write-docs.md", """
            ---
            title: Write docs
            status: open
            parent: 2026-G1
            ---
            """);
      note("goals/2026/Q2/2026-Q2-G1-old-plan.md", """
            ---
            title: Old plan
            status: open
            parent: 2026-G1
            ---
            """);
      note("goals/2026/Q2/2026-Q2-G2-old-win.md", """
            ---
            title: Old win
            status: reached
            closed: 2026-06-30
            parent: 2026-G1
            ---
            """);
      note("goals/2026/Q4/2026-Q4-G1-keep-going.md", """
            ---
            title: Keep going
            status: open
            parent: 2026-G1
            ---
            """);
      note("goals/2026/Q4/2026-Q4-G2-astray.md", """
            ---
            title: Astray
            status: open
            parent: 2025-G9
            ---
            """);
      // Carried over: open, under a goal of a quarter already ended.
      note("efforts/old-carried.md", """
            ---
            title: Old carried
            status: planned
            goal: 2026-Q2-G1
            ---
            """);
      note("efforts/old-done.md", """
            ---
            title: Old done
            status: done
            closed: 2026-05-30
            goal: 2026-Q2-G1
            ---
            """);
      note("efforts/old-finished.md", """
            ---
            title: Old finished
            status: done
            closed: 2026-07-15
            goal: 2026-Q2-G2
            ---
            """);
      note("efforts/moved.md", """
            ---
            title: Moved
            status: active
            goal: 2026-Q4-G1
            ---
            """);
      note("efforts/fresh.md", """
            ---
            title: Fresh
            status: planned
            goal: 2026-Q4-G1
            ---
            """);
      note("efforts/jepsen.md", """
            ---
            title: Jepsen
            status: done
            closed: 2026-09-20
            goal: 2026-Q3-G1
            ---
            """);
      note("efforts/linearizability.md", """
            ---
            title: Linearizability
            status: planned
            goal: 2026-Q3-G1
            ---
            """);
      imported("""
            {"uuid":"%s","description":"Write checker","status":"completed","project":"Alpha",
             "entry":"20260701T100000Z","modified":"20260810T100000Z","end":"20260810T100000Z",
             "effort":"jepsen","note":"Alpha/20260701-write-checker.md"}""".formatted(CHECKER),
            // Completed the quarter before.
            """
            {"uuid":"%s","description":"Earlier run","status":"completed","project":"Alpha",
             "entry":"20260501T100000Z","modified":"20260610T100000Z","end":"20260610T100000Z",
             "effort":"jepsen"}""".formatted(EARLIER),
            // Untouched in the quarter, but open in a listed effort.
            """
            {"uuid":"%s","description":"Model history","status":"waiting","project":"Beta",
             "entry":"20260501T100000Z","modified":"20260601T100000Z","wait":"20991231T000000Z",
             "effort":"linearizability"}""".formatted(WAITING),
            """
            {"uuid":"%s","description":"Removed idea","status":"deleted","project":"Beta",
             "entry":"20260701T100000Z","modified":"20260801T100000Z","end":"20260801T100000Z",
             "effort":"linearizability"}""".formatted(REMOVED),
            // General work: completed in the quarter, without an effort.
            """
            {"uuid":"%s","description":"Cut release","status":"completed","project":"Alpha",
             "entry":"20260701T100000Z","modified":"20260901T100000Z","end":"20260901T100000Z"}""".formatted(RELEASE),
            // Untouched in the quarter, without an effort.
            """
            {"uuid":"%s","description":"Backlog item","status":"pending","project":"Alpha",
             "entry":"20260501T100000Z","modified":"20260501T100000Z"}""".formatted(BACKLOG),
            // Worked on in the quarter, without a project.
            """
            {"uuid":"%s","description":"Hand work","status":"pending",
             "entry":"20260601T100000Z","modified":"20260601T100000Z"}""".formatted(HAND),
            // Completed in the quarter, in an effort that does not exist.
            """
            {"uuid":"%s","description":"Stray fix","status":"completed","project":"Alpha",
             "entry":"20260601T100000Z","modified":"20260715T100000Z","end":"20260715T100000Z",
             "effort":"nowhere"}""".formatted(STRAY),
            // Worked on in the quarter, in an effort since moved to a goal of the next one.
            """
            {"uuid":"%s","description":"Moved work","status":"pending","project":"Gamma",
             "entry":"20260701T100000Z","modified":"20260820T100000Z","effort":"moved"}""".formatted(MOVED),
            """
            {"uuid":"%s","description":"Moved sibling","status":"pending","project":"Gamma",
             "entry":"20260601T100000Z","modified":"20260601T100000Z","effort":"moved"}""".formatted(SIBLING),
            // Untouched in the quarter, in an effort of the next one.
            """
            {"uuid":"%s","description":"Fresh start","status":"pending","project":"Gamma",
             "entry":"20260601T100000Z","modified":"20260601T100000Z","effort":"fresh"}""".formatted(FRESH));
      day("2026-06-30", written(CHECKER, "Before the quarter"));
      day("2026-08-05", written(HAND, "By hand") + written(CHECKER, "First checker"));
      day("2026-10-01", written(CHECKER, "After the quarter"));
   }

   @Test
   void summaryOfAQuarterGathersItsGoalsEffortsAndTasks() {
      Result result = run("summary", "2026-Q3");

      assertThat(result.exitCode()).isZero();
      JsonNode json = JSON.readTree(result.out());
      assertThat(json.path("schema").asInt()).isEqualTo(1);
      assertThat(json.path("period").asString()).isEqualTo("2026-Q3");
      assertThat(json.path("from").asString()).isEqualTo("2026-07-01");
      assertThat(json.path("to").asString()).isEqualTo("2026-09-30");
      assertThat(json.path("date").asString()).isEqualTo(LocalDate.now().toString());

      // The quarter's goals by ID, without efforts too; the yearly goal is not one of the quarter.
      JsonNode goals = json.path("goals");
      assertThat(ids(goals)).containsExactly("goal:2026-Q3-G1", "goal:2026-Q3-G2");
      JsonNode bugs = goals.get(0);
      assertThat(bugs.path("status").asString()).isEqualTo("reached");
      assertThat(bugs.path("parent").asString()).isEqualTo("goal:2026-G1");
      assertThat(goals.get(1).path("efforts").isEmpty()).isTrue();

      // Every effort of a goal of the quarter, by slug, idle ones too.
      JsonNode efforts = bugs.path("efforts");
      assertThat(ids(efforts)).containsExactly("effort:jepsen", "effort:linearizability");
      assertThat(efforts.get(0).path("status").asString()).isEqualTo("done");
      assertThat(efforts.get(0).path("closed").asString()).isEqualTo("2026-09-20");

      // Tasks with a sign of life in the quarter, or open in a listed effort; never those completed earlier or deleted.
      assertThat(uuids(efforts.get(0).path("tasks"))).containsExactly(CHECKER);
      assertThat(uuids(efforts.get(1).path("tasks"))).containsExactly(WAITING);
   }

   @Test
   void summaryOfAQuarterGivesEachTaskOfAnEffortItsSessionsInTheQuarter() {
      JsonNode efforts = JSON.readTree(run("summary", "2026-Q3").out()).path("goals").get(0).path("efforts");

      JsonNode checker = efforts.get(0).path("tasks").get(0);
      assertThat(checker.path("description").asString()).isEqualTo("Write checker");
      assertThat(checker.path("note").asString())
            .isEqualTo(taskwarrior.dataLocation().resolve("Alpha/20260701-write-checker.md").toString());
      assertThat(checker.path("end").asString()).isEqualTo("2026-08-10T10:00:00Z");
      assertThat(checker.path("untouched").isNull()).isTrue();
      JsonNode sessions = checker.path("sessions");
      assertThat(sessions.size()).isEqualTo(1);
      assertThat(sessions.get(0).path("id").asString()).isEqualTo("2026-08-05#2");
      assertThat(sessions.get(0).path("title").asString()).isEqualTo("First checker");

      // In only for being open in a listed effort: the day it was last changed.
      JsonNode waiting = efforts.get(1).path("tasks").get(0);
      assertThat(waiting.path("untouched").asString()).isEqualTo("2026-06-01");
      assertThat(waiting.path("sessions").isEmpty()).isTrue();
   }

   @Test
   void summaryOfAQuarterListsTheWorkWithoutAnEffortPerProjectWithoutSessions() {
      JsonNode general = JSON.readTree(run("summary", "2026-Q3").out()).path("general");

      // By name, without a project last; only tasks with a sign of life in the quarter.
      assertThat(general.valueStream().map(project -> project.path("project").asString(null)).toList())
            .containsExactly("Alpha", null);
      // An effort that does not exist lists its task with the work without one; check reports it.
      assertThat(uuids(general.get(0).path("tasks"))).containsExactly(STRAY, RELEASE);
      // In for its session, which general work does not carry, to keep the facts small.
      JsonNode hand = general.get(1).path("tasks").get(0);
      assertThat(hand.path("uuid").asString()).isEqualTo(HAND);
      assertThat(hand.has("sessions")).isFalse();
      assertThat(hand.path("untouched").isNull()).isTrue();
   }

   @Test
   void summaryOfAQuarterListsTheOtherGoalsHoldingAnEffortCarriedOverClosedOrWorkedOnInIt() {
      JsonNode others = JSON.readTree(run("summary", "2026-Q3").out()).path("otherGoals");

      // By ID: earlier goals, then later ones; a goal without such an effort is left out.
      assertThat(ids(others)).containsExactly("goal:2026-Q2-G1", "goal:2026-Q2-G2", "goal:2026-Q4-G1");
      // Carried over, even idle; an effort closed before the quarter is left out.
      assertThat(ids(others.get(0).path("efforts"))).containsExactly("effort:old-carried");
      assertThat(others.get(0).path("efforts").get(0).path("carriedOver").asBoolean()).isTrue();
      // Closed in the quarter.
      assertThat(ids(others.get(1).path("efforts"))).containsExactly("effort:old-finished");
      // Worked on in the quarter, with its open tasks, untouched ones too; its idle sibling effort is left out.
      JsonNode keepGoing = others.get(2).path("efforts");
      assertThat(ids(keepGoing)).containsExactly("effort:moved");
      assertThat(uuids(keepGoing.get(0).path("tasks"))).containsExactly(SIBLING, MOVED);
      assertThat(keepGoing.get(0).path("tasks").get(0).path("untouched").asString()).isEqualTo("2026-06-01");
   }

   @Test
   void summaryOfAYearNestsTheQuarterlyGoalsOfItsYearlyGoals() {
      JsonNode json = JSON.readTree(run("summary", "2026").out());

      assertThat(json.path("period").asString()).isEqualTo("2026");
      assertThat(json.path("from").asString()).isEqualTo("2026-01-01");
      assertThat(json.path("to").asString()).isEqualTo("2026-12-31");
      // Each yearly goal, then the quarterly goals pointing to no yearly goal of the year.
      JsonNode goals = json.path("goals");
      assertThat(ids(goals)).containsExactly("goal:2026-G1", "goal:2026-Q4-G2");
      JsonNode correct = goals.get(0);
      assertThat(correct.path("efforts").isEmpty()).isTrue();
      assertThat(ids(correct.path("goals"))).containsExactly("goal:2026-Q2-G1", "goal:2026-Q2-G2",
            "goal:2026-Q3-G1", "goal:2026-Q3-G2", "goal:2026-Q4-G1");
      assertThat(goals.get(1).path("goals").isEmpty()).isTrue();
      // Every effort of a goal of the year, idle and closed ones too.
      assertThat(ids(correct.path("goals").get(0).path("efforts")))
            .containsExactly("effort:old-carried", "effort:old-done");
      assertThat(ids(correct.path("goals").get(4).path("efforts"))).containsExactly("effort:fresh", "effort:moved");
      // The same rules over four quarters: completed in the year, with its sessions in it.
      JsonNode jepsen = correct.path("goals").get(2).path("efforts").get(0);
      assertThat(uuids(jepsen.path("tasks"))).containsExactly(EARLIER, CHECKER);
      assertThat(jepsen.path("tasks").get(1).path("sessions").size()).isEqualTo(3);
      assertThat(json.path("otherGoals").isEmpty()).isTrue();
   }

   @Test
   void summaryForPeoplePrintsTheQuarterAsMarkdownByPlan() {
      Result result = run("summary", "2026-Q3", "--human");

      assertThat(result.exitCode()).isZero();
      // Goals of the period, then the others marked with their quarter, then general work; a goal without efforts is
      // still listed, an effort without tasks too.
      assertThat(result.out()).isEqualTo("""
            ---
            title: 2026-Q3
            period: 2026-Q3
            date: %s
            ---

            # 2026-Q3, 2026-07-01 to 2026-09-30

            ## 2026-Q3-G1 · Find bugs (reached, closed 2026-09-28)

            ### jepsen · Jepsen (done, closed 2026-09-20)

            - [[20260701-write-checker|Write checker]] · Alpha · completed

            ### linearizability · Linearizability (planned, carried over)

            - Model history · Beta · pending (untouched since 2026-06-01)

            ## 2026-Q3-G2 · Write docs (open)

            ## 2026-Q2-G1 · Old plan (open, from 2026-Q2)

            ### old-carried · Old carried (planned, carried over)

            ## 2026-Q2-G2 · Old win (reached, closed 2026-06-30, from 2026-Q2)

            ### old-finished · Old finished (done, closed 2026-07-15)

            ## 2026-Q4-G1 · Keep going (open, in 2026-Q4)

            ### moved · Moved (active)

            - Moved sibling · Gamma · pending (untouched since 2026-06-01)
            - Moved work · Gamma · pending

            ## General work

            ### Alpha

            - Stray fix · completed
            - Cut release · completed

            ### No project

            - Hand work · pending
            """.formatted(LocalDate.now()));
   }

   @Test
   void summaryForPeoplePrintsTheYearWithItsQuarterlyGoalsOneLevelDown() {
      Result result = run("summary", "2026", "--human");

      assertThat(result.exitCode()).isZero();
      assertThat(result.out()).contains("title: \"2026\"\n").containsSubsequence(
            "# 2026, 2026-01-01 to 2026-12-31\n",
            "\n## 2026-G1 · Provably correct (open)\n",
            "\n### 2026-Q2-G1 · Old plan (open)\n",
            "\n#### old-carried · Old carried (planned, carried over)\n",
            "\n#### old-done · Old done (done, closed 2026-05-30)\n",
            "\n### 2026-Q3-G1 · Find bugs (reached, closed 2026-09-28)\n",
            "\n#### jepsen · Jepsen (done, closed 2026-09-20)\n",
            "\n- Earlier run · Alpha · completed\n",
            "\n### 2026-Q4-G1 · Keep going (open)\n",
            // Modified in the year, unlike in the quarter.
            "\n- Fresh start · Gamma · pending\n",
            "\n## 2026-Q4-G2 · Astray (open)\n",
            "\n## General work\n");
   }

   @Test
   void summaryOfAQuarterWithoutGoalsOrTasksSaysSo() {
      TestTaskwarrior empty = TestTaskwarrior.create(root.resolve("empty"));

      Result result = CliHarness.run(empty.environment(), false, "summary", "2026-Q3", "--human");

      assertThat(result.exitCode()).isZero();
      assertThat(result.out()).endsWith("""
            # 2026-Q3, 2026-07-01 to 2026-09-30

            No goals or tasks in 2026-Q3.
            """);
   }

   @Test
   void summaryListsTheGoalsNeedingClosingByItsEnd() {
      JsonNode quarter = JSON.readTree(run("summary", "2026-Q3").out());
      JsonNode year = JSON.readTree(run("summary", "2026").out());

      // Open goals of the period and earlier ones it lists; a later goal is not closed yet, nor is a year running.
      assertThat(quarter.path("needsClosing").valueStream().map(JsonNode::asString).toList())
            .containsExactly("goal:2026-Q2-G1", "goal:2026-Q3-G2");
      assertThat(year.path("needsClosing").valueStream().map(JsonNode::asString).toList())
            .containsExactly("goal:2026-Q2-G1", "goal:2026-Q3-G2");
   }

   @Test
   void summaryWriteRefusesAQuarterWhileItsGoalsNeedClosing() {
      Result result = run("summary", "2026-Q3", "--write");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.VALIDATION.exitCode());
      // Each goal with the command closing it; a goal of a later quarter does not stand in the way.
      assertThat(result.err()).contains("2026-Q3", "ztk goal set 2026-Q2-G1 --status",
            "ztk goal set 2026-Q3-G2 --status");
      assertThat(result.err()).doesNotContain("2026-Q4-G1");
      assertThat(summaries().resolve("2026-Q3.md")).doesNotExist();
   }

   @Test
   void summaryWriteSavesAQuarterOnceItsGoalsAreClosed() {
      // The review: a carried-over effort is settled before its goal closes.
      run("effort", "set", "old-carried", "--status", "dropped");
      run("goal", "set", "2026-Q2-G1", "--status", "missed");
      run("goal", "set", "2026-Q3-G2", "--status", "dropped");

      Result result = CliHarness.runWithStdin("A quarter of bugs found.\n", taskwarrior.environment(), false,
            "summary", "2026-Q3", "--write", "--narrative", "-");

      assertThat(result.exitCode()).isZero();
      Path file = summaries().resolve("2026-Q3.md");
      JsonNode json = JSON.readTree(result.out());
      assertThat(json.path("id").asString()).isEqualTo("2026-Q3");
      assertThat(json.path("path").asString()).isEqualTo(file.toString());
      assertThat(file).content().isEqualTo(run("summary", "2026-Q3", "--human").out() + """

            ---

            A quarter of bugs found.
            """);
   }

   @Test
   void summaryRefusesAPeriodThatIsNeitherAWeekAQuarterNorAYear() {
      Result result = run("summary", "2026-Q5");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.USAGE.exitCode());
      assertThat(result.err()).contains("2026-Q5", "e.g. 2026-W42, 2026-Q4 or 2026");
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
    * @param day the day, e.g. {@code 2026-08-05}.
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
    * @param items goals or efforts of a JSON result.
    * @return their node IDs, in order.
    */
   private static List<String> ids(JsonNode items) {
      return items.valueStream().map(item -> item.path("id").asString()).toList();
   }

   /**
    * @param tasks tasks of a JSON result.
    * @return their UUIDs, in order.
    */
   private static List<String> uuids(JsonNode tasks) {
      return tasks.valueStream().map(task -> task.path("uuid").asString()).toList();
   }

   private Result run(String... args) {
      return CliHarness.run(taskwarrior.environment(), false, args);
   }
}
