package io.github.jabolina.ztk.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jabolina.ztk.CliHarness;
import io.github.jabolina.ztk.CliHarness.Result;
import io.github.jabolina.ztk.TestTaskwarrior;
import io.github.jabolina.ztk.ZtkException;
import io.github.jabolina.ztk.configuration.ConfigurationLoader;
import io.github.jabolina.ztk.taskwarrior.Status;
import io.github.jabolina.ztk.taskwarrior.Task;
import io.github.jabolina.ztk.taskwarrior.TaskStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The task lifecycle from the command line: starting, stopping, reviewing and completing tasks.
 */
class LifecycleCommandsTest {

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
   void startStartsTheTaskAndPrintsOnlyItsNotePath() {
      Result result = CliHarness.run(taskwarrior.environment(), true, "start", "1");

      assertThat(result.exitCode()).isZero();
      Task task = store.get("1");
      assertThat(task.active()).isTrue();

      // A path result: only the path on stdout; the human message goes to stderr.
      Path note = taskwarrior.dataLocation().resolve(task.note().orElseThrow());
      assertThat(result.out()).isEqualTo(note + System.lineSeparator());
      assertThat(result.err()).contains("Task 1");
   }

   @Test
   void startPrintsTheTaskAndNotePathAsJsonWhenPiped() {
      Result result = CliHarness.run(taskwarrior.environment(), false, "start", "1");

      assertThat(result.exitCode()).isZero();
      Task task = store.get("1");
      JsonNode json = JSON.readTree(result.out());
      assertThat(json.path("schema").asInt()).isEqualTo(1);
      assertThat(json.path("id").asInt()).isEqualTo(1);
      assertThat(json.path("uuid").asString()).isEqualTo(task.uuid());
      assertThat(json.path("path").asString())
            .isEqualTo(taskwarrior.dataLocation().resolve(task.note().orElseThrow()).toString());
   }

   @Test
   void startWhileAnotherTaskIsActiveFailsOnStderrWithoutOutput() {
      taskwarrior.task("add", "Write spec", "project:Alpha");
      CliHarness.run(taskwarrior.environment(), false, "start", "1");

      Result result = CliHarness.run(taskwarrior.environment(), false, "start", "2");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.VALIDATION.exitCode());
      assertThat(result.out()).isEmpty();
      assertThat(result.err()).contains("Task 1", "ztk stop");
      assertThat(store.get("2").active()).isFalse();
   }

   @Test
   void startWithEditorOpensTheNoteInTheConfiguredEditor() {
      Path editorArguments = taskwarrior.recordingEditor();

      Result result = CliHarness.run(taskwarrior.environment(), true, "start", "1", "-e");

      assertThat(result.exitCode()).isZero();
      Path note = taskwarrior.dataLocation().resolve(store.get("1").note().orElseThrow());
      assertThat(editorArguments).content().isEqualTo(note + "\n");
      // Still a path result, so the flag does not change what scripts read.
      assertThat(result.out()).isEqualTo(note + System.lineSeparator());
   }

   @Test
   void stopPrintsTheStoppedTasksOnTerminal() {
      CliHarness.run(taskwarrior.environment(), false, "start", "1");

      Result result = CliHarness.run(taskwarrior.environment(), true, "stop");

      assertThat(result.exitCode()).isZero();
      assertThat(result.out()).contains("Stopped Task 1", "Fix flaky test");
      assertThat(store.get("1").active()).isFalse();
   }

   @Test
   void stopPrintsTheStoppedTasksAsJsonWhenPiped() {
      CliHarness.run(taskwarrior.environment(), false, "start", "1");

      Result result = CliHarness.run(taskwarrior.environment(), false, "stop");

      assertThat(result.exitCode()).isZero();
      JsonNode json = JSON.readTree(result.out());
      assertThat(json.path("schema").asInt()).isEqualTo(1);
      assertThat(json.path("tasks")).hasSize(1);
      JsonNode stopped = json.path("tasks").get(0);
      assertThat(stopped.path("id").asInt()).isEqualTo(1);
      assertThat(stopped.path("uuid").asString()).isEqualTo(store.get("1").uuid());
      assertThat(stopped.path("description").asString()).isEqualTo("Fix flaky test");
   }

   @Test
   void stopWithNothingActiveSaysSoAndSucceeds() {
      Result result = CliHarness.run(taskwarrior.environment(), true, "stop");

      assertThat(result.exitCode()).isZero();
      assertThat(result.out()).contains("No task is active");
   }

   @Test
   void reviewStopsTheTaskAndSaysItIsReadyForReviewOnTerminal() {
      CliHarness.run(taskwarrior.environment(), false, "start", "1");

      Result result = CliHarness.run(taskwarrior.environment(), true, "review", "1");

      assertThat(result.exitCode()).isZero();
      assertThat(result.out()).contains("Task 1", "Fix flaky test", "review");
      Task task = store.get("1");
      assertThat(task.active()).isFalse();
      assertThat(task.tags()).containsExactlyInAnyOrder("week", "review");
   }

   @Test
   void reviewPrintsTheTaskAsJsonWhenPiped() {
      Result result = CliHarness.run(taskwarrior.environment(), false, "review", "1");

      assertThat(result.exitCode()).isZero();
      JsonNode json = JSON.readTree(result.out());
      assertThat(json.path("schema").asInt()).isEqualTo(1);
      assertThat(json.path("id").asInt()).isEqualTo(1);
      assertThat(json.path("uuid").asString()).isEqualTo(store.get("1").uuid());
      assertThat(json.path("description").asString()).isEqualTo("Fix flaky test");
   }

   @Test
   void doneCompletesTheTaskAndSaysSoOnTerminal() {
      String uuid = store.get("1").uuid();

      Result result = CliHarness.run(taskwarrior.environment(), true, "done", "1");

      assertThat(result.exitCode()).isZero();
      assertThat(result.out()).contains("Completed", "Fix flaky test");
      assertThat(store.get(uuid).status()).isEqualTo(Status.COMPLETED);
   }

   @Test
   void startWithEditorOutsideAnInteractiveSessionFailsBeforeStarting() {
      Result result = CliHarness.run(taskwarrior.environment(), false, "start", "1", "--editor");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.USAGE.exitCode());
      assertThat(result.out()).isEmpty();
      // Points to the same command without the flag.
      assertThat(result.err()).contains("ztk start 1");
      assertThat(store.get("1").active()).isFalse();
      assertThat(taskwarrior.dataLocation()).isEmptyDirectory();
   }
}
