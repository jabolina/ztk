package io.github.jabolina.ztk.cli;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Listing tasks from the command line: the week and the task list.
 */
class ListingCommandsTest {

   private static final JsonMapper JSON = JsonMapper.shared();

   @TempDir
   Path root;

   TestTaskwarrior taskwarrior;

   @BeforeEach
   void setUp() {
      taskwarrior = TestTaskwarrior.create(root);
      taskwarrior.task("add", "Fix flaky test", "project:Alpha", "+week");
      taskwarrior.task("add", "Write spec", "project:Alpha");
   }

   @Test
   void weekShowsOnlyThisWeeksTasksOnTerminal() {
      Result result = CliHarness.run(taskwarrior.environment(), true, "week");

      assertThat(result.exitCode()).isZero();
      assertThat(result.out()).contains("Fix flaky test")
            .doesNotContain("Write spec");
   }

   @Test
   void weekWithAProjectShowsOnlyThatProjectsTasks() {
      taskwarrior.task("add", "Review design", "project:Beta", "+week");

      Result result = CliHarness.run(taskwarrior.environment(), true, "week", "Beta");

      assertThat(result.exitCode()).isZero();
      assertThat(result.out()).contains("Review design")
            .doesNotContain("Fix flaky test");
   }

   @Test
   void weekPrintsTheTasksAsJsonWhenPiped() {
      taskwarrior.task("1", "start");
      taskwarrior.task("1", "modify", "+review");

      Result result = CliHarness.run(taskwarrior.environment(), false, "week");

      assertThat(result.exitCode()).isZero();
      JsonNode json = JSON.readTree(result.out());
      assertThat(json.path("schema").asInt()).isEqualTo(1);
      assertThat(json.path("tasks")).hasSize(1);
      JsonNode task = json.path("tasks").get(0);
      assertThat(task.path("id").asInt()).isEqualTo(1);
      assertThat(task.path("description").asString()).isEqualTo("Fix flaky test");
      assertThat(task.path("status").asString()).isEqualTo("pending");
      assertThat(task.path("project").asString()).isEqualTo("Alpha");
      assertThat(task.path("tags")).extracting(JsonNode::asString).containsExactlyInAnyOrder("week", "review");
      assertThat(task.path("active").asBoolean()).isTrue();
      // Unset attributes are written as null, so scripts always find the key.
      assertThat(task.has("issue")).isTrue();
      assertThat(task.path("issue").isNull()).isTrue();
   }

   @Test
   void weekKeepsTaskwarriorColorsOnTerminal() {
      // Taskwarrior colors tasks only when it sees a terminal, and ztk captures its output.
      Result result = CliHarness.run(taskwarrior.environment(), true, "week");

      assertThat(result.out()).contains("\u001B[");
   }

   @Test
   void weekWithNoColorPrintsPlainTextOnTerminal() {
      Properties env = taskwarrior.environment();
      env.setProperty("NO_COLOR", "");

      Result result = CliHarness.run(env, true, "week");

      assertThat(result.exitCode()).isZero();
      assertThat(result.out()).contains("Fix flaky test")
            .doesNotContain("\u001B[");
   }

   @Test
   void weekWithNoTasksSaysSoAndSucceeds() {
      taskwarrior.task("1", "modify", "-week");

      Result result = CliHarness.run(taskwarrior.environment(), true, "week");

      assertThat(result.exitCode()).isZero();
      // Points to the command that brings a task into the week.
      assertThat(result.out()).contains("No tasks", "ztk start");
   }
}
