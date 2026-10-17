package io.github.jabolina.ztk.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jabolina.ztk.CliHarness;
import io.github.jabolina.ztk.CliHarness.Result;
import io.github.jabolina.ztk.TestTaskwarrior;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The command line as a whole: dispatch, output modes, global flags and exit codes.
 */
class CliTest {

   private static final JsonMapper JSON = JsonMapper.shared();

   // Every ANSI color or style sequence starts with the escape character.
   private static final String ANSI_ESCAPE = "\u001B[";

   @TempDir
   Path root;

   TestTaskwarrior taskwarrior;

   @BeforeEach
   void setUp() {
      taskwarrior = TestTaskwarrior.create(root);
   }

   @Test
   void configShowPrintsTheSourceAndEffectiveValuesOnTerminal() {
      Result result = CliHarness.run(taskwarrior.environment(), true, "config", "show");

      assertThat(result.exitCode()).isZero();
      assertThat(result.out()).contains(
            "source: " + taskwarrior.ztkrc(),
            "data.location=" + taskwarrior.dataLocation(),
            "task.command=task",
            "task.rc=" + taskwarrior.taskRc(),
            "task.data=" + taskwarrior.taskData(),
            "editor=true");
      assertThat(result.err()).isEmpty();
   }

   @Test
   void configShowPrintsJsonWhenStdoutIsNotTerminal() {
      Result result = CliHarness.run(taskwarrior.environment(), false, "config", "show");

      assertThat(result.exitCode()).isZero();
      JsonNode json = JSON.readTree(result.out());
      assertThat(json.path("schema").asInt()).isEqualTo(1);
      assertThat(json.path("source").asString()).isEqualTo(taskwarrior.ztkrc().toString());
      JsonNode values = json.path("values");
      assertThat(values.path("data.location").asString()).isEqualTo(taskwarrior.dataLocation().toString());
      assertThat(values.path("task.command").asString()).isEqualTo("task");
      assertThat(values.path("task.rc").asString()).isEqualTo(taskwarrior.taskRc().toString());
      assertThat(values.path("task.data").asString()).isEqualTo(taskwarrior.taskData().toString());
      assertThat(values.path("editor").asString()).isEqualTo("true");
   }

   @Test
   void configShowListsEveryFolderInsideDataLocation() {
      Result result = CliHarness.run(taskwarrior.environment(), false, "config", "show");

      // Unset in the fixture, so these are the defaults: shown so agents know where each kind of note lives.
      JsonNode values = JSON.readTree(result.out()).path("values");
      Path data = taskwarrior.dataLocation();
      assertThat(values.path("folder.zettel").asString()).isEqualTo(data.resolve("zettel").toString());
      assertThat(values.path("folder.papers").asString()).isEqualTo(data.resolve("papers").toString());
      assertThat(values.path("folder.goals").asString()).isEqualTo(data.resolve("goals").toString());
      assertThat(values.path("folder.efforts").asString()).isEqualTo(data.resolve("efforts").toString());
      assertThat(values.path("folder.journal").asString()).isEqualTo(data.resolve("journal").toString());
      assertThat(values.path("folder.summaries").asString()).isEqualTo(data.resolve("summaries").toString());
   }

   @Test
   void humanFlagForcesHumanOutputWhenPiped() {
      Result result = CliHarness.run(taskwarrior.environment(), false, "--human", "config", "show");

      assertThat(result.exitCode()).isZero();
      assertThat(result.out()).startsWith("source: ");
   }

   @Test
   void jsonFlagForcesJsonOutputOnTerminalInAnyPosition() {
      Result result = CliHarness.run(taskwarrior.environment(), true, "config", "show", "--json");

      assertThat(result.exitCode()).isZero();
      assertThat(JSON.readTree(result.out()).path("schema").asInt()).isEqualTo(1);
   }

   @Test
   void configFlagSelectsTheConfigurationFile() {
      // No ZTKRC and an empty HOME: only the flag can lead to the fixture's configuration.
      Properties env = new Properties();
      env.setProperty("HOME", root.resolve("empty-home").toString());

      Result result = CliHarness.run(env, true, "--config", taskwarrior.ztkrc().toString(), "config", "show");

      assertThat(result.exitCode()).isZero();
      assertThat(result.out()).contains("source: " + taskwarrior.ztkrc());
   }

   @Test
   void configShowWithoutConfigurationExplainsWhatIsMissing() {
      Properties env = new Properties();
      env.setProperty("HOME", root.resolve("empty-home").toString());

      Result result = CliHarness.run(env, true, "config", "show");

      // Inspecting the configuration is how users find out why other commands fail, so it must not fail itself.
      assertThat(result.exitCode()).isZero();
      assertThat(result.out()).contains(
            "No ztk configuration found",
            root.resolve("empty-home/.ztkrc").toString(),
            "data.location=");
   }

   @Test
   void helpWorksWithoutConfigurationAndListsCommands() {
      Properties env = new Properties();
      env.setProperty("HOME", root.resolve("empty-home").toString());

      Result result = CliHarness.run(env, true, "--help");

      assertThat(result.exitCode()).isZero();
      assertThat(result.out()).contains("config", "--json", "--human", "--config");
   }

   @Test
   void helpHasNoColorsWhenStdoutIsNotTerminal() {
      Result result = CliHarness.run(taskwarrior.environment(), false, "--help");

      assertThat(result.exitCode()).isZero();
      assertThat(result.out()).contains("Usage: ztk").doesNotContain(ANSI_ESCAPE);
   }

   @Test
   void usageErrorHasNoColorsWhenStdoutIsNotTerminal() {
      Result result = CliHarness.run(taskwarrior.environment(), false, "add", "--force", "fix");

      assertThat(result.exitCode()).isEqualTo(1);
      assertThat(result.err()).contains("--force", "Usage: ztk add").doesNotContain(ANSI_ESCAPE);
   }

   @Test
   void helpHasColorsOnTerminal() {
      // Æsh also reads the real NO_COLOR variable, which the test environment cannot override.
      assumeTrue(System.getenv("NO_COLOR") == null, "NO_COLOR is set in the environment running the tests");

      Result result = CliHarness.run(taskwarrior.environment(), true, "--help");

      assertThat(result.exitCode()).isZero();
      assertThat(result.out()).contains(ANSI_ESCAPE);
   }

   @Test
   void noColorVariableDisablesColorsOnTerminal() {
      Properties env = taskwarrior.environment();
      // Any value counts, even empty, as defined by https://no-color.org.
      env.setProperty("NO_COLOR", "");

      Result result = CliHarness.run(env, true, "--help");

      assertThat(result.exitCode()).isZero();
      assertThat(result.out()).contains("Usage: ztk").doesNotContain(ANSI_ESCAPE);
   }

   @Test
   void unknownCommandIsAUsageError() {
      Result result = CliHarness.run(taskwarrior.environment(), true, "bogus");

      assertThat(result.exitCode()).isEqualTo(1);
      assertThat(result.out() + result.err()).contains("bogus");
   }
}
