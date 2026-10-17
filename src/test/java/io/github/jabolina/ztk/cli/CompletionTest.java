package io.github.jabolina.ztk.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.jabolina.ztk.CliHarness;
import io.github.jabolina.ztk.CliHarness.Result;
import io.github.jabolina.ztk.TestTaskwarrior;

/**
 * Completion candidates offered for ztk's arguments.
 *
 * <p>
 * The shell scripts and the matching of subcommands and options belong to Æsh. These tests cover the candidates ztk
 * computes itself from Taskwarrior, asked the way the shell scripts ask: {@code ztk --aesh-complete -- <words>}, where
 * the last word is the one being completed.
 * </p>
 */
class CompletionTest {

   @TempDir
   Path root;

   TestTaskwarrior taskwarrior;

   @BeforeEach
   void setUp() {
      taskwarrior = TestTaskwarrior.create(root);
   }

   @ParameterizedTest
   @ValueSource(strings = { "start", "review", "done", "info" })
   void commandsTakingTaskOfferPendingTaskIds(String command) {
      taskwarrior.task("add", "first", "project:Alpha");
      taskwarrior.task("add", "second", "project:Alpha");

      Result result = complete(command, "");

      assertThat(result.exitCode()).isZero();
      // Only the IDs: no file names, which the shell would otherwise offer next to them.
      assertThat(result.out().lines()).containsExactly("1", "2");
   }

   @Test
   void taskIdsAreFilteredByWhatWasTyped() {
      taskwarrior.task("add", "first", "project:Alpha");
      taskwarrior.task("add", "second", "project:Alpha");

      Result result = complete("done", "2");

      assertThat(result.exitCode()).isZero();
      assertThat(result.out().lines()).containsExactly("2");
   }

   @Test
   void addCompletesProjectAttributeWithExistingProjects() {
      taskwarrior.task("add", "first", "project:Alpha");
      taskwarrior.task("add", "second", "project:Beta");

      Result result = complete("add", "fix", "bug", "project:Al");

      assertThat(result.exitCode()).isZero();
      assertThat(result.out().lines()).containsExactly("project:Alpha");
   }

   @Test
   void addCompletesTagsInUseByOpenTasks() {
      taskwarrior.task("add", "first", "+week", "+review");
      taskwarrior.task("add", "second", "+archived");
      taskwarrior.task("2", "done");

      Result result = complete("add", "fix", "+");

      assertThat(result.exitCode()).isZero();
      // Neither the completed task's tag nor Taskwarrior's virtual tags (ACTIVE, OVERDUE...), which cannot be added.
      assertThat(result.out().lines()).containsExactly("+review", "+week");
   }

   @ParameterizedTest
   @ValueSource(strings = { "list", "week" })
   void listingsCompleteProjectNames(String command) {
      taskwarrior.task("add", "first", "project:Alpha");
      taskwarrior.task("add", "second", "project:Beta");

      Result result = complete(command, "Al");

      assertThat(result.exitCode()).isZero();
      assertThat(result.out().lines()).containsExactly("Alpha");
   }

   @Test
   void withoutConfigurationCompletionStaysQuiet() {
      // No ZTKRC and an empty HOME: no configuration can be found.
      Properties env = new Properties();
      env.setProperty("HOME", root.resolve("empty-home").toString());

      Result result = complete(env, "done", "");

      // Completion runs on every Tab: an error there would be printed in the middle of the command being typed.
      // Without task IDs Æsh offers the command's options instead, so stdout is not checked.
      assertThat(result.exitCode()).isZero();
      assertThat(result.err()).isEmpty();
   }

   /**
    * Asks ztk for the candidates of the last word, as the shell completion scripts do, with the fixture's environment.
    *
    * @param words the words after the program name. The last one is being completed and may be empty.
    * @return the exit code and the candidates, one per line on stdout.
    */
   private Result complete(String... words) {
      return complete(taskwarrior.environment(), words);
   }

   /**
    * Asks ztk for the candidates of the last word, as the shell completion scripts do.
    *
    * @param env the process environment ztk runs with.
    * @param words the words after the program name. The last one is being completed and may be empty.
    * @return the exit code and the candidates, one per line on stdout.
    */
   private static Result complete(Properties env, String... words) {
      String[] args = new String[words.length + 2];
      args[0] = "--aesh-complete";
      args[1] = "--";
      System.arraycopy(words, 0, args, 2, words.length);
      return CliHarness.run(env, false, args);
   }
}
