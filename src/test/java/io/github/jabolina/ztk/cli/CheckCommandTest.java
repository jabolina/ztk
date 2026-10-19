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
                  "unreadable-note");
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
