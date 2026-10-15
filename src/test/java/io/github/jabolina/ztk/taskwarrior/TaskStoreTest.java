package io.github.jabolina.ztk.taskwarrior;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jabolina.ztk.TestTaskwarrior;
import io.github.jabolina.ztk.ZtkException;
import io.github.jabolina.ztk.configuration.ConfigurationLoader;

/**
 * Reading tasks through the store, against an isolated Taskwarrior.
 */
class TaskStoreTest {

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
   void queryReturnsMatchingTasks() {
      taskwarrior.task("add", "first", "project:Alpha", "+week");
      taskwarrior.task("add", "second", "project:Beta");

      List<Task> week = store.query("+week");

      assertThat(week).hasSize(1);
      Task task = week.getFirst();
      assertThat(task.id()).isEqualTo(1);
      assertThat(task.uuid()).hasSize(36);
      assertThat(task.description()).isEqualTo("first");
      assertThat(task.status()).isEqualTo("pending");
      assertThat(task.project()).contains("Alpha");
      assertThat(task.tags()).containsExactly("week");
   }

   @Test
   void queryWithoutMatchesIsEmpty() {
      taskwarrior.task("add", "first", "project:Alpha");

      assertThat(store.query("project:Nowhere")).isEmpty();
   }

   @Test
   void startedTaskIsActive() {
      taskwarrior.task("add", "idle");
      taskwarrior.task("add", "busy");
      taskwarrior.task("2", "start");

      List<Task> tasks = store.query();

      assertThat(tasks).extracting(Task::description, Task::active)
            .containsExactlyInAnyOrder(tuple("idle", false), tuple("busy", true));
   }

   @Test
   void getResolvesIdAndUuid() {
      taskwarrior.task("add", "first");
      taskwarrior.task("add", "second");

      Task byId = store.get("2");
      Task byUuid = store.get(byId.uuid());

      assertThat(byId.description()).isEqualTo("second");
      assertThat(byUuid).isEqualTo(byId);
   }

   @Test
   void getUnknownTaskIsNotFound() {
      assertThatThrownBy(() -> store.get("99"))
            .isInstanceOfSatisfying(ZtkException.class,
                  e -> assertThat(e.exitCode()).isEqualTo(ZtkException.Kind.NOT_FOUND.exitCode()))
            .hasMessageContaining("99");
   }

   @Test
   void getRejectsReferencesThatAreNotIdOrUuid() {
      // A filter would match this task if it were passed through to Taskwarrior.
      taskwarrior.task("add", "first", "project:Alpha");

      assertThatThrownBy(() -> store.get("project:Alpha"))
            .isInstanceOfSatisfying(ZtkException.class,
                  e -> assertThat(e.exitCode()).isEqualTo(ZtkException.Kind.USAGE.exitCode()))
            .hasMessageContaining("project:Alpha");
   }

   @Test
   void taskwarriorFailureIncludesItsError() {
      assertThatThrownBy(() -> store.query("(unbalanced"))
            .isInstanceOfSatisfying(ZtkException.class,
                  e -> assertThat(e.exitCode()).isEqualTo(ZtkException.Kind.TASKWARRIOR.exitCode()))
            .hasMessageContaining("Mismatched parentheses");
   }

   @Test
   void readsTheNoteAttribute() {
      // Defined for this setup command only: the fixture's taskrc has no UDAs, like a user's .taskrc.
      taskwarrior.task("rc.uda.note.type=string", "add", "noted", "project:Alpha", "note:Alpha/fix-bug.md");
      taskwarrior.task("add", "plain", "project:Alpha");

      assertThat(store.get("1").note()).contains("Alpha/fix-bug.md");
      assertThat(store.get("2").note()).isEmpty();
   }
}
