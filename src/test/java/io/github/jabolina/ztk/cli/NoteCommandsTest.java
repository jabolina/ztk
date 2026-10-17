package io.github.jabolina.ztk.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
 * Finding and creating notes from the command line.
 */
class NoteCommandsTest {

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
   void infoPrintsTheTaskAndItsNotePathAsJsonWhenPiped() {
      // Created through ztk, so the task has a note; not started, since info works on any task.
      CliHarness.run(taskwarrior.environment(), false, "add", "Write spec", "project:Alpha");
      Task task = store.get("2");

      Result result = CliHarness.run(taskwarrior.environment(), false, "info", "2");

      assertThat(result.exitCode()).isZero();
      JsonNode json = JSON.readTree(result.out());
      assertThat(json.path("schema").asInt()).isEqualTo(1);
      assertThat(json.path("id").asInt()).isEqualTo(2);
      assertThat(json.path("uuid").asString()).isEqualTo(task.uuid());
      assertThat(json.path("description").asString()).isEqualTo("Write spec");
      assertThat(json.path("active").asBoolean()).isFalse();
      assertThat(json.path("note").asString())
            .isEqualTo(taskwarrior.dataLocation().resolve(task.note().orElseThrow()).toString());
   }

   @Test
   void infoOnAnUnknownTaskIsNotFound() {
      Result result = CliHarness.run(taskwarrior.environment(), false, "info", "9");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.NOT_FOUND.exitCode());
      assertThat(result.out()).isEmpty();
      // Names the task, and points to the command that shows the existing IDs.
      assertThat(result.err()).contains("Task 9", "ztk list");
   }

   @Test
   void newCreatesAZettelAndPrintsOnlyItsPath() throws IOException {
      // Unquoted words form the title, like the description of add.
      Result result = CliHarness.run(taskwarrior.environment(), true, "new", "Causality", "and", "Time");

      assertThat(result.exitCode()).isZero();
      // A path result: only the path on stdout.
      Path zettel = Path.of(result.out().strip());
      assertThat(result.out()).isEqualTo(zettel + System.lineSeparator());
      // The fixture leaves folder.zettel unset, so its default applies.
      assertThat(zettel.getParent()).isEqualTo(taskwarrior.dataLocation().resolve("zettel"));
      assertThat(zettel.getFileName().toString()).matches("\\d{8}-causality-and-time\\.md");
      assertThat(Files.readString(zettel))
            .startsWith("---\ntitle: Causality and Time\n")
            .containsPattern("\nuuid: [0-9a-f-]{36}\n")
            .contains("# Notes");
   }

   @Test
   void newPrintsThePathAndUuidAsJsonWhenPiped() throws IOException {
      Result result = CliHarness.run(taskwarrior.environment(), false, "new", "Causality");

      assertThat(result.exitCode()).isZero();
      JsonNode json = JSON.readTree(result.out());
      assertThat(json.path("schema").asInt()).isEqualTo(1);
      Path zettel = Path.of(json.path("path").asString());
      assertThat(zettel.getParent()).isEqualTo(taskwarrior.dataLocation().resolve("zettel"));
      // The same identifier the zettel carries, so scripts can refer to it without reading the file.
      assertThat(Files.readString(zettel)).contains("\nuuid: " + json.path("uuid").asString() + "\n");
      assertThat(json.path("uuid").asString()).hasSize(36);
   }

   @Test
   void newWithEditorOpensTheZettelInTheConfiguredEditor() {
      Path editorArguments = taskwarrior.recordingEditor();

      Result result = CliHarness.run(taskwarrior.environment(), true, "new", "Causality", "-e");

      assertThat(result.exitCode()).isZero();
      Path zettel = Path.of(result.out().strip());
      assertThat(editorArguments).content().isEqualTo(zettel + "\n");
   }

   @Test
   void newWithEditorOutsideAnInteractiveSessionFailsBeforeWriting() {
      Result result = CliHarness.run(taskwarrior.environment(), false, "new", "Causality", "--editor");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.USAGE.exitCode());
      assertThat(result.out()).isEmpty();
      // Points to the same command without the flag.
      assertThat(result.err()).contains("ztk new Causality");
      assertThat(taskwarrior.dataLocation().resolve("zettel")).doesNotExist();
   }
}
