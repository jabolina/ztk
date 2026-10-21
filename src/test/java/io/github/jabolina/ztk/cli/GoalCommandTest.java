package io.github.jabolina.ztk.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Year;
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
 * Creating and managing goals from the command line.
 */
class GoalCommandTest {

   private static final JsonMapper JSON = JsonMapper.shared();

   @TempDir
   Path root;

   TestTaskwarrior taskwarrior;
   Path goals;

   @BeforeEach
   void setUp() {
      taskwarrior = TestTaskwarrior.create(root);
      goals = taskwarrior.dataLocation().resolve("goals");
   }

   @Test
   void goalNewCreatesTheYearlyGoalFromItsTemplateAndPrintsItsPath() throws IOException {
      Result result = run("goal", "new", "2026", "--title", "Infinispan provably correct", "--human");

      assertThat(result.exitCode()).isZero();
      Path goal = goals.resolve("2026/2026-G1-infinispan-provably-correct.md");
      // Only the bare path on stdout, so it can be used in shell substitutions.
      assertThat(result.out()).isEqualTo(goal + System.lineSeparator());
      assertThat(result.err()).contains("2026-G1");

      JsonNode frontmatter = frontmatter(goal);
      assertThat(frontmatter.path("title").asString()).isEqualTo("Infinispan provably correct");
      assertThat(frontmatter.path("status").asString()).isEqualTo("open");
      assertThat(frontmatter.path("date").asString()).startsWith("20");
      // A new goal is not closed, and the ID lives in the file name only.
      assertThat(frontmatter.has("closed")).isFalse();
      assertThat(frontmatter.has("parent")).isFalse();
      assertThat(Files.readString(goal)).containsSubsequence(
            "## Summary", "## Why it matters", "## Agreement", "## What success looks like",
            "## Out of scope", "## Open questions", "## Retrospective");
   }

   @Test
   void goalNewPrintsTheGoalIdAndPathAsJsonWhenPiped() {
      Result result = run("goal", "new", "2026", "--title", "Infinispan provably correct");

      assertThat(result.exitCode()).isZero();
      JsonNode json = JSON.readTree(result.out());
      assertThat(json.path("schema").asInt()).isEqualTo(1);
      // The node ID graph and search print, so the result can be fed straight back to them.
      assertThat(json.path("id").asString()).isEqualTo("goal:2026-G1");
      assertThat(json.path("path").asString())
            .isEqualTo(goals.resolve("2026/2026-G1-infinispan-provably-correct.md").toString());
   }

   @Test
   void goalNewNumbersAfterTheHighestGoalOfTheYear() throws IOException {
      Path year = Files.createDirectories(goals.resolve("2026"));
      Files.writeString(year.resolve("2026-G1-first.md"), "");
      // A dropped or deleted goal keeps its number: the gap at G2 is never filled.
      Files.writeString(year.resolve("2026-G3-third.md"), "");
      // Neither material nor quarterly goals nor other years take part in the year's numbering.
      Files.createDirectories(year.resolve("kickoff"));
      Files.writeString(year.resolve("kickoff/2026-G7-draft.md"), "");
      Files.createDirectories(year.resolve("Q4"));
      Files.writeString(year.resolve("Q4/2026-Q4-G8-quarter.md"), "");
      Files.createDirectories(goals.resolve("2025"));
      Files.writeString(goals.resolve("2025/2025-G9-last-year.md"), "");

      Result result = run("goal", "new", "2026", "--title", "Fourth", "--human");

      assertThat(result.exitCode()).isZero();
      assertThat(result.out().strip()).isEqualTo(year.resolve("2026-G4-fourth.md").toString());
   }

   @Test
   void goalNewNamesTheFileAfterTheGivenSlug() {
      Result result = run("goal", "new", "2026", "--title", "Infinispan provably correct",
            "--slug", "provably-correct", "--human");

      assertThat(result.exitCode()).isZero();
      Path goal = goals.resolve("2026/2026-G1-provably-correct.md");
      assertThat(result.out().strip()).isEqualTo(goal.toString());
   }

   @Test
   void goalNewCutsLongTitlesToSixtyCharactersInTheFileName() {
      Result result = run("goal", "new", "2026", "--title",
            "Make every cache operation in Infinispan provably correct a hundred percent of the time",
            "--human");

      assertThat(result.exitCode()).isZero();
      String name = Path.of(result.out().strip()).getFileName().toString();
      String slug = name.substring("2026-G1-".length(), name.length() - ".md".length());
      // The 60th character is the dash after "a": cut there, without leaving it dangling at the end.
      assertThat(slug).isEqualTo("make-every-cache-operation-in-infinispan-provably-correct-a");
   }

   @Test
   void goalNewRefusesASlugOutsideLowercaseLettersDigitsAndDashes() {
      Result result = run("goal", "new", "2026", "--title", "Provably correct", "--slug", "Provably Correct");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.USAGE.exitCode());
      // Suggests a valid slug.
      assertThat(result.err()).contains("--slug provably-correct");
      assertThat(goals).doesNotExist();
   }

   @Test
   void goalNewRefusesATitleWithoutLettersOrDigitsWhenNoSlugIsGiven() {
      Result result = run("goal", "new", "2026", "--title", "???");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.USAGE.exitCode());
      assertThat(result.err()).contains("--slug");
      assertThat(goals).doesNotExist();
   }

   @Test
   void goalNewCreatesAQuarterlyGoalUnderItsYearlyParent() throws IOException {
      run("goal", "new", "2026", "--title", "Infinispan provably correct");

      Result result = run("goal", "new", "2026-Q4", "--title", "Fix found data loss bugs", "--parent", "2026-G1", "--human");

      assertThat(result.exitCode()).isZero();
      Path goal = goals.resolve("2026/Q4/2026-Q4-G1-fix-found-data-loss-bugs.md");
      assertThat(result.out()).isEqualTo(goal + System.lineSeparator());
      JsonNode frontmatter = frontmatter(goal);
      assertThat(frontmatter.path("title").asString()).isEqualTo("Fix found data loss bugs");
      assertThat(frontmatter.path("status").asString()).isEqualTo("open");
      assertThat(frontmatter.path("parent").asString()).isEqualTo("2026-G1");
      assertThat(Files.readString(goal)).containsSubsequence(
            "## Summary", "## Contribution", "## Done when", "## Out of scope", "## Risks and dependencies",
            "## Retrospective");
   }

   @Test
   void goalNewNumbersQuarterlyGoalsWithinTheirQuarter() {
      run("goal", "new", "2026", "--title", "Infinispan provably correct");
      // Another quarter, and the yearly goals, do not take part in the quarter's numbering.
      run("goal", "new", "2026-Q3", "--title", "Reproduce data loss", "--parent", "2026-G1");
      run("goal", "new", "2026-Q4", "--title", "Fix found data loss bugs", "--parent", "2026-G1");

      Result result = run("goal", "new", "2026-Q4", "--title", "Demo the fixes", "--parent", "2026-G1");

      assertThat(result.exitCode()).isZero();
      JsonNode json = JSON.readTree(result.out());
      assertThat(json.path("id").asString()).isEqualTo("goal:2026-Q4-G2");
      assertThat(json.path("path").asString())
            .isEqualTo(goals.resolve("2026/Q4/2026-Q4-G2-demo-the-fixes.md").toString());
   }

   @ParameterizedTest
   @ValueSource(strings = { "2026-Q5", "26", "2026Q4", "2026-q4" })
   void goalNewRefusesAPeriodThatIsNeitherAYearNorAQuarter(String period) {
      Result result = run("goal", "new", period, "--title", "Anything");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.USAGE.exitCode());
      assertThat(result.err()).contains("e.g. 2026 or 2026-Q4");
      assertThat(goals).doesNotExist();
   }

   @Test
   void goalNewRefusesAQuarterlyGoalWithoutParent() {
      run("goal", "new", "2026", "--title", "Infinispan provably correct");

      Result result = run("goal", "new", "2026-Q4", "--title", "Fix found data loss bugs");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.USAGE.exitCode());
      // Names the yearly goal it could be.
      assertThat(result.err()).contains("--parent").contains("2026-G1");
      assertThat(goals.resolve("2026/Q4")).doesNotExist();
   }

   @Test
   void goalNewRefusesAParentForAYearlyGoal() {
      Result result = run("goal", "new", "2026", "--title", "Infinispan provably correct", "--parent", "2025-G1");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.USAGE.exitCode());
      assertThat(result.err()).contains("without --parent");
      assertThat(goals).doesNotExist();
   }

   @Test
   void goalNewRefusesAParentThatIsNotAYearlyGoalOfTheSameYear() {
      run("goal", "new", "2025", "--title", "Last year");
      run("goal", "new", "2026", "--title", "Infinispan provably correct");

      Result otherYear = run("goal", "new", "2026-Q4", "--title", "Fix bugs", "--parent", "2025-G1");
      Result unknown = run("goal", "new", "2026-Q4", "--title", "Fix bugs", "--parent", "2026-G7");

      for (Result result : new Result[] { otherYear, unknown }) {
         assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.VALIDATION.exitCode());
         // Lists the yearly goals that could be the parent.
         assertThat(result.err()).contains("2026-G1").contains("Infinispan provably correct");
      }
      assertThat(goals.resolve("2026/Q4")).doesNotExist();
   }

   @Test
   void goalNewRefusesAParentInAYearWithoutGoalsPointingToCreatingOne() {
      Result result = run("goal", "new", "2026-Q4", "--title", "Fix bugs", "--parent", "2026-G1");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.VALIDATION.exitCode());
      assertThat(result.err()).contains("ztk goal new 2026 --title");
      assertThat(goals).doesNotExist();
   }

   @Test
   void goalNewRefusesAClosedParentPointingToReopeningIt() throws IOException {
      Path year = Files.createDirectories(goals.resolve("2026"));
      Files.writeString(year.resolve("2026-G1-reached.md"), """
            ---
            title: Reached already
            status: reached
            closed: 2026-09-30
            ---
            """);

      Result result = run("goal", "new", "2026-Q4", "--title", "Fix bugs", "--parent", "2026-G1");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.VALIDATION.exitCode());
      assertThat(result.err()).contains("ztk goal set 2026-G1 --status open");
      assertThat(year.resolve("Q4")).doesNotExist();
   }

   @Test
   void goalSetStatusClosesTheGoalAndLeavesEverythingElseAsItWas() throws IOException {
      String body = """

            ## Summary
            Written by hand, with a [[wikilink]] and a line ending in two spaces.\s\s

            ---

            A horizontal rule above must not read as frontmatter.
            """;
      Path goal = goal("2026/2026-G1-provably-correct.md", """
            ---
            title: Infinispan provably correct
            status: open
            date: 2026-01-05T09:00:00+01:00
            owner: someone
            ---
            """ + body);

      Result result = run("goal", "set", "2026-G1", "--status", "reached", "--human");

      assertThat(result.exitCode()).isZero();
      // The node ID, as graph and search print it.
      assertThat(result.out()).isEqualTo("goal:2026-G1" + System.lineSeparator());
      JsonNode frontmatter = frontmatter(goal);
      assertThat(frontmatter.path("status").asString()).isEqualTo("reached");
      assertThat(frontmatter.path("closed").asString()).isEqualTo(LocalDate.now().toString());
      // Fields ztk does not know are kept, and the body is untouched, byte for byte.
      assertThat(frontmatter.path("title").asString()).isEqualTo("Infinispan provably correct");
      assertThat(frontmatter.path("owner").asString()).isEqualTo("someone");
      assertThat(Files.readString(goal)).endsWith("\n---\n" + body);
   }

   @Test
   void goalSetStatusOpenReopensTheGoalRemovingClosed() throws IOException {
      Path goal = goal("2026/2026-G1-reached.md", closedGoal("Reached already"));

      Result result = run("goal", "set", "2026-G1", "--status", "open");

      assertThat(result.exitCode()).isZero();
      JsonNode frontmatter = frontmatter(goal);
      assertThat(frontmatter.path("status").asString()).isEqualTo("open");
      assertThat(frontmatter.has("closed")).isFalse();
   }

   @Test
   void goalSetTheSameClosedStatusKeepsTheDateItClosed() throws IOException {
      Path goal = goal("2026/2026-G1-reached.md", closedGoal("Reached already"));

      Result result = run("goal", "set", "2026-G1", "--status", "reached");

      assertThat(result.exitCode()).isZero();
      // Closed when it last moved to a closed status, not when it was last set.
      assertThat(frontmatter(goal).path("closed").asString()).isEqualTo("2026-09-30");
   }

   @Test
   void goalSetRefusesToCloseAGoalWithOpenGoalsUnderItListingEach() throws IOException {
      Path yearly = goal("2026/2026-G1-provably-correct.md", goalOf("open", null));
      goal("2026/Q4/2026-Q4-G1-fix-bugs.md", goalOf("open", "2026-G1"));
      goal("2026/Q4/2026-Q4-G2-write-docs.md", goalOf("open", "2026-G1"));
      // Settled already: it does not stand in the way.
      goal("2026/Q3/2026-Q3-G1-tests.md", goalOf("missed", "2026-G1"));

      // Any closed status: dropping a goal is a decision about its goals too.
      Result result = run("goal", "set", "2026-G1", "--status", "dropped");

      // Closing is a review: each open goal under it is named, with how to settle it.
      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.VALIDATION.exitCode());
      assertThat(result.err())
            .contains("2026-Q4-G1", "2026-Q4-G2")
            .contains("ztk goal set 2026-Q4-G1 --status <reached|missed|dropped>")
            .contains("ztk goal set 2026-Q4-G2 --parent <goal ID>")
            .doesNotContain("2026-Q3-G1");
      assertThat(frontmatter(yearly).path("status").asString()).isEqualTo("open");

      // Once each is settled, it closes.
      run("goal", "set", "2026-Q4-G1", "--status", "reached");
      run("goal", "set", "2026-Q4-G2", "--status", "dropped");
      Result settled = run("goal", "set", "2026-G1", "--status", "reached");

      assertThat(settled.exitCode()).as(settled.err()).isZero();
      assertThat(frontmatter(yearly).path("status").asString()).isEqualTo("reached");
   }

   @Test
   void goalSetRefusesToCloseAQuarterlyGoalWithOpenEffortsListingEach() throws IOException {
      goal("2026/2026-G1-provably-correct.md", goalOf("open", null));
      Path quarterly = goal("2026/Q4/2026-Q4-G1-fix-bugs.md", goalOf("open", "2026-G1"));
      effort("write-the-guide", "active", "2026-Q4-G1");
      effort("next-demo", "planned", "2026-Q4-G1");
      effort("old-demo", "done", "2026-Q4-G1");

      Result result = run("goal", "set", "2026-Q4-G1", "--status", "reached");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.VALIDATION.exitCode());
      assertThat(result.err())
            .contains("write-the-guide", "next-demo")
            .contains("ztk effort set write-the-guide --status <done|dropped>")
            .contains("ztk effort set next-demo --goal <goal ID>")
            .doesNotContain("old-demo");
      assertThat(frontmatter(quarterly).path("status").asString()).isEqualTo("open");
   }

   @Test
   void goalSetTitleChangesOnlyTheTitleAndAcceptsTheNodeId() throws IOException {
      Path goal = Path.of(run("goal", "new", "2026", "--title", "Provably correct", "--human").out().strip());

      // The node ID, as JSON results print it, so an agent can feed it straight back.
      Result result = run("goal", "set", "goal:2026-G1", "--title", "Infinispan provably correct");

      assertThat(result.exitCode()).isZero();
      JsonNode json = JSON.readTree(result.out());
      assertThat(json.path("id").asString()).isEqualTo("goal:2026-G1");
      // The file keeps its name: tasks, efforts and wikilinks point to it.
      assertThat(json.path("path").asString()).isEqualTo(goal.toString());
      assertThat(frontmatter(goal).path("title").asString()).isEqualTo("Infinispan provably correct");
      assertThat(frontmatter(goal).path("status").asString()).isEqualTo("open");
   }

   @Test
   void goalSetParentMovesAQuarterlyGoalToAnotherYearlyGoal() throws IOException {
      run("goal", "new", "2026", "--title", "Infinispan provably correct");
      run("goal", "new", "2026", "--title", "Grow the community");
      Path quarterly = Path.of(run("goal", "new", "2026-Q4", "--title", "Fix bugs", "--parent", "2026-G1", "--human")
            .out().strip());

      Result result = run("goal", "set", "2026-Q4-G1", "--parent", "2026-G2");

      assertThat(result.exitCode()).isZero();
      assertThat(frontmatter(quarterly).path("parent").asString()).isEqualTo("2026-G2");
   }

   @Test
   void goalSetRefusesAParentThatCouldNotBeTheGoalsParent() throws IOException {
      run("goal", "new", "2026", "--title", "Infinispan provably correct");
      goal("2026/2026-G2-reached.md", closedGoal("Reached already"));
      Path quarterly = Path.of(run("goal", "new", "2026-Q4", "--title", "Fix bugs", "--parent", "2026-G1", "--human")
            .out().strip());
      String before = Files.readString(quarterly);

      Result closed = run("goal", "set", "2026-Q4-G1", "--parent", "2026-G2");
      Result unknown = run("goal", "set", "2026-Q4-G1", "--parent", "2026-G9");
      Result yearly = run("goal", "set", "2026-G1", "--parent", "2026-G2");

      assertThat(closed.exitCode()).isEqualTo(ZtkException.Kind.VALIDATION.exitCode());
      assertThat(closed.err()).contains("ztk goal set 2026-G2 --status open");
      assertThat(unknown.exitCode()).isEqualTo(ZtkException.Kind.VALIDATION.exitCode());
      // As check reports it: how to point it at a yearly goal of its year, and where to find them.
      assertThat(unknown.err()).contains("2026-G9", "ztk goal set 2026-Q4-G1 --parent <goal ID>", "ztk goal list 2026");
      assertThat(yearly.exitCode()).isEqualTo(ZtkException.Kind.USAGE.exitCode());
      assertThat(yearly.err()).contains("without --parent");
      // A refused change leaves the note as it was.
      assertThat(quarterly).hasContent(before);
   }

   @ParameterizedTest
   @ValueSource(strings = { "2026-G9", "2026-Q4-G1", "provably-correct" })
   void goalSetOnAGoalThatDoesNotExistExitsNotFound(String id) {
      run("goal", "new", "2026", "--title", "Provably correct");

      Result result = run("goal", "set", id, "--status", "reached");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.NOT_FOUND.exitCode());
      assertThat(result.err()).contains("ztk goal list");
   }

   @Test
   void goalSetRefusesAnUnknownStatusListingTheValidOnes() {
      run("goal", "new", "2026", "--title", "Provably correct");

      Result result = run("goal", "set", "2026-G1", "--status", "done");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.USAGE.exitCode());
      assertThat(result.err()).contains("open, reached, missed, dropped");
   }

   @Test
   void goalSetWithNothingToChangeIsAUsageError() {
      run("goal", "new", "2026", "--title", "Provably correct");

      Result result = run("goal", "set", "2026-G1");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.USAGE.exitCode());
      assertThat(result.err()).contains("--status", "--title", "--parent");
   }

   @Test
   void goalListPrintsTheYearsGoalsEachYearlyGoalFollowedByItsQuarterlyGoals() throws IOException {
      run("goal", "new", "2026", "--title", "Infinispan provably correct");
      run("goal", "new", "2026", "--title", "Grow the community");
      run("goal", "new", "2026-Q4", "--title", "Talk at a conference", "--parent", "2026-G2");
      run("goal", "new", "2026-Q4", "--title", "Fix found data loss bugs", "--parent", "2026-G1");
      run("goal", "new", "2026-Q1", "--title", "Reproduce data loss", "--parent", "2026-G1");
      // Numbers order as numbers: G10 after G2.
      goal("2026/2026-G10-tenth.md", """
            ---
            title: Tenth
            status: open
            ---
            """);
      // Another year is not listed.
      run("goal", "new", "2025", "--title", "Last year");

      Result result = run("goal", "list", "2026");

      assertThat(result.exitCode()).isZero();
      JsonNode listed = JSON.readTree(result.out()).path("goals");
      assertThat(ids(listed)).containsExactly(
            "goal:2026-G1", "goal:2026-Q1-G1", "goal:2026-Q4-G2", "goal:2026-G2", "goal:2026-Q4-G1", "goal:2026-G10");
      JsonNode yearly = listed.get(0);
      assertThat(yearly.path("title").asString()).isEqualTo("Infinispan provably correct");
      assertThat(yearly.path("status").asString()).isEqualTo("open");
      assertThat(yearly.path("closed").isNull()).isTrue();
      assertThat(yearly.path("parent").isNull()).isTrue();
      assertThat(yearly.path("path").asString())
            .isEqualTo(goals.resolve("2026/2026-G1-infinispan-provably-correct.md").toString());
      assertThat(yearly.path("needsClosing").asBoolean()).isFalse();
      // The parent as a node ID, like the goal's own.
      assertThat(listed.get(1).path("parent").asString()).isEqualTo("goal:2026-G1");
   }

   @Test
   void goalListOfAQuarterListsItsGoalsWithTheirYearlyParents() {
      run("goal", "new", "2026", "--title", "Infinispan provably correct");
      run("goal", "new", "2026", "--title", "Grow the community");
      run("goal", "new", "2026", "--title", "Not a parent this quarter");
      run("goal", "new", "2026-Q4", "--title", "Talk at a conference", "--parent", "2026-G2");
      run("goal", "new", "2026-Q4", "--title", "Fix found data loss bugs", "--parent", "2026-G1");
      run("goal", "new", "2026-Q1", "--title", "Reproduce data loss", "--parent", "2026-G1");

      Result result = run("goal", "list", "2026-Q4");

      assertThat(result.exitCode()).isZero();
      assertThat(ids(JSON.readTree(result.out()).path("goals")))
            .containsExactly("goal:2026-G1", "goal:2026-Q4-G2", "goal:2026-G2", "goal:2026-Q4-G1");
   }

   @Test
   void goalListDefaultsToTheCurrentYear() {
      Year now = Year.now();
      run("goal", "new", now.toString(), "--title", "This year");
      run("goal", "new", now.minusYears(1).toString(), "--title", "Last year");

      Result result = run("goal", "list");

      assertThat(result.exitCode()).isZero();
      assertThat(ids(JSON.readTree(result.out()).path("goals"))).containsExactly("goal:" + now + "-G1");
   }

   @Test
   void goalListWithStatusKeepsTheParentsOfTheGoalsItLists() throws IOException {
      goal("2026/2026-G1-reached.md", closedGoal("Reached already"));
      run("goal", "new", "2026", "--title", "Grow the community");
      goal("2026/Q3/2026-Q3-G1-talk.md", """
            ---
            title: Talk at a conference
            status: reached
            closed: 2026-09-15
            parent: 2026-G2
            ---
            """);
      run("goal", "new", "2026-Q4", "--title", "Write a blog post", "--parent", "2026-G2");

      Result result = run("goal", "list", "2026", "--status", "reached");

      assertThat(result.exitCode()).isZero();
      JsonNode listed = JSON.readTree(result.out()).path("goals");
      // 2026-G2 is open: listed only as the parent of a reached goal.
      assertThat(ids(listed)).containsExactly("goal:2026-G1", "goal:2026-G2", "goal:2026-Q3-G1");
      assertThat(listed.get(0).path("closed").asString()).isEqualTo("2026-09-30");
      assertThat(listed.get(1).path("status").asString()).isEqualTo("open");
   }

   @Test
   void goalListMarksOpenGoalsWhosePeriodEndedAsNeedingClosing() throws IOException {
      goal("2020/2020-G1-long-ago.md", """
            ---
            title: Long ago
            status: open
            ---
            """);
      goal("2020/Q2/2020-Q2-G1-a-quarter-long-ago.md", """
            ---
            title: A quarter long ago
            status: open
            parent: 2020-G1
            ---
            """);
      goal("2020/2020-G2-reached.md", """
            ---
            title: Reached
            status: reached
            closed: 2020-12-01
            ---
            """);

      Result json = run("goal", "list", "2020");
      Result human = run("goal", "list", "2020", "--human");

      assertThat(json.exitCode()).isZero();
      JsonNode listed = JSON.readTree(json.out()).path("goals");
      assertThat(ids(listed)).containsExactly("goal:2020-G1", "goal:2020-Q2-G1", "goal:2020-G2");
      assertThat(listed.valueStream().map(goal -> goal.path("needsClosing").asBoolean())).containsExactly(true, true, false);
      // People see quarterly goals under their yearly goal, and what needs closing.
      assertThat(human.out().lines())
            .anySatisfy(line -> assertThat(line).startsWith("2020-G1").contains("needs closing"))
            .anySatisfy(line -> assertThat(line).startsWith("  2020-Q2-G1").contains("needs closing"))
            .anySatisfy(line -> assertThat(line).startsWith("2020-G2").doesNotContain("needs closing"));
   }

   @Test
   void goalListOfAPeriodWithoutGoalsIsEmptyAndPointsToCreatingOne() {
      Result json = run("goal", "list", "2030");
      Result human = run("goal", "list", "2030", "--human");

      assertThat(json.exitCode()).isZero();
      assertThat(JSON.readTree(json.out()).path("goals").isEmpty()).isTrue();
      assertThat(human.exitCode()).isZero();
      assertThat(human.out()).contains("ztk goal new 2030 --title");
   }

   @Test
   void goalListRefusesAnUnknownPeriodOrStatus() {
      Result period = run("goal", "list", "2026-Q5");
      Result status = run("goal", "list", "2026", "--status", "done");

      assertThat(period.exitCode()).isEqualTo(ZtkException.Kind.USAGE.exitCode());
      assertThat(period.err()).contains("e.g. 2026 or 2026-Q4");
      assertThat(status.exitCode()).isEqualTo(ZtkException.Kind.USAGE.exitCode());
      assertThat(status.err()).contains("open, reached, missed, dropped");
   }

   @Test
   void goalInfoOfAYearlyGoalShowsItWithItsQuarterlyGoals() {
      run("goal", "new", "2026", "--title", "Infinispan provably correct");
      run("goal", "new", "2026", "--title", "Grow the community");
      run("goal", "new", "2026-Q4", "--title", "Fix found data loss bugs", "--parent", "2026-G1");
      run("goal", "new", "2026-Q4", "--title", "Talk at a conference", "--parent", "2026-G2");
      run("goal", "new", "2026-Q1", "--title", "Reproduce data loss", "--parent", "2026-G1");

      Result result = run("goal", "info", "2026-G1");

      assertThat(result.exitCode()).isZero();
      JsonNode json = JSON.readTree(result.out());
      assertThat(json.path("schema").asInt()).isEqualTo(1);
      assertThat(json.path("id").asString()).isEqualTo("goal:2026-G1");
      assertThat(json.path("title").asString()).isEqualTo("Infinispan provably correct");
      assertThat(json.path("status").asString()).isEqualTo("open");
      assertThat(json.path("parent").isNull()).isTrue();
      assertThat(json.path("path").asString())
            .isEqualTo(goals.resolve("2026/2026-G1-infinispan-provably-correct.md").toString());
      // Its quarterly goals only, by quarter.
      assertThat(ids(json.path("goals"))).containsExactly("goal:2026-Q1-G1", "goal:2026-Q4-G1");
      assertThat(json.path("goals").get(0).path("parent").asString()).isEqualTo("goal:2026-G1");
   }

   @Test
   void goalInfoOfAQuarterlyGoalNamesItsParentAndHasNoGoals() {
      run("goal", "new", "2026", "--title", "Infinispan provably correct");
      run("goal", "new", "2026-Q4", "--title", "Fix found data loss bugs", "--parent", "2026-G1");

      // The node ID, as JSON results print it.
      Result result = run("goal", "info", "goal:2026-Q4-G1");

      assertThat(result.exitCode()).isZero();
      JsonNode json = JSON.readTree(result.out());
      assertThat(json.path("id").asString()).isEqualTo("goal:2026-Q4-G1");
      assertThat(json.path("parent").asString()).isEqualTo("goal:2026-G1");
      // Every goal has the same fields.
      assertThat(json.path("goals").isArray()).isTrue();
      assertThat(json.path("goals").isEmpty()).isTrue();
   }

   @Test
   void goalInfoShowsPeopleTheGoalAboveItsQuarterlyGoals() {
      run("goal", "new", "2026", "--title", "Infinispan provably correct");
      run("goal", "new", "2026-Q4", "--title", "Fix found data loss bugs", "--parent", "2026-G1");

      Result result = run("goal", "info", "2026-G1", "--human");

      assertThat(result.exitCode()).isZero();
      assertThat(result.out().lines())
            .anySatisfy(line -> assertThat(line).startsWith("2026-G1").contains("Infinispan provably correct"))
            .anySatisfy(line -> assertThat(line).startsWith("  2026-Q4-G1").contains("Fix found data loss bugs"));
   }

   @Test
   void goalListCountsTheEffortsOfEachGoalAndOfAYearlyGoalThroughItsQuarterlyGoals() throws IOException {
      run("goal", "new", "2026", "--title", "Infinispan provably correct");
      run("goal", "new", "2026-Q3", "--title", "Fix bugs", "--parent", "2026-G1");
      run("goal", "new", "2026-Q4", "--title", "Fix found data loss bugs", "--parent", "2026-G1");
      effort("summer-demo", "done", "2026-Q3-G1");
      effort("infinijepsen-demo", "active", "2026-Q4-G1");
      effort("raft-demo", "dropped", "2026-Q4-G1");

      JsonNode year = JSON.readTree(run("goal", "list", "2026").out()).path("goals");
      JsonNode quarter = JSON.readTree(run("goal", "list", "2026-Q4").out()).path("goals");

      // Any status: closed efforts are part of what the goal got.
      assertThat(year.valueStream().map(goal -> goal.path("id").asString() + " " + goal.path("efforts").asInt()))
            .containsExactly("goal:2026-G1 3", "goal:2026-Q3-G1 1", "goal:2026-Q4-G1 2");
      // A quarter counts, under the yearly goal, only the quarterly goals it lists.
      assertThat(quarter.valueStream().map(goal -> goal.path("id").asString() + " " + goal.path("efforts").asInt()))
            .containsExactly("goal:2026-G1 2", "goal:2026-Q4-G1 2");
   }

   @Test
   void goalInfoOfAQuarterlyGoalShowsItsEffortsWithTheirTasksCounted() throws IOException {
      run("goal", "new", "2026", "--title", "Infinispan provably correct");
      run("goal", "new", "2026-Q3", "--title", "Fix bugs", "--parent", "2026-G1");
      run("goal", "new", "2026-Q4", "--title", "Fix found data loss bugs", "--parent", "2026-G1");
      effort("raft-demo", "done", "2026-Q4-G1");
      effort("infinijepsen-demo", "active", "2026-Q4-G1");
      effort("summer-demo", "done", "2026-Q3-G1");
      taskwarrior.task("rc.uda.effort.type=string", "add", "Fix bug", "project:Alpha", "effort:infinijepsen-demo");

      JsonNode info = JSON.readTree(run("goal", "info", "2026-Q4-G1").out());

      JsonNode efforts = info.path("efforts");
      // As effort list shows them, by slug.
      assertThat(efforts.valueStream().map(effort -> effort.path("id").asString()))
            .containsExactly("effort:infinijepsen-demo", "effort:raft-demo");
      assertThat(efforts.get(0).path("status").asString()).isEqualTo("active");
      assertThat(efforts.get(0).path("tasks").path("pending").asInt()).isEqualTo(1);
      assertThat(efforts.get(1).path("tasks").path("pending").asInt()).isZero();
      assertThat(info.path("goals").isEmpty()).isTrue();
   }

   @Test
   void goalInfoOfAYearlyGoalShowsTheEffortsUnderEachOfItsQuarterlyGoals() throws IOException {
      run("goal", "new", "2026", "--title", "Infinispan provably correct");
      run("goal", "new", "2026-Q3", "--title", "Fix bugs", "--parent", "2026-G1");
      run("goal", "new", "2026-Q4", "--title", "Fix found data loss bugs", "--parent", "2026-G1");
      effort("summer-demo", "done", "2026-Q3-G1");
      effort("infinijepsen-demo", "active", "2026-Q4-G1");

      JsonNode info = JSON.readTree(run("goal", "info", "2026-G1").out());

      // Efforts move quarterly goals forward, so a yearly goal reaches them through its quarterly goals.
      assertThat(info.path("efforts").isEmpty()).isTrue();
      JsonNode goals = info.path("goals");
      assertThat(ids(goals)).containsExactly("goal:2026-Q3-G1", "goal:2026-Q4-G1");
      assertThat(ids(goals.get(0).path("efforts"))).containsExactly("effort:summer-demo");
      assertThat(ids(goals.get(1).path("efforts"))).containsExactly("effort:infinijepsen-demo");
   }

   @Test
   void goalInfoShowsPeopleTheEffortsOfItsGoals() throws IOException {
      run("goal", "new", "2026", "--title", "Infinispan provably correct");
      run("goal", "new", "2026-Q4", "--title", "Fix found data loss bugs", "--parent", "2026-G1");
      effort("infinijepsen-demo", "active", "2026-Q4-G1");
      taskwarrior.task("rc.uda.effort.type=string", "add", "Fix bug", "project:Alpha", "effort:infinijepsen-demo");

      Result result = run("goal", "info", "2026-G1", "--human");

      assertThat(result.exitCode()).isZero();
      assertThat(result.out().lines())
            .anySatisfy(line -> assertThat(line).contains("infinijepsen-demo", "2026-Q4-G1", "1 pending, 0 completed"));
   }

   @ParameterizedTest
   @ValueSource(strings = { "2026-G9", "provably-correct" })
   void goalInfoOnAGoalThatDoesNotExistExitsNotFound(String id) {
      run("goal", "new", "2026", "--title", "Provably correct");

      Result result = run("goal", "info", id);

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.NOT_FOUND.exitCode());
      assertThat(result.err()).contains("ztk goal list");
   }

   /**
    * @param goals the {@code goals} array of a JSON result.
    * @return the node IDs, in order.
    */
   private static List<String> ids(JsonNode goals) {
      return goals.valueStream().map(goal -> goal.path("id").asString()).toList();
   }

   /**
    * Writes a goal note by hand, as a user or agent would, creating its folders.
    *
    * @param relative the note's path relative to the goals folder, e.g. {@code 2026/2026-G1-provably-correct.md}.
    * @param content the whole file, frontmatter included.
    * @return the absolute path of the note.
    * @throws IOException when the note cannot be written.
    */
   private Path goal(String relative, String content) throws IOException {
      Path note = goals.resolve(relative);
      Files.createDirectories(note.getParent());
      return Files.writeString(note, content);
   }

   /**
    * Writes an effort note by hand, as {@code effort new} would.
    *
    * @param slug the effort's slug.
    * @param status the effort's status, e.g. {@code active}.
    * @param goal the effort's quarterly goal.
    * @throws IOException when the note cannot be written.
    */
   private void effort(String slug, String status, String goal) throws IOException {
      Path note = taskwarrior.dataLocation().resolve("efforts").resolve(slug + ".md");
      Files.createDirectories(note.getParent());
      Files.writeString(note, """
            ---
            title: %s
            status: %s
            goal: %s
            ---
            """.formatted(slug, status, goal));
   }

   /**
    * @param title the goal's title.
    * @return a yearly goal note reached on 2026-09-30.
    */
   private static String closedGoal(String title) {
      return """
            ---
            title: %s
            status: reached
            closed: 2026-09-30
            date: 2026-01-05T09:00:00+01:00
            ---

            ## Summary
            """.formatted(title);
   }

   /**
    * @param status the goal's status, e.g. {@code open}; a closed one is dated 2026-09-30.
    * @param parent the yearly goal of a quarterly goal; {@code null} for a yearly goal.
    * @return a goal note, as {@code goal new} and {@code goal set} leave it.
    */
   private static String goalOf(String status, String parent) {
      return """
            ---
            title: A goal
            status: %s
            %s%sdate: 2026-01-05T09:00:00+01:00
            ---
            """.formatted(status, status.equals("open") ? "" : "closed: 2026-09-30\n",
            parent == null ? "" : "parent: " + parent + "\n");
   }

   private Result run(String... args) {
      return CliHarness.run(taskwarrior.environment(), false, args);
   }

   private static JsonNode frontmatter(Path note) throws IOException {
      String content = Files.readString(note);
      return YAMLMapper.shared().readTree(content.substring(4, content.indexOf("\n---\n", 4)));
   }
}
