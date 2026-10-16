package io.github.jabolina.ztk.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jabolina.ztk.CliHarness;
import io.github.jabolina.ztk.CliHarness.Result;
import io.github.jabolina.ztk.TestTaskwarrior;
import io.github.jabolina.ztk.ZtkException;
import io.github.jabolina.ztk.configuration.ConfigurationLoader;
import io.github.jabolina.ztk.taskwarrior.Task;
import io.github.jabolina.ztk.taskwarrior.TaskStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code ztk add} from the command line, including the guard against arguments ztk does not understand.
 */
class AddCommandTest {

   private static final JsonMapper JSON = JsonMapper.shared();

   @TempDir
   Path root;

   TestTaskwarrior taskwarrior;
   TaskStore store;

   @BeforeEach
   void setUp() {
      taskwarrior = TestTaskwarrior.create(root);
      store = new TaskStore(ConfigurationLoader.load(taskwarrior.environment()));
   }

   @Test
   void addCreatesTheTaskAndPrintsOnlyItsNotePath() {
      Result result = CliHarness.run(taskwarrior.environment(), true, "add", "write", "spec", "project:Alpha", "+week");

      assertThat(result.exitCode()).isZero();
      List<Task> tasks = store.query();
      assertThat(tasks).hasSize(1);
      Task task = tasks.getFirst();
      assertThat(task.description()).isEqualTo("write spec");
      assertThat(task.tags()).containsExactly("week");

      // A path result: only the path on stdout; the human message goes to stderr.
      Path note = taskwarrior.dataLocation().resolve(task.note().orElseThrow());
      assertThat(result.out()).isEqualTo(note + System.lineSeparator());
      assertThat(note).isRegularFile();
   }

   @Test
   void addWithoutProjectFailsWithItsMessageAndCreatesNothing() {
      Result result = CliHarness.run(taskwarrior.environment(), false, "add", "orphan", "+week");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.VALIDATION.exitCode());
      assertThat(result.err()).contains("project:");
      assertThat(result.out()).isEmpty();
      assertThat(store.query()).isEmpty();
   }

   @Test
   void addWithoutWordsIsAUsageError() {
      Result result = CliHarness.run(taskwarrior.environment(), true, "add");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.USAGE.exitCode());
      assertThat(result.err()).contains("ztk add");
      assertThat(store.query()).isEmpty();
   }

   @Test
   void helpPrintsUsageAndCreatesNothing() {
      Result beforeWords = CliHarness.run(taskwarrior.environment(), true, "add", "--help");
      Result afterWords = CliHarness.run(taskwarrior.environment(), true, "add", "fix", "bug", "project:A", "-h");

      assertThat(beforeWords.exitCode()).isZero();
      assertThat(beforeWords.out()).contains("Usage", "add");
      assertThat(afterWords.exitCode()).isZero();
      assertThat(store.query()).isEmpty();
   }

   @Test
   void unknownOptionIsAUsageErrorAndCreatesNothing() {
      Result result = CliHarness.run(taskwarrior.environment(), true, "add", "fix", "--force", "project:A");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.USAGE.exitCode());
      assertThat(store.query()).isEmpty();
   }

   @Test
   void wordStartingWithDashIsAUsageErrorAndCreatesNothing() {
      // Taskwarrior would read -week as "remove tag week"; ztk add does not support it, and must not pass it on.
      Result result = CliHarness.run(taskwarrior.environment(), true, "add", "fix", "-week", "project:A");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.USAGE.exitCode());
      assertThat(result.out() + result.err()).contains("-week");
      assertThat(store.query()).isEmpty();
   }

   @Test
   void pipedOutputPrintsTheTaskAndNotePathAsJson() {
      Result result = CliHarness.run(taskwarrior.environment(), false, "add", "write", "spec", "project:Alpha");

      assertThat(result.exitCode()).isZero();
      Task task = store.query().getFirst();
      JsonNode json = JSON.readTree(result.out());
      assertThat(json.path("schema").asInt()).isEqualTo(1);
      assertThat(json.path("id").asInt()).isEqualTo(task.id());
      assertThat(json.path("uuid").asString()).isEqualTo(task.uuid());
      assertThat(json.path("path").asString())
            .isEqualTo(taskwarrior.dataLocation().resolve(task.note().orElseThrow()).toString());
   }
}
