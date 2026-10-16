package io.github.jabolina.ztk.taskwarrior;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jabolina.ztk.TestTaskwarrior;
import io.github.jabolina.ztk.ZtkException;
import io.github.jabolina.ztk.configuration.Configuration;
import io.github.jabolina.ztk.configuration.ConfigurationLoader;

/**
 * Creating and changing tasks through the store, against an isolated Taskwarrior.
 */
class TaskStoreWriteTest {

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
   void addReturnsTheCreatedTask() {
      Task task = store.add(List.of("write", "spec", "project:Alpha", "+week"));

      assertThat(task.description()).isEqualTo("write spec");
      assertThat(task.project()).contains("Alpha");
      assertThat(task.tags()).containsExactly("week");
      assertThat(task.status()).isEqualTo(Status.PENDING);
      assertThat(store.get(task.uuid())).isEqualTo(task);
   }

   @Test
   void startActivatesTheTaskAndAppliesModifications() {
      Task task = store.add(List.of("work", "project:Alpha", "+review"));

      store.start(task, List.of("+week", "-review"));

      Task started = store.get(task.uuid());
      assertThat(started.active()).isTrue();
      assertThat(started.tags()).containsExactly("week");
   }

   @Test
   void stopDeactivatesTheTask() {
      Task task = store.add(List.of("work", "project:Alpha"));
      store.start(task, List.of());

      store.stop(task);

      assertThat(store.get(task.uuid()).active()).isFalse();
   }

   @Test
   void modifyChangesAttributes() {
      Task task = store.add(List.of("work", "project:Alpha"));

      store.modify(task, List.of("project:Beta", "+review"));

      Task modified = store.get(task.uuid());
      assertThat(modified.project()).contains("Beta");
      assertThat(modified.tags()).containsExactly("review");
      assertThat(modified.description()).isEqualTo("work");
   }

   @Test
   void doneCompletesTheTask() {
      Task task = store.add(List.of("work", "project:Alpha"));

      store.done(task, List.of());

      Task completed = store.get(task.uuid());
      assertThat(completed.status()).isEqualTo(Status.COMPLETED);
      // Completed tasks leave the working set, so they no longer have an ID.
      assertThat(completed.id()).isZero();
   }

   @Test
   void settingTheNoteKeepsTheDescriptionWithoutUdaInTaskrc() {
      // The fixture's taskrc does not define uda.note: the store must make Taskwarrior understand "note:" itself.
      Task task = store.add(List.of("keep", "me", "project:Alpha"));

      store.modify(task, List.of("note:Alpha/keep-me.md"));

      Task noted = store.get(task.uuid());
      assertThat(noted.description()).isEqualTo("keep me");
      assertThat(noted.note()).contains("Alpha/keep-me.md");
      assertThat(taskwarrior.taskRc()).content().doesNotContain("uda.note");
   }

   @Test
   void taskwarriorOlderThanThreeIsRejected() throws IOException {
      // A stand-in executable that reports an old version for any arguments.
      Path oldTask = Files.writeString(root.resolve("old-task"), "#!/bin/sh\necho 2.6.2\n");
      oldTask.toFile().setExecutable(true);
      Path ztkrc = Files.writeString(root.resolve("old.ztkrc"), """
            data.location=%s
            task.command=%s
            """.formatted(taskwarrior.dataLocation(), oldTask));
      Configuration configuration = ConfigurationLoader.load(ztkrc, taskwarrior.environment());

      assertThatThrownBy(() -> new TaskStore(configuration))
            .isInstanceOfSatisfying(ZtkException.class,
                  e -> assertThat(e.exitCode()).isEqualTo(ZtkException.Kind.TASKWARRIOR.exitCode()))
            .hasMessageContaining("2.6.2")
            .hasMessageContaining("3.0");
   }
}
