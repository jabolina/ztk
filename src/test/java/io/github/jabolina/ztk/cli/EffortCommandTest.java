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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.jabolina.ztk.CliHarness;
import io.github.jabolina.ztk.CliHarness.Result;
import io.github.jabolina.ztk.TestTaskwarrior;
import io.github.jabolina.ztk.ZtkException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Creating and managing efforts from the command line.
 */
class EffortCommandTest {

   private static final JsonMapper JSON = JsonMapper.shared();

   @TempDir
   Path root;

   TestTaskwarrior taskwarrior;
   Path efforts;

   @BeforeEach
   void setUp() throws IOException {
      taskwarrior = TestTaskwarrior.create(root);
      efforts = taskwarrior.dataLocation().resolve("efforts");
      note("goals/2026/2026-G1-provably-correct.md", goal("Infinispan provably correct", "open", null));
      note("goals/2026/Q4/2026-Q4-G1-fix-bugs.md", goal("Fix found data loss bugs", "open", "2026-G1"));
   }

   @Test
   void effortNewCreatesTheEffortFromItsTemplateAndPrintsItsPath() throws IOException {
      Result result = run("effort", "new", "infinijepsen-demo", "--title", "InfiniJepsen demo", "--goal", "2026-Q4-G1",
            "--human");

      assertThat(result.exitCode()).isZero();
      Path effort = efforts.resolve("infinijepsen-demo.md");
      // Only the bare path on stdout, so it can be used in shell substitutions.
      assertThat(result.out()).isEqualTo(effort + System.lineSeparator());
      assertThat(result.err()).contains("infinijepsen-demo");

      JsonNode frontmatter = frontmatter(effort);
      assertThat(frontmatter.path("title").asString()).isEqualTo("InfiniJepsen demo");
      assertThat(frontmatter.path("status").asString()).isEqualTo("planned");
      assertThat(frontmatter.path("goal").asString()).isEqualTo("2026-Q4-G1");
      assertThat(frontmatter.path("date").asString()).startsWith("20");
      assertThat(frontmatter.has("closed")).isFalse();
      assertThat(Files.readString(effort)).containsSubsequence(
            "## Context", "## Done when", "## Approach", "## Decisions", "## Risks and open questions", "## Outcome");
   }

   @Test
   void effortNewPrintsTheEffortIdAndPathAsJsonWhenPiped() {
      Result result = run("effort", "new", "infinijepsen-demo", "--title", "InfiniJepsen demo", "--goal", "2026-Q4-G1");

      assertThat(result.exitCode()).isZero();
      JsonNode json = JSON.readTree(result.out());
      assertThat(json.path("schema").asInt()).isEqualTo(1);
      // The node ID graph and search print, so the result can be fed straight back to them.
      assertThat(json.path("id").asString()).isEqualTo("effort:infinijepsen-demo");
      assertThat(json.path("path").asString()).isEqualTo(efforts.resolve("infinijepsen-demo.md").toString());
   }

   @Test
   void effortNewAcceptsTheGoalAsANodeIdAndStoresItBare() throws IOException {
      Result result = run("effort", "new", "infinijepsen-demo", "--title", "InfiniJepsen demo", "--goal", "goal:2026-Q4-G1");

      assertThat(result.exitCode()).isZero();
      assertThat(frontmatter(efforts.resolve("infinijepsen-demo.md")).path("goal").asString()).isEqualTo("2026-Q4-G1");
   }

   @Test
   void effortNewRefusesASlugThatIsNotLowercaseLettersDigitsAndDashes() {
      Result result = run("effort", "new", "InfiniJepsen_Demo", "--title", "InfiniJepsen demo", "--goal", "2026-Q4-G1");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.USAGE.exitCode());
      // Suggests a valid slug made from the one given.
      assertThat(result.err()).contains("InfiniJepsen_Demo", "infinijepsen-demo");
      assertThat(efforts).doesNotExist();
   }

   @Test
   void effortNewRefusesASlugThatIsAlreadyTheNameOfANote() throws IOException {
      // A wikilink to the effort would then be ambiguous.
      note("zettel/infinijepsen-demo.md", "A zettel.\n");

      Result result = run("effort", "new", "infinijepsen-demo", "--title", "InfiniJepsen demo", "--goal", "2026-Q4-G1");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.VALIDATION.exitCode());
      assertThat(result.err()).contains("zettel/infinijepsen-demo");
      assertThat(efforts.resolve("infinijepsen-demo.md")).doesNotExist();
   }

   @Test
   void effortNewRefusesTheSlugOfAnExistingEffortKeepingIt() throws IOException {
      Path existing = note("efforts/infinijepsen-demo.md", "---\ntitle: Earlier\nstatus: active\ngoal: 2026-Q4-G1\n---\n");

      Result result = run("effort", "new", "infinijepsen-demo", "--title", "InfiniJepsen demo", "--goal", "2026-Q4-G1");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.VALIDATION.exitCode());
      assertThat(result.err()).contains("efforts/infinijepsen-demo");
      assertThat(frontmatter(existing).path("title").asString()).isEqualTo("Earlier");
   }

   @Test
   void effortNewRefusesAGoalThatIsNotAQuarterlyGoal() {
      Result unknown = run("effort", "new", "infinijepsen-demo", "--title", "InfiniJepsen demo", "--goal", "2026-Q4-G9");
      // Efforts move quarterly goals forward, never yearly ones directly.
      Result yearly = run("effort", "new", "infinijepsen-demo", "--title", "InfiniJepsen demo", "--goal", "2026-G1");

      for (Result result : new Result[] { unknown, yearly }) {
         assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.VALIDATION.exitCode());
         // Says where to find the goals an effort can have.
         assertThat(result.err()).contains("ztk goal list");
      }
      assertThat(unknown.err()).contains("2026-Q4-G9");
      assertThat(yearly.err()).contains("2026-G1");
      assertThat(efforts).doesNotExist();
   }

   @Test
   void effortNewRefusesAClosedGoalNamingHowToReopenIt() throws IOException {
      note("goals/2026/Q3/2026-Q3-G1-reached.md", goal("Reached already", "reached", "2026-G1"));

      Result result = run("effort", "new", "infinijepsen-demo", "--title", "InfiniJepsen demo", "--goal", "2026-Q3-G1");

      // Strict: no open work goes under a closed goal.
      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.VALIDATION.exitCode());
      assertThat(result.err()).contains("reached", "ztk goal set 2026-Q3-G1 --status open");
      assertThat(efforts).doesNotExist();
   }

   @Test
   void effortSetStatusClosesTheEffortAndLeavesEverythingElseAsItWas() throws IOException {
      String body = """

            ## Context
            Written by hand, with a [[wikilink]].
            """;
      Path effort = note("efforts/infinijepsen-demo.md", """
            ---
            title: InfiniJepsen demo
            status: active
            goal: 2026-Q4-G1
            date: 2026-10-05T09:00:00+01:00
            owner: someone
            ---
            """ + body);

      Result result = run("effort", "set", "infinijepsen-demo", "--status", "done", "--human");

      assertThat(result.exitCode()).isZero();
      // The node ID, as graph and search print it.
      assertThat(result.out()).isEqualTo("effort:infinijepsen-demo" + System.lineSeparator());
      JsonNode frontmatter = frontmatter(effort);
      assertThat(frontmatter.path("status").asString()).isEqualTo("done");
      assertThat(frontmatter.path("closed").asString()).isEqualTo(LocalDate.now().toString());
      // Fields ztk does not know are kept, and the body is untouched.
      assertThat(frontmatter.path("owner").asString()).isEqualTo("someone");
      assertThat(Files.readString(effort)).endsWith("\n---\n" + body);
   }

   @Test
   void effortSetStatusBackToAnOpenOneRemovesClosed() throws IOException {
      Path effort = note("efforts/infinijepsen-demo.md", effort("dropped", "2026-Q4-G1", "2026-10-10"));

      Result result = run("effort", "set", "infinijepsen-demo", "--status", "active");

      assertThat(result.exitCode()).isZero();
      assertThat(frontmatter(effort).path("status").asString()).isEqualTo("active");
      assertThat(frontmatter(effort).has("closed")).isFalse();
   }

   @Test
   void effortSetTheSameClosedStatusKeepsTheDateItClosed() throws IOException {
      Path effort = note("efforts/infinijepsen-demo.md", effort("done", "2026-Q4-G1", "2026-10-10"));

      Result result = run("effort", "set", "infinijepsen-demo", "--status", "done");

      assertThat(result.exitCode()).isZero();
      assertThat(frontmatter(effort).path("closed").asString()).isEqualTo("2026-10-10");
   }

   @Test
   void effortSetTheSameClosedStatusDatesItWhenItHasNoClosedDate() throws IOException {
      // Closed by hand, without the date: check reports a closed mismatch, and this fixes it.
      Path effort = note("efforts/infinijepsen-demo.md", effort("done", "2026-Q4-G1", null));

      Result result = run("effort", "set", "infinijepsen-demo", "--status", "done");

      assertThat(result.exitCode()).isZero();
      assertThat(frontmatter(effort).path("closed").asString()).isEqualTo(LocalDate.now().toString());
   }

   @Test
   void effortSetTitleChangesOnlyTheTitleAndAcceptsTheNodeId() throws IOException {
      Path effort = note("efforts/infinijepsen-demo.md", effort("active", "2026-Q4-G1", null));

      // The node ID, as JSON results print it, so an agent can feed it straight back.
      Result result = run("effort", "set", "effort:infinijepsen-demo", "--title", "InfiniJepsen live demo");

      assertThat(result.exitCode()).isZero();
      JsonNode json = JSON.readTree(result.out());
      assertThat(json.path("id").asString()).isEqualTo("effort:infinijepsen-demo");
      assertThat(json.path("path").asString()).isEqualTo(effort.toString());
      assertThat(frontmatter(effort).path("title").asString()).isEqualTo("InfiniJepsen live demo");
      assertThat(frontmatter(effort).path("status").asString()).isEqualTo("active");
   }

   @Test
   void effortSetGoalCarriesTheEffortOverToAnotherQuarter() throws IOException {
      note("goals/2027/2027-G1-provably-correct.md", goal("Infinispan provably correct", "open", null));
      note("goals/2027/Q1/2027-Q1-G1-more-bugs.md", goal("Fix more bugs", "open", "2027-G1"));
      Path effort = note("efforts/infinijepsen-demo.md", effort("active", "2026-Q4-G1", null));

      Result result = run("effort", "set", "infinijepsen-demo", "--goal", "goal:2027-Q1-G1");

      assertThat(result.exitCode()).isZero();
      // The file never moves: only the goal it points to changes, stored bare.
      assertThat(frontmatter(effort).path("goal").asString()).isEqualTo("2027-Q1-G1");
   }

   @Test
   void effortSetRefusesAGoalAnEffortCannotHaveKeepingTheEffort() throws IOException {
      note("goals/2026/Q3/2026-Q3-G1-reached.md", goal("Reached already", "reached", "2026-G1"));
      Path effort = note("efforts/infinijepsen-demo.md", effort("active", "2026-Q4-G1", null));

      Result closed = run("effort", "set", "infinijepsen-demo", "--goal", "2026-Q3-G1");
      Result yearly = run("effort", "set", "infinijepsen-demo", "--goal", "2026-G1");

      assertThat(closed.exitCode()).isEqualTo(ZtkException.Kind.VALIDATION.exitCode());
      assertThat(closed.err()).contains("ztk goal set 2026-Q3-G1 --status open");
      assertThat(yearly.exitCode()).isEqualTo(ZtkException.Kind.VALIDATION.exitCode());
      assertThat(yearly.err()).contains("ztk goal list");
      assertThat(frontmatter(effort).path("goal").asString()).isEqualTo("2026-Q4-G1");
   }

   @ParameterizedTest
   @ValueSource(strings = { "no-such-effort", "effort:no-such-effort" })
   void effortSetOnAnEffortThatDoesNotExistExitsNotFound(String slug) {
      Result result = run("effort", "set", slug, "--status", "active");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.NOT_FOUND.exitCode());
      assertThat(result.err()).contains("no-such-effort", "ztk effort list");
   }

   @Test
   void effortSetRefusesAnUnknownStatusListingTheValidOnes() throws IOException {
      note("efforts/infinijepsen-demo.md", effort("active", "2026-Q4-G1", null));

      Result result = run("effort", "set", "infinijepsen-demo", "--status", "finished");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.USAGE.exitCode());
      assertThat(result.err()).contains("finished", "planned", "active", "done", "dropped");
   }

   @Test
   void effortSetWithNothingToChangeIsAUsageError() throws IOException {
      note("efforts/infinijepsen-demo.md", effort("active", "2026-Q4-G1", null));

      Result result = run("effort", "set", "infinijepsen-demo");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.USAGE.exitCode());
      assertThat(result.err()).contains("--status", "--title", "--goal");
   }

   @Test
   void tasksShowTheirEffortOrNullForGeneralWork() {
      taskwarrior.task("rc.uda.effort.type=string", "add", "Fix bug", "project:Alpha", "effort:infinijepsen-demo");
      taskwarrior.task("add", "Release", "project:Alpha");

      // The common task shape: every task command prints it.
      assertThat(task("1").path("effort").asString()).isEqualTo("infinijepsen-demo");
      JsonNode general = task("2");
      assertThat(general.has("effort")).isTrue();
      assertThat(general.path("effort").isNull()).isTrue();
   }

   @Test
   void effortAssignJoinsEveryTaskToTheEffortAndPrintsThem() throws IOException {
      note("efforts/infinijepsen-demo.md", effort("active", "2026-Q4-G1", null));
      taskwarrior.task("add", "Fix bug", "project:Alpha");
      taskwarrior.task("add", "Write demo", "project:Beta");
      String uuid = taskwarrior.task("_get", "2.uuid");

      // The effort as a node ID and a task by UUID, as JSON results print them.
      Result result = run("effort", "assign", "effort:infinijepsen-demo", "1", uuid);

      assertThat(result.exitCode()).as(result.err()).isZero();
      assertThat(JSON.readTree(result.out()).path("tasks").valueStream()
            .map(task -> task.path("description").asString() + " " + task.path("effort").asString()))
            .containsExactly("Fix bug infinijepsen-demo", "Write demo infinijepsen-demo");
      assertThat(task("1").path("effort").asString()).isEqualTo("infinijepsen-demo");
      assertThat(task("2").path("effort").asString()).isEqualTo("infinijepsen-demo");
   }

   @Test
   void effortAssignRefusesAClosedEffortForAPendingTaskChangingNothing() throws IOException {
      note("efforts/infinijepsen-demo.md", effort("done", "2026-Q4-G1", "2026-10-10"));
      taskwarrior.task("add", "Fix bug", "project:Alpha");
      taskwarrior.task("add", "Write demo", "project:Alpha");
      String uuid = taskwarrior.task("_get", "1.uuid");
      taskwarrior.task("rc.confirmation=off", "1", "done");

      // The completed task could join, but the pending one cannot: all or nothing.
      Result result = run("effort", "assign", "infinijepsen-demo", uuid, "1");

      // Strict: no open work goes under a closed effort.
      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.VALIDATION.exitCode());
      assertThat(result.err()).contains("done", "ztk effort set infinijepsen-demo --status active");
      assertThat(task(uuid).path("effort").isNull()).isTrue();
      assertThat(task("1").path("effort").isNull()).isTrue();
   }

   @Test
   void effortAssignLetsACompletedTaskJoinAClosedEffort() throws IOException {
      note("efforts/infinijepsen-demo.md", effort("done", "2026-Q4-G1", "2026-10-10"));
      taskwarrior.task("add", "Fix bug", "project:Alpha");
      String uuid = taskwarrior.task("_get", "1.uuid");
      taskwarrior.task("rc.confirmation=off", "1", "done");

      // Work done for the effort, recorded after the fact.
      Result result = run("effort", "assign", "infinijepsen-demo", uuid);

      assertThat(result.exitCode()).as(result.err()).isZero();
      assertThat(task(uuid).path("effort").asString()).isEqualTo("infinijepsen-demo");
   }

   @Test
   void effortAssignToAnEffortThatDoesNotExistExitsNotFound() {
      taskwarrior.task("add", "Fix bug", "project:Alpha");

      Result result = run("effort", "assign", "no-such-effort", "1");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.NOT_FOUND.exitCode());
      assertThat(result.err()).contains("no-such-effort", "ztk effort list");
      assertThat(task("1").path("effort").isNull()).isTrue();
   }

   @Test
   void effortUnassignMakesTheTasksGeneralWork() {
      taskwarrior.task("rc.uda.effort.type=string", "add", "Fix bug", "project:Alpha", "effort:infinijepsen-demo");
      taskwarrior.task("rc.uda.effort.type=string", "add", "Write demo", "project:Alpha", "effort:infinijepsen-demo");

      Result result = run("effort", "unassign", "1", "2");

      assertThat(result.exitCode()).as(result.err()).isZero();
      assertThat(JSON.readTree(result.out()).path("tasks").valueStream().map(task -> task.path("effort").isNull()))
            .containsExactly(true, true);
      assertThat(task("1").path("effort").isNull()).isTrue();
      assertThat(task("2").path("effort").isNull()).isTrue();
   }

   @Test
   void effortListShowsTheOpenEffortsOfEveryQuarterMarkingThoseCarriedOver() throws IOException {
      // Quarters far apart, so which have ended does not depend on when the test runs.
      Path old = note("efforts/old-demo.md", effort("active", "2020-Q1-G1", null));
      note("efforts/new-demo.md", effort("planned", "2099-Q4-G1", null));
      note("efforts/done-demo.md", effort("done", "2099-Q4-G1", "2099-10-10"));
      note("efforts/dropped-demo.md", effort("dropped", "2020-Q1-G1", "2020-02-02"));

      Result result = run("effort", "list");

      assertThat(result.exitCode()).as(result.err()).isZero();
      JsonNode efforts = JSON.readTree(result.out()).path("efforts");
      // By the quarter of their goal; closed efforts are left out.
      assertThat(ids(efforts)).containsExactly("effort:old-demo", "effort:new-demo");
      JsonNode carried = efforts.get(0);
      assertThat(carried.path("title").asString()).isEqualTo("InfiniJepsen demo");
      assertThat(carried.path("status").asString()).isEqualTo("active");
      assertThat(carried.path("closed").isNull()).isTrue();
      // A node ID, so it can be fed straight back.
      assertThat(carried.path("goal").asString()).isEqualTo("goal:2020-Q1-G1");
      assertThat(carried.path("quarter").asString()).isEqualTo("2020-Q1");
      assertThat(carried.path("carriedOver").asBoolean()).isTrue();
      assertThat(carried.path("path").asString()).isEqualTo(old.toString());
      assertThat(efforts.get(1).path("carriedOver").asBoolean()).isFalse();
   }

   @Test
   void effortListWithAPeriodShowsTheEffortsOfItsGoalsInAnyStatus() throws IOException {
      note("efforts/old-demo.md", effort("active", "2020-Q1-G1", null));
      note("efforts/new-demo.md", effort("planned", "2099-Q4-G1", null));
      note("efforts/done-demo.md", effort("done", "2099-Q4-G2", "2099-10-10"));
      note("efforts/spring-demo.md", effort("active", "2099-Q1-G1", null));

      Result quarter = run("effort", "list", "2099-Q4");
      Result year = run("effort", "list", "2099");

      assertThat(ids(JSON.readTree(quarter.out()).path("efforts"))).containsExactly("effort:new-demo", "effort:done-demo");
      // A year: the efforts of its four quarters.
      assertThat(ids(JSON.readTree(year.out()).path("efforts")))
            .containsExactly("effort:spring-demo", "effort:new-demo", "effort:done-demo");
   }

   @Test
   void effortListFiltersByStatusAndByGoal() throws IOException {
      note("efforts/old-demo.md", effort("done", "2020-Q1-G1", "2020-02-02"));
      note("efforts/new-demo.md", effort("planned", "2099-Q4-G1", null));
      note("efforts/done-demo.md", effort("done", "2099-Q4-G2", "2099-10-10"));

      Result done = run("effort", "list", "--status", "done");
      Result ofGoal = run("effort", "list", "--goal", "goal:2099-Q4-G2");

      // Any quarter, only that status.
      assertThat(ids(JSON.readTree(done.out()).path("efforts"))).containsExactly("effort:old-demo", "effort:done-demo");
      assertThat(ids(JSON.readTree(ofGoal.out()).path("efforts"))).containsExactly("effort:done-demo");
   }

   @Test
   void effortListCountsTheTasksOfEachEffortByStatus() throws IOException {
      note("efforts/infinijepsen-demo.md", effort("active", "2099-Q4-G1", null));
      note("efforts/new-demo.md", effort("planned", "2099-Q4-G1", null));
      taskwarrior.task("rc.uda.effort.type=string", "add", "Fix bug", "project:Alpha", "effort:infinijepsen-demo");
      // Waiting is still to do: pending.
      taskwarrior.task("rc.uda.effort.type=string", "add", "Review", "project:Alpha", "wait:2099-01-01",
            "effort:infinijepsen-demo");
      taskwarrior.task("rc.uda.effort.type=string", "add", "Write demo", "project:Alpha", "effort:infinijepsen-demo");
      taskwarrior.task("rc.uda.effort.type=string", "add", "Scrapped", "project:Alpha", "effort:infinijepsen-demo");
      String writeDemo = taskwarrior.task("_get", "3.uuid");
      String scrapped = taskwarrior.task("_get", "4.uuid");
      // A completed task, then a deleted one, which is not counted.
      taskwarrior.task("rc.confirmation=off", writeDemo, "done");
      taskwarrior.task("rc.confirmation=off", scrapped, "delete");

      JsonNode efforts = JSON.readTree(run("effort", "list").out()).path("efforts");

      assertThat(efforts.get(0).path("tasks").path("pending").asInt()).isEqualTo(2);
      assertThat(efforts.get(0).path("tasks").path("completed").asInt()).isEqualTo(1);
      assertThat(efforts.get(1).path("tasks").path("pending").asInt()).isZero();
      assertThat(efforts.get(1).path("tasks").path("completed").asInt()).isZero();
   }

   @Test
   void onATerminalEffortListIsATableMarkingCarriedOverEfforts() throws IOException {
      note("efforts/old-demo.md", effort("active", "2020-Q1-G1", null));
      taskwarrior.task("rc.uda.effort.type=string", "add", "Fix bug", "project:Alpha", "effort:old-demo");

      Result result = run("effort", "list", "--human");

      assertThat(result.exitCode()).isZero();
      assertThat(result.out()).contains("old-demo", "active, carried over", "2020-Q1-G1", "1 pending, 0 completed",
            "InfiniJepsen demo");
   }

   @Test
   void anEmptyEffortListSaysHowToCreateOne() {
      Result result = run("effort", "list", "--human");

      assertThat(result.exitCode()).isZero();
      assertThat(result.out()).contains("ztk effort new");
   }

   @Test
   void effortInfoShowsTheEffortItsGoalChainAndItsTasksByStatus() throws IOException {
      Path effort = note("efforts/infinijepsen-demo.md", effort("active", "2026-Q4-G1", null));
      note("efforts/infinijepsen-demo-two.md", effort("active", "2026-Q4-G1", null));
      run("add", "Fix", "bug", "project:Alpha", "--effort", "infinijepsen-demo");
      run("add", "Old", "bug", "project:Alpha", "--effort", "infinijepsen-demo");
      run("done", "2");
      // Another effort whose slug starts the same, and general work: neither is listed.
      run("add", "Other", "project:Alpha", "--effort", "infinijepsen-demo-two");
      taskwarrior.task("add", "General", "project:Alpha");

      Result result = run("effort", "info", "infinijepsen-demo");

      assertThat(result.exitCode()).as(result.err()).isZero();
      JsonNode info = JSON.readTree(result.out());
      assertThat(info.path("id").asString()).isEqualTo("effort:infinijepsen-demo");
      assertThat(info.path("title").asString()).isEqualTo("InfiniJepsen demo");
      assertThat(info.path("status").asString()).isEqualTo("active");
      assertThat(info.path("path").asString()).isEqualTo(effort.toString());
      // The goal chain: the quarterly goal, then the yearly goal it moves forward.
      JsonNode goal = info.path("goal");
      assertThat(goal.path("id").asString()).isEqualTo("goal:2026-Q4-G1");
      assertThat(goal.path("title").asString()).isEqualTo("Fix found data loss bugs");
      assertThat(goal.path("status").asString()).isEqualTo("open");
      assertThat(goal.path("path").asString()).endsWith("2026-Q4-G1-fix-bugs.md");
      assertThat(goal.path("parent").path("id").asString()).isEqualTo("goal:2026-G1");
      assertThat(goal.path("parent").path("title").asString()).isEqualTo("Infinispan provably correct");
      assertThat(goal.path("parent").path("parent").isNull()).isTrue();
      // Tasks by status, in the common task shape plus their note.
      JsonNode pending = info.path("tasks").path("pending");
      assertThat(pending.valueStream().map(task -> task.path("description").asString())).containsExactly("Fix bug");
      assertThat(pending.get(0).path("note").asString()).endsWith(".md");
      assertThat(info.path("tasks").path("completed").valueStream().map(task -> task.path("description").asString()))
            .containsExactly("Old bug");
   }

   @Test
   void effortInfoShowsNoGoalWhenItsGoalDoesNotExist() throws IOException {
      note("efforts/infinijepsen-demo.md", effort("active", "2026-Q4-G9", null));

      // The node ID, as JSON results print it, so an agent can feed it straight back.
      Result result = run("effort", "info", "effort:infinijepsen-demo");

      assertThat(result.exitCode()).as(result.err()).isZero();
      JsonNode info = JSON.readTree(result.out());
      assertThat(info.path("goal").isNull()).isTrue();
      assertThat(info.path("tasks").path("pending").isEmpty()).isTrue();
      assertThat(info.path("tasks").path("completed").isEmpty()).isTrue();
   }

   @Test
   void effortInfoOnAnEffortThatDoesNotExistExitsNotFound() {
      Result result = run("effort", "info", "no-such-effort");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.NOT_FOUND.exitCode());
      assertThat(result.err()).contains("no-such-effort", "ztk effort list");
   }

   @Test
   void onATerminalEffortInfoShowsTheGoalChainAndTheTasks() throws IOException {
      note("efforts/infinijepsen-demo.md", effort("active", "2026-Q4-G1", null));
      run("add", "Fix", "bug", "project:Alpha", "--effort", "infinijepsen-demo");

      Result result = run("effort", "info", "infinijepsen-demo", "--human");

      assertThat(result.exitCode()).isZero();
      assertThat(result.out()).contains("infinijepsen-demo", "2026-Q4-G1", "Fix found data loss bugs", "2026-G1",
            "Fix bug");
   }

   /**
    * @param status the effort's status, e.g. {@code active}.
    * @param goal the effort's quarterly goal.
    * @param closed the day it closed; {@code null} when it is open.
    * @return an effort note, as {@code effort new} writes it.
    */
   private static String effort(String status, String goal, String closed) {
      return """
            ---
            title: InfiniJepsen demo
            status: %s
            %sgoal: %s
            date: 2026-10-05T09:00:00+01:00
            ---

            ## Context
            """.formatted(status, closed == null ? "" : "closed: " + closed + "\n", goal);
   }

   /**
    * @param title the goal's title.
    * @param status the goal's status, e.g. {@code open}.
    * @param parent the yearly goal of a quarterly goal; {@code null} for a yearly goal.
    * @return a goal note, as {@code goal new} writes it.
    */
   private static String goal(String title, String status, String parent) {
      return """
            ---
            title: %s
            status: %s
            %sdate: 2026-01-05T09:00:00+01:00
            ---

            ## Summary
            """.formatted(title, status, parent == null ? "" : "parent: " + parent + "\n");
   }

   /**
    * Writes a note by hand, as a user or agent would, creating its folders.
    *
    * @param relative the note's path relative to {@code data.location}, e.g. {@code efforts/infinijepsen-demo.md}.
    * @param content the whole file, frontmatter included.
    * @return the absolute path of the note.
    * @throws IOException when the note cannot be written.
    */
   private Path note(String relative, String content) throws IOException {
      Path note = taskwarrior.dataLocation().resolve(relative);
      Files.createDirectories(note.getParent());
      return Files.writeString(note, content);
   }

   /**
    * @param efforts the {@code efforts} array of a JSON result.
    * @return the node IDs, in order.
    */
   private static List<String> ids(JsonNode efforts) {
      return efforts.valueStream().map(effort -> effort.path("id").asString()).toList();
   }

   /**
    * @param reference the task ID or UUID.
    * @return the task as {@code ztk info} prints it, in the common task shape.
    */
   private JsonNode task(String reference) {
      return JSON.readTree(run("info", reference).out());
   }

   private Result run(String... args) {
      return CliHarness.run(taskwarrior.environment(), false, args);
   }

   private static JsonNode frontmatter(Path note) throws IOException {
      String content = Files.readString(note);
      return YAMLMapper.shared().readTree(content.substring(4, content.indexOf("\n---\n", 4)));
   }
}
