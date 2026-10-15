package io.github.jabolina.ztk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jabolina.ztk.configuration.Configuration;
import io.github.jabolina.ztk.configuration.ConfigurationLoader;

/**
 * Sanity checks for the isolated Taskwarrior fixture, so other tests can trust it.
 */
class TestTaskwarriorTest {

   @TempDir
   Path root;

   @Test
   void taskCommandsWriteToTheFixtureDataOnly() {
      TestTaskwarrior taskwarrior = TestTaskwarrior.create(root);

      taskwarrior.task("add", "isolated", "project:Test");

      assertThat(taskwarrior.task("count")).isEqualTo("1");
      assertThat(taskwarrior.taskData()).isNotEmptyDirectory();
   }

   @Test
   void fixtureConfigurationLoadsThroughTheEnvironment() {
      TestTaskwarrior taskwarrior = TestTaskwarrior.create(root);

      Configuration configuration = ConfigurationLoader.load(taskwarrior.environment());

      assertThat(configuration.source()).isEqualTo(taskwarrior.ztkrc());
      assertThat(configuration.dataLocation()).isEqualTo(taskwarrior.dataLocation());
      assertThat(configuration.taskRc()).contains(taskwarrior.taskRc());
      assertThat(configuration.taskData()).contains(taskwarrior.taskData());
   }

   @Test
   void failingTaskCommandReportsItsError() {
      TestTaskwarrior taskwarrior = TestTaskwarrior.create(root);

      // Taskwarrior rejects an add without a description.
      assertThatThrownBy(() -> taskwarrior.task("add"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Additional text must be provided");
   }

   @Test
   void refusesToLiveOutsideTheTemporaryDirectory() {
      Path outside = Path.of(System.getProperty("user.home"), "ztk-test-must-not-exist");

      assertThatThrownBy(() -> TestTaskwarrior.create(outside))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("must live under");
      assertThat(outside).doesNotExist();
   }
}
