package io.github.jabolina.ztk.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Checking the knowledge base for broken links and tasks from the command line.
 *
 * <p>
 * Notes are written straight into the fixture's knowledge base, as users and agents write them, rather than through
 * ztk: problems come from whatever is there.
 * </p>
 */
class CheckCommandTest {

   private static final JsonMapper JSON = JsonMapper.shared();

   @TempDir
   Path root;

   TestTaskwarrior taskwarrior;

   @BeforeEach
   void setUp() {
      taskwarrior = TestTaskwarrior.create(root);
   }

   @Test
   void aWikilinkToNothingIsADanglingLinkError() throws IOException {
      Path causality = note("zettel/20261015-causality.md", """
            ---
            title: Causality
            ---

            Ordering events needs [[20261016-lamport-clocks]].
            """);

      Result result = CliHarness.run(taskwarrior.environment(), false, "check");

      // Errors fail the run, but the problems are still printed for agents to act on.
      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.VALIDATION.exitCode());
      JsonNode json = JSON.readTree(result.out());
      assertThat(json.path("schema").asInt()).isEqualTo(1);
      JsonNode problems = json.path("problems");
      assertThat(problems).hasSize(1);

      JsonNode dangling = problems.get(0);
      assertThat(dangling.path("code").asString()).isEqualTo("dangling-link");
      assertThat(dangling.path("severity").asString()).isEqualTo("error");
      assertThat(dangling.path("id").asString()).isEqualTo("note:zettel/20261015-causality");
      assertThat(dangling.path("path").asString()).isEqualTo(causality.toString());
      // Counted from the top of the file, frontmatter included, so an editor opens the right line.
      assertThat(dangling.path("line").asInt()).isEqualTo(5);
      assertThat(dangling.path("message").asString()).contains("20261016-lamport-clocks");
      // What to do: write the missing note, or correct the link.
      assertThat(dangling.path("hint").asString()).contains("20261016-lamport-clocks.md");
      // Only ambiguous links have candidates, but every problem has the key.
      assertThat(dangling.has("candidates")).isTrue();
      assertThat(dangling.path("candidates").isNull()).isTrue();

      // People are told how many, and to run it again once fixed.
      assertThat(result.err()).contains("1 error").contains("ztk check");
   }

   @Test
   void aWikilinkToANameSeveralNotesHaveIsAnAmbiguousLinkWarning() throws IOException {
      note("alpha/overview.md", "What Alpha is.\n");
      note("beta/overview.md", "What Beta is.\n");
      // The note of that name in its own folder wins: not ambiguous.
      note("alpha/design.md", "Builds on the [[overview]].\n");
      Path index = note("zettel/index.md", """
            ---
            title: Index
            ---

            Start from the [[overview]].
            """);

      Result result = CliHarness.run(taskwarrior.environment(), false, "check");

      // Warnings alone do not fail the run.
      assertThat(result.exitCode()).isZero();
      assertThat(result.err()).isEmpty();
      JsonNode problems = JSON.readTree(result.out()).path("problems");
      assertThat(problems).hasSize(1);

      JsonNode ambiguous = problems.get(0);
      assertThat(ambiguous.path("code").asString()).isEqualTo("ambiguous-link");
      assertThat(ambiguous.path("severity").asString()).isEqualTo("warning");
      assertThat(ambiguous.path("id").asString()).isEqualTo("note:zettel/index");
      assertThat(ambiguous.path("path").asString()).isEqualTo(index.toString());
      assertThat(ambiguous.path("line").asInt()).isEqualTo(5);
      assertThat(ambiguous.path("message").asString()).contains("overview");
      // What to do: tell the notes apart, choosing among the candidates.
      assertThat(ambiguous.path("hint").asString()).contains("Rename").contains("candidates");
      // The notes it could mean, so an agent can pick one.
      assertThat(ambiguous.path("candidates").valueStream().map(JsonNode::asString))
            .containsExactly("note:alpha/overview", "note:beta/overview");
   }

   @Test
   void anOpenTaskNamingANoteThatDoesNotExistIsAMissingNoteError() {
      taskwarrior.task("rc.uda.note.type=string", "add", "Fix bug", "project:Alpha", "note:Alpha/fix-bug.md");
      String uuid = taskwarrior.task("_get", "1.uuid");
      // Done tasks are history: what they point to no longer matters.
      taskwarrior.task("rc.uda.note.type=string", "add", "Old bug", "project:Alpha", "note:Alpha/old-bug.md");
      taskwarrior.task("rc.confirmation=off", "2", "done");

      Result result = CliHarness.run(taskwarrior.environment(), false, "check");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.VALIDATION.exitCode());
      JsonNode problems = JSON.readTree(result.out()).path("problems");
      assertThat(problems).hasSize(1);

      JsonNode missing = problems.get(0);
      assertThat(missing.path("code").asString()).isEqualTo("missing-note");
      assertThat(missing.path("severity").asString()).isEqualTo("error");
      assertThat(missing.path("id").asString()).isEqualTo("task:" + uuid);
      // The task has no note to open, and nothing on a line is wrong.
      assertThat(missing.path("path").isNull()).isTrue();
      assertThat(missing.path("line").isNull()).isTrue();
      assertThat(missing.path("message").asString()).contains("Fix bug").contains("Alpha/fix-bug.md");
      // What to run: the short UUID names the task however the task numbers change.
      assertThat(missing.path("hint").asString()).contains("task " + uuid.substring(0, 8) + " modify note:");
   }

   @Test
   void anOpenTaskWithoutAProjectIsATaskWithoutProjectError() {
      taskwarrior.task("add", "Fix bug");
      String uuid = taskwarrior.task("_get", "1.uuid");
      // Waiting tasks are open, too.
      taskwarrior.task("add", "Review", "wait:2099-01-01");
      // Done tasks keep the project they had.
      taskwarrior.task("add", "Old bug");
      taskwarrior.task("rc.confirmation=off", "3", "done");

      Result result = CliHarness.run(taskwarrior.environment(), false, "check");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.VALIDATION.exitCode());
      JsonNode problems = JSON.readTree(result.out()).path("problems");
      assertThat(problems.valueStream().map(problem -> problem.path("message").asString()))
            .hasSize(2)
            .anySatisfy(message -> assertThat(message).contains("Fix bug"))
            .anySatisfy(message -> assertThat(message).contains("Review"));

      JsonNode fixBug = problems.valueStream()
            .filter(problem -> problem.path("id").asString().equals("task:" + uuid))
            .findFirst()
            .orElseThrow();
      assertThat(fixBug.path("code").asString()).isEqualTo("task-without-project");
      assertThat(fixBug.path("severity").asString()).isEqualTo("error");
      assertThat(fixBug.path("path").isNull()).isTrue();
      assertThat(fixBug.path("hint").asString()).contains("task " + uuid.substring(0, 8) + " modify project:");
      assertThat(result.err()).contains("2 errors");
   }

   @Test
   void aTaskNoteWhoseTaskIsGoneIsAnOrphanTaskNoteWarning() throws IOException {
      // Deleted tasks are gone for good: their notes are left behind.
      taskwarrior.task("add", "Fix bug", "project:Alpha");
      String uuid = taskwarrior.task("_get", "1.uuid");
      taskwarrior.task("rc.confirmation=off", "1", "delete");
      Path fixBug = note("Alpha/fix-bug.md", """
            ---
            title: Fix bug
            task_uuid: %s
            ---

            # Notes
            """.formatted(uuid));

      Result result = CliHarness.run(taskwarrior.environment(), false, "check");

      assertThat(result.exitCode()).isZero();
      JsonNode problems = JSON.readTree(result.out()).path("problems");
      assertThat(problems).hasSize(1);

      JsonNode orphan = problems.get(0);
      assertThat(orphan.path("code").asString()).isEqualTo("orphan-task-note");
      assertThat(orphan.path("severity").asString()).isEqualTo("warning");
      assertThat(orphan.path("id").asString()).isEqualTo("note:Alpha/fix-bug");
      assertThat(orphan.path("path").asString()).isEqualTo(fixBug.toString());
      // About the frontmatter as a whole.
      assertThat(orphan.path("line").isNull()).isTrue();
      assertThat(orphan.path("message").asString()).contains(uuid);
      assertThat(orphan.path("hint").asString()).contains("task_uuid");
   }

   @Test
   void aNoteThatCannotBeReadIsAnUnreadableNoteWarning() throws IOException {
      // "Raft elects a leader in São Paulo." saved as Latin-1: the "ã" is a byte UTF-8 rejects.
      Path latin1 = taskwarrior.dataLocation().resolve("zettel/20261015-latin1.md");
      Files.createDirectories(latin1.getParent());
      Files.write(latin1, "Raft elects a leader in São Paulo.\n".getBytes(StandardCharsets.ISO_8859_1));

      Result result = CliHarness.run(taskwarrior.environment(), false, "check");

      assertThat(result.exitCode()).isZero();
      JsonNode json = JSON.readTree(result.out());
      JsonNode problems = json.path("problems");
      assertThat(problems).hasSize(1);

      JsonNode unreadable = problems.get(0);
      assertThat(unreadable.path("code").asString()).isEqualTo("unreadable-note");
      assertThat(unreadable.path("severity").asString()).isEqualTo("warning");
      // Never read, so it is no node.
      assertThat(unreadable.path("id").isNull()).isTrue();
      assertThat(unreadable.path("path").asString()).isEqualTo(latin1.toString());
      assertThat(unreadable.path("line").isNull()).isTrue();
      // Why it cannot be read, and how to fix it.
      assertThat(unreadable.path("message").asString()).contains("UTF-8");
      assertThat(unreadable.path("hint").asString()).contains("iconv");
      // Reported once, as a problem, rather than again as a warning.
      assertThat(json.has("warnings")).isFalse();
   }

   @Test
   void problemsAreListedErrorsFirstThenByFileAndLine() throws IOException {
      note("x/overview.md", "What X is.\n");
      note("y/overview.md", "What Y is.\n");
      // First by file name, but only a warning.
      note("a/index.md", "Start from the [[overview]].\n");
      note("c/two.md", "See [[missing]].\n");
      note("b/one.md", """
            See [[gone]].

            And [[nowhere]].
            """);

      Result result = CliHarness.run(taskwarrior.environment(), false, "check");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.VALIDATION.exitCode());
      JsonNode problems = JSON.readTree(result.out()).path("problems");
      assertThat(problems.valueStream()
            .map(problem -> problem.path("id").asString() + ":" + problem.path("line").asInt()))
            .containsExactly("note:b/one:1", "note:b/one:3", "note:c/two:1", "note:a/index:1");
   }

   @Test
   void peopleSeeATableOfTheProblemsAndHowManyErrors() throws IOException {
      Path causality = note("zettel/20261015-causality.md", """
            ---
            title: Causality
            ---

            Ordering events needs [[20261016-lamport-clocks]].
            """);

      Result result = CliHarness.run(taskwarrior.environment(), true, "check");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.VALIDATION.exitCode());
      List<String> lines = result.out().lines().toList();
      assertThat(lines).hasSize(2);
      // Stripped: the table may pad its last column.
      assertThat(lines.get(0).strip()).matches("SEVERITY +CODE +PATH +MESSAGE");
      // The path with its line, as editors open it, and what to do next to what is wrong.
      assertThat(lines.get(1).strip())
            .startsWith("ERROR")
            .contains("dangling-link")
            .contains(causality + ":5")
            .contains("Links to 20261016-lamport-clocks, which no note or task has.")
            .endsWith("Write a note named 20261016-lamport-clocks.md, or correct the link.");
      assertThat(result.err()).contains("1 error");
   }

   @Test
   void codeReportsOnlyThoseCodesAndTheExitCodeFollowsThem() throws IOException {
      note("alpha/overview.md", "What Alpha is.\n");
      note("beta/overview.md", "What Beta is.\n");
      note("zettel/index.md", "Start from the [[overview]], then [[nowhere]].\n");

      // Only the warning: the dangling link is left out, so it no longer fails the run.
      Result result = CliHarness.run(taskwarrior.environment(), false, "check", "--code", "ambiguous-link");

      assertThat(result.exitCode()).isZero();
      JsonNode problems = JSON.readTree(result.out()).path("problems");
      assertThat(problems.valueStream().map(problem -> problem.path("code").asString()))
            .containsExactly("ambiguous-link");
   }

   @Test
   void anUnknownCodeIsRejectedListingTheKnownOnes() {
      Result result = CliHarness.run(taskwarrior.environment(), false, "check", "--code", "dangling");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.USAGE.exitCode());
      assertThat(result.out()).isEmpty();
      assertThat(result.err()).contains("'dangling'")
            .contains("dangling-link", "missing-note", "task-without-project", "ambiguous-link", "orphan-task-note",
                  "unreadable-note", "unknown-effort", "missing-goal", "unknown-goal", "invalid-status",
                  "invalid-goal-name", "duplicate-goal", "needs-closing", "closed-mismatch", "open-under-closed");
   }

   @Test
   void projectReportsOnlyTheProblemsOfItsNotesAndTasks() throws IOException {
      note("Alpha/design.md", "Builds on [[nowhere]].\n");
      // Linked from elsewhere, but not in Alpha.
      note("zettel/index.md", "See [[elsewhere]].\n");
      taskwarrior.task("rc.uda.note.type=string", "add", "Fix bug", "project:Alpha", "note:Alpha/fix-bug.md");
      String uuid = taskwarrior.task("_get", "1.uuid");
      taskwarrior.task("rc.uda.note.type=string", "add", "Other", "project:Beta", "note:Beta/other.md");
      // In no project, so in none asked for.
      taskwarrior.task("add", "Loose");

      Result result = CliHarness.run(taskwarrior.environment(), false, "check", "--project", "Alpha");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.VALIDATION.exitCode());
      JsonNode problems = JSON.readTree(result.out()).path("problems");
      // The task has no note, so its problem has no file: listed after the ones that have.
      assertThat(problems.valueStream()
            .map(problem -> problem.path("code").asString() + " " + problem.path("id").asString()))
            .containsExactly("dangling-link note:Alpha/design", "missing-note task:" + uuid);
   }

   @Test
   void aProjectNoTaskOrFolderHasIsNotFound() throws IOException {
      note("Alpha/design.md", "Alpha.\n");

      Result result = CliHarness.run(taskwarrior.environment(), false, "check", "--project", "Alph");

      // A typo would otherwise look like a project without problems.
      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.NOT_FOUND.exitCode());
      assertThat(result.out()).isEmpty();
      assertThat(result.err()).contains("Alph").contains("task projects");
   }

   @Test
   void aGoalOrEffortWithoutAStatusOfItsKindIsAnInvalidStatusError() throws IOException {
      // An effort's status, not a goal's.
      Path goal = note("goals/2026/2026-G1-provably-correct.md", goal("done", null));
      Path effort = note("efforts/infinijepsen-demo.md", """
            ---
            title: InfiniJepsen demo
            goal: 2026-Q4-G1
            ---
            """);
      note("goals/2026/Q4/2026-Q4-G1-fix-the-bugs.md", goal("open", "2026-G1"));

      Result result = CliHarness.run(taskwarrior.environment(), false, "check", "--code", "invalid-status");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.VALIDATION.exitCode());
      JsonNode problems = JSON.readTree(result.out()).path("problems");
      assertThat(codes(problems)).containsExactlyInAnyOrder("invalid-status goal:2026-G1",
            "invalid-status effort:infinijepsen-demo");

      JsonNode ofGoal = problem(problems, "goal:2026-G1");
      assertThat(ofGoal.path("severity").asString()).isEqualTo("error");
      assertThat(ofGoal.path("path").asString()).isEqualTo(goal.toString());
      assertThat(ofGoal.path("line").isNull()).isTrue();
      assertThat(ofGoal.path("message").asString()).contains("status");
      // The command setting it, with the statuses of its kind.
      assertThat(ofGoal.path("hint").asString()).contains("ztk goal set 2026-G1 --status")
            .contains("open|reached|missed|dropped");

      JsonNode ofEffort = problem(problems, "effort:infinijepsen-demo");
      assertThat(ofEffort.path("path").asString()).isEqualTo(effort.toString());
      assertThat(ofEffort.path("hint").asString()).contains("ztk effort set infinijepsen-demo --status")
            .contains("planned|active|done|dropped");
   }

   @Test
   void anEffortOrQuarterlyGoalPointingToNoGoalIsAMissingGoalError() throws IOException {
      // Yearly goals point to nothing.
      note("goals/2026/2026-G1-provably-correct.md", goal("open", null));
      note("goals/2026/Q4/2026-Q4-G1-fix-the-bugs.md", goal("open", null));
      note("efforts/infinijepsen-demo.md", """
            ---
            title: InfiniJepsen demo
            status: active
            ---
            """);

      Result result = CliHarness.run(taskwarrior.environment(), false, "check", "--code", "missing-goal");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.VALIDATION.exitCode());
      JsonNode problems = JSON.readTree(result.out()).path("problems");
      assertThat(codes(problems)).containsExactlyInAnyOrder("missing-goal goal:2026-Q4-G1",
            "missing-goal effort:infinijepsen-demo");
      // The command pointing it at a goal, and where to find one of the right level.
      assertThat(problem(problems, "goal:2026-Q4-G1").path("hint").asString())
            .contains("yearly goal of 2026")
            .contains("ztk goal set 2026-Q4-G1 --parent")
            .contains("ztk goal list 2026");
      assertThat(problem(problems, "effort:infinijepsen-demo").path("hint").asString())
            .contains("quarterly goal")
            .contains("ztk effort set infinijepsen-demo --goal")
            .contains("ztk goal list");
   }

   @Test
   void aGoalNamedThatIsNotThereOrNotOfTheRightLevelOrYearIsAnUnknownGoalError() throws IOException {
      note("goals/2025/2025-G1-ship-it.md", goal("open", null));
      note("goals/2026/2026-G1-provably-correct.md", goal("open", null));
      // A yearly goal of another year.
      note("goals/2026/Q4/2026-Q4-G1-fix-the-bugs.md", goal("open", "2025-G1"));
      // A quarterly goal, not a yearly one.
      note("goals/2026/Q4/2026-Q4-G2-write-docs.md", goal("open", "2026-Q4-G1"));
      // As they should be: no problem.
      note("goals/2026/Q4/2026-Q4-G3-tidy-up.md", goal("open", "2026-G1"));
      note("efforts/tidy.md", effort("2026-Q4-G3"));
      // No goal has the ID.
      note("efforts/infinijepsen-demo.md", effort("2026-Q4-G9"));
      // A yearly goal, not a quarterly one.
      note("efforts/consensus.md", effort("2026-G1"));

      Result result = CliHarness.run(taskwarrior.environment(), false, "check", "--code", "unknown-goal");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.VALIDATION.exitCode());
      JsonNode problems = JSON.readTree(result.out()).path("problems");
      assertThat(codes(problems)).containsExactlyInAnyOrder("unknown-goal goal:2026-Q4-G1",
            "unknown-goal goal:2026-Q4-G2", "unknown-goal effort:infinijepsen-demo", "unknown-goal effort:consensus");

      // What it points to, and why that will not do.
      assertThat(problem(problems, "goal:2026-Q4-G1").path("message").asString())
            .contains("2025-G1").contains("yearly goal of 2026");
      assertThat(problem(problems, "goal:2026-Q4-G2").path("message").asString())
            .contains("2026-Q4-G1").contains("yearly goal of 2026");
      assertThat(problem(problems, "effort:infinijepsen-demo").path("message").asString())
            .contains("2026-Q4-G9").contains("no goal");
      assertThat(problem(problems, "effort:consensus").path("message").asString())
            .contains("2026-G1").contains("quarterly goal");
      // As for a missing goal: the command pointing it at the right one.
      assertThat(problem(problems, "goal:2026-Q4-G1").path("hint").asString())
            .contains("ztk goal set 2026-Q4-G1 --parent");
      assertThat(problem(problems, "effort:consensus").path("hint").asString())
            .contains("ztk effort set consensus --goal");
   }

   @Test
   void aNoteInAGoalFolderNotNamedAfterAGoalIdOfThatFolderIsAnInvalidGoalNameError() throws IOException {
      Path notes = note("goals/2026/notes.md", "Thoughts on the year.\n");
      // A quarterly goal's name, in the year's folder.
      note("goals/2026/2026-Q4-G1-fix-the-bugs.md", goal("open", "2026-G1"));
      // Another year's goal.
      note("goals/2026/Q4/2025-Q4-G1-ship-it.md", goal("open", "2025-G1"));
      // Material, in a subfolder: no problem.
      note("goals/2026/Q4/data/benchmarks.md", "Numbers.\n");

      Result result = CliHarness.run(taskwarrior.environment(), false, "check", "--code", "invalid-goal-name");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.VALIDATION.exitCode());
      JsonNode problems = JSON.readTree(result.out()).path("problems");
      // Read as plain notes, since they are no goals.
      assertThat(codes(problems)).containsExactlyInAnyOrder("invalid-goal-name note:goals/2026/notes",
            "invalid-goal-name note:goals/2026/2026-Q4-G1-fix-the-bugs",
            "invalid-goal-name note:goals/2026/Q4/2025-Q4-G1-ship-it");
      JsonNode problem = problem(problems, "note:goals/2026/notes");
      assertThat(problem.path("severity").asString()).isEqualTo("error");
      assertThat(problem.path("path").asString()).isEqualTo(notes.toString());
      // Either a goal to rename, or material to move.
      assertThat(problem.path("hint").asString()).contains("Rename").contains("subfolder");
   }

   @Test
   void twoGoalFilesWithOneIdAreEachADuplicateGoalError() throws IOException {
      Path first = note("goals/2026/2026-G1-provably-correct.md", goal("open", null));
      Path second = note("goals/2026/2026-G1-fast.md", goal("open", null));

      Result result = CliHarness.run(taskwarrior.environment(), false, "check", "--code", "duplicate-goal");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.VALIDATION.exitCode());
      JsonNode problems = JSON.readTree(result.out()).path("problems");
      // One per file, each naming the other.
      assertThat(problems.valueStream().map(problem -> problem.path("path").asString()))
            .containsExactly(second.toString(), first.toString());
      assertThat(problems.get(0).path("id").asString()).isEqualTo("goal:2026-G1");
      assertThat(problems.get(0).path("message").asString()).contains(first.toString());
      assertThat(problems.get(1).path("message").asString()).contains(second.toString());
      assertThat(problems.get(0).path("hint").asString()).contains("Rename");
   }

   @Test
   void aTaskNamingNoEffortIsAnUnknownEffortErrorDoneOnesIncluded() throws IOException {
      note("goals/2026/2026-G1-provably-correct.md", goal("open", null));
      note("goals/2026/Q4/2026-Q4-G1-fix-the-bugs.md", goal("open", "2026-G1"));
      note("efforts/infinijepsen-demo.md", effort("2026-Q4-G1"));
      taskwarrior.task("rc.uda.effort.type=string", "add", "Fix bug", "project:Alpha", "effort:ghost");
      String fixBug = taskwarrior.task("_get", "1.uuid");
      // Summaries count done tasks, so their effort must be right too.
      taskwarrior.task("rc.uda.effort.type=string", "add", "Old bug", "project:Beta", "effort:ghost");
      String oldBug = taskwarrior.task("_get", "2.uuid");
      taskwarrior.task("rc.confirmation=off", "2", "done");
      // As it should be: no problem.
      taskwarrior.task("rc.uda.effort.type=string", "add", "Demo", "project:Alpha", "effort:infinijepsen-demo");

      Result result = CliHarness.run(taskwarrior.environment(), false, "check", "--code", "unknown-effort");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.VALIDATION.exitCode());
      JsonNode problems = JSON.readTree(result.out()).path("problems");
      assertThat(codes(problems)).containsExactlyInAnyOrder("unknown-effort task:" + fixBug,
            "unknown-effort task:" + oldBug);
      JsonNode problem = problem(problems, "task:" + fixBug);
      assertThat(problem.path("severity").asString()).isEqualTo("error");
      assertThat(problem.path("message").asString()).contains("Fix bug").contains("ghost");
      // Moved to an existing effort, or out of any; ztk takes the full UUID, which done tasks keep.
      assertThat(problem.path("hint").asString())
            .contains("ztk effort assign <slug> " + fixBug)
            .contains("ztk effort unassign " + fixBug);

      // A task's problem belongs to the task's project.
      Result beta = CliHarness.run(taskwarrior.environment(), false, "check", "--code", "unknown-effort",
            "--project", "Beta");

      assertThat(codes(JSON.readTree(beta.out()).path("problems"))).containsExactly("unknown-effort task:" + oldBug);
   }

   @Test
   void openWorkUnderAClosedGoalOrEffortIsAnOpenUnderClosedErrorAtEveryLevel() throws IOException {
      note("goals/2026/2026-G1-provably-correct.md", closed("reached", null));
      // Open under a reached yearly goal.
      note("goals/2026/Q4/2026-Q4-G1-fix-the-bugs.md", goal("open", "2026-G1"));
      // Closed under a closed goal: as it should be.
      note("goals/2026/Q4/2026-Q4-G2-write-docs.md", closed("missed", "2026-G1"));
      // Active under a missed quarterly goal.
      note("efforts/write-the-guide.md", effort("2026-Q4-G2"));
      note("efforts/infinijepsen-demo.md", """
            ---
            title: InfiniJepsen demo
            status: done
            closed: 2026-10-10
            goal: 2026-Q4-G1
            ---
            """);
      // Left behind when the effort closed.
      taskwarrior.task("rc.uda.effort.type=string", "add", "Fix bug", "project:Alpha", "effort:infinijepsen-demo");
      String fixBug = taskwarrior.task("_get", "1.uuid");
      // Done before it closed: as it should be.
      taskwarrior.task("rc.uda.effort.type=string", "add", "Old bug", "project:Alpha", "effort:infinijepsen-demo");
      taskwarrior.task("rc.confirmation=off", "2", "done");

      Result result = CliHarness.run(taskwarrior.environment(), false, "check", "--code", "open-under-closed");

      // ztk never lets open work under something closed, so finding some is an error.
      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.VALIDATION.exitCode());
      JsonNode problems = JSON.readTree(result.out()).path("problems");
      // One problem per open child, found in the child: it is what gets closed, moved, or reopened over.
      assertThat(codes(problems)).containsExactlyInAnyOrder("open-under-closed goal:2026-Q4-G1",
            "open-under-closed effort:write-the-guide", "open-under-closed task:" + fixBug);

      JsonNode quarterly = problem(problems, "goal:2026-Q4-G1");
      assertThat(quarterly.path("severity").asString()).isEqualTo("error");
      assertThat(quarterly.path("message").asString()).contains("2026-G1").contains("reached");
      assertThat(quarterly.path("hint").asString())
            .contains("ztk goal set 2026-Q4-G1 --status <reached|missed|dropped>")
            .contains("ztk goal set 2026-Q4-G1 --parent <goal ID>")
            .contains("ztk goal set 2026-G1 --status open");

      JsonNode effort = problem(problems, "effort:write-the-guide");
      assertThat(effort.path("message").asString()).contains("2026-Q4-G2").contains("missed");
      assertThat(effort.path("hint").asString())
            .contains("ztk effort set write-the-guide --status <done|dropped>")
            .contains("ztk effort set write-the-guide --goal <goal ID>")
            .contains("ztk goal set 2026-Q4-G2 --status open");

      JsonNode task = problem(problems, "task:" + fixBug);
      assertThat(task.path("message").asString()).contains("Fix bug").contains("infinijepsen-demo").contains("done");
      assertThat(task.path("hint").asString())
            .contains("task " + fixBug.substring(0, 8) + " done")
            .contains("ztk effort assign <slug> " + fixBug)
            .contains("ztk effort set infinijepsen-demo --status active");

      // A task's problem still belongs to the task's project.
      Result beta = CliHarness.run(taskwarrior.environment(), false, "check", "--code", "open-under-closed",
            "--project", "Alpha");

      assertThat(codes(JSON.readTree(beta.out()).path("problems"))).contains("open-under-closed task:" + fixBug);
   }

   @Test
   void anOpenGoalWhosePeriodEndedNeedsClosingWarning() throws IOException {
      Path open = note("goals/2025/2025-G1-ship-it.md", goal("open", null));
      // Closed, with the day it closed: as it should be.
      note("goals/2025/2025-G2-fast.md", """
            ---
            title: Fast
            status: reached
            closed: 2025-12-20
            ---
            """);

      Result result = CliHarness.run(taskwarrior.environment(), false, "check");

      assertThat(result.exitCode()).isZero();
      JsonNode problems = JSON.readTree(result.out()).path("problems");
      assertThat(codes(problems)).containsExactly("needs-closing goal:2025-G1");
      JsonNode problem = problems.get(0);
      assertThat(problem.path("severity").asString()).isEqualTo("warning");
      assertThat(problem.path("path").asString()).isEqualTo(open.toString());
      assertThat(problem.path("message").asString()).contains("2025");
      // Only the user can say whether it was reached, missed or dropped.
      assertThat(problem.path("hint").asString()).contains("ztk goal set 2025-G1 --status <reached|missed|dropped>");
   }

   @Test
   void aClosedDateNotAgreeingWithTheStatusIsAClosedMismatchWarning() throws IOException {
      // Closed by hand, without the date.
      note("goals/2026/2026-G1-provably-correct.md", """
            ---
            title: Provably correct
            status: reached
            ---
            """);
      note("goals/2026/Q4/2026-Q4-G1-fix-the-bugs.md", goal("open", "2026-G1"));
      // Reopened by hand, keeping the date.
      note("efforts/infinijepsen-demo.md", """
            ---
            title: InfiniJepsen demo
            status: active
            closed: 2026-10-10
            goal: 2026-Q4-G1
            ---
            """);

      Result result = CliHarness.run(taskwarrior.environment(), false, "check", "--code", "closed-mismatch");

      assertThat(result.exitCode()).isZero();
      JsonNode problems = JSON.readTree(result.out()).path("problems");
      assertThat(codes(problems)).containsExactlyInAnyOrder("closed-mismatch goal:2026-G1",
            "closed-mismatch effort:infinijepsen-demo");

      JsonNode undated = problem(problems, "goal:2026-G1");
      assertThat(undated.path("severity").asString()).isEqualTo("warning");
      assertThat(undated.path("message").asString()).contains("reached").contains("no closed date");
      // Setting the status again dates it.
      assertThat(undated.path("hint").asString()).contains("ztk goal set 2026-G1 --status reached");

      JsonNode dated = problem(problems, "effort:infinijepsen-demo");
      assertThat(dated.path("message").asString()).contains("active").contains("2026-10-10");
      // Setting the status again clears it.
      assertThat(dated.path("hint").asString()).contains("ztk effort set infinijepsen-demo --status active");
   }

   /**
    * @param status the goal's status, e.g. {@code open}.
    * @param parent the yearly goal of a quarterly goal; {@code null} for none.
    * @return a goal note, as {@code goal new} writes it.
    */
   private static String goal(String status, String parent) {
      return """
            ---
            title: A goal
            status: %s
            %sdate: 2026-01-05T09:00:00+01:00
            ---
            """.formatted(status, parent == null ? "" : "parent: " + parent + "\n");
   }

   /**
    * @param status the goal's closed status, e.g. {@code reached}.
    * @param parent the yearly goal of a quarterly goal; {@code null} for none.
    * @return a closed goal note, dated as {@code goal set} leaves it.
    */
   private static String closed(String status, String parent) {
      return """
            ---
            title: A goal
            status: %s
            closed: 2026-10-01
            %sdate: 2026-01-05T09:00:00+01:00
            ---
            """.formatted(status, parent == null ? "" : "parent: " + parent + "\n");
   }

   /**
    * @param goal the effort's quarterly goal.
    * @return an active effort note, as {@code effort new} writes it.
    */
   private static String effort(String goal) {
      return """
            ---
            title: An effort
            status: active
            goal: %s
            date: 2026-10-05T09:00:00+01:00
            ---
            """.formatted(goal);
   }

   /**
    * @param problems the {@code problems} array of a check run.
    * @return each problem as its code and node ID, e.g. {@code missing-goal effort:infinijepsen-demo}.
    */
   private static List<String> codes(JsonNode problems) {
      return problems.valueStream()
            .map(problem -> problem.path("code").asString() + " " + problem.path("id").asString())
            .toList();
   }

   /**
    * @param problems the {@code problems} array of a check run.
    * @param id the node ID the problem is found in.
    * @return the only problem found in it.
    */
   private static JsonNode problem(JsonNode problems, String id) {
      return problems.valueStream()
            .filter(problem -> problem.path("id").asString().equals(id))
            .reduce((one, other) -> {
               throw new AssertionError("Several problems in " + id + ": " + problems);
            })
            .orElseThrow(() -> new AssertionError(id + " not in " + problems));
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
