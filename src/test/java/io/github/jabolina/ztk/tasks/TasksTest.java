package io.github.jabolina.ztk.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jabolina.ztk.TestTaskwarrior;
import io.github.jabolina.ztk.ZtkException;
import io.github.jabolina.ztk.configuration.Configuration;
import io.github.jabolina.ztk.configuration.ConfigurationLoader;
import io.github.jabolina.ztk.taskwarrior.Status;
import io.github.jabolina.ztk.taskwarrior.Task;
import io.github.jabolina.ztk.taskwarrior.TaskStore;

/**
 * Task use cases, against an isolated Taskwarrior and knowledge base.
 */
class TasksTest {

   // A fixed instant, so note names and the frontmatter date are predictable.
   private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 15, 12, 0, 0, 0, ZoneOffset.ofHours(-3));

   @TempDir
   Path root;

   TestTaskwarrior taskwarrior;
   TaskStore store;
   Tasks tasks;

   @BeforeEach
   void setUp() {
      taskwarrior = TestTaskwarrior.create(root);
      Configuration configuration = ConfigurationLoader.load(taskwarrior.environment());
      store = new TaskStore(configuration);
      tasks = new Tasks(configuration, store, Clock.fixed(NOW.toInstant(), NOW.getOffset()));
   }

   @Test
   void addCreatesTheTaskWithItsNote() {
      TaskNote added = tasks.add(List.of("Fix flaky test", "project:Alpha"));

      Path expected = taskwarrior.dataLocation().resolve("Alpha/20261015-fix-flaky-test.md");
      assertThat(added.note()).isEqualTo(expected);
      assertThat(added.note()).content().isEqualTo("""
            ---
            title: Fix flaky test
            date: 2026-10-15T12:00:00-03:00
            task_uuid: %s
            project: Alpha
            issue: ""
            ---

            # Notes

            """.formatted(added.task().uuid()));
      assertThat(added.task().description()).isEqualTo("Fix flaky test");
      assertThat(added.task().note()).contains("Alpha/20261015-fix-flaky-test.md");
      assertThat(store.get(added.task().uuid())).isEqualTo(added.task());
   }

   @Test
   void addWithIssueReferencesItInTheNote() {
      TaskNote added = tasks.add(List.of("Fix flaky test", "project:Alpha", "issue:GH-1"));

      assertThat(added.task().issue()).contains("GH-1");
      assertThat(added.note()).content().isEqualTo("""
            ---
            title: Fix flaky test
            date: 2026-10-15T12:00:00-03:00
            task_uuid: %s
            project: Alpha
            issue: GH-1
            ---

            Ref: **GH-1**

            # Notes

            """.formatted(added.task().uuid()));
   }

   @Test
   void addQuotesTitlesThatWouldNotBeValidYaml() {
      TaskNote added = tasks.add(List.of("Fix: flaky test", "project:Alpha"));

      assertThat(added.note()).hasFileName("20261015-fix-flaky-test.md");
      assertThat(added.note()).content().contains("\ntitle: \"Fix: flaky test\"\n");
   }

   @Test
   void addingTheSameDescriptionTwiceKeepsSeparateNotes() {
      TaskNote first = tasks.add(List.of("Fix flaky test", "project:Alpha"));
      TaskNote second = tasks.add(List.of("Fix flaky test", "project:Alpha"));

      assertThat(second.note()).hasFileName("20261015-fix-flaky-test-2.md");
      assertThat(second.task().note()).contains("Alpha/20261015-fix-flaky-test-2.md");
      assertThat(second.note()).content().contains("task_uuid: " + second.task().uuid());
      assertThat(first.note()).content().contains("task_uuid: " + first.task().uuid());
   }

   @Test
   void startStartsTheTaskAndMovesItFromReviewToTheWeek() {
      TaskNote added = tasks.add(List.of("Fix flaky test", "project:Alpha", "+review"));

      TaskNote started = tasks.start(String.valueOf(added.task().id()));

      assertThat(started.task().uuid()).isEqualTo(added.task().uuid());
      assertThat(started.task().active()).isTrue();
      assertThat(started.task().tags()).containsExactly("week");
      assertThat(started.note()).isEqualTo(added.note());
      // The returned task is the stored state, not a stale copy.
      assertThat(store.get(added.task().uuid())).isEqualTo(started.task());
   }

   @Test
   void startCreatesAndLinksTheNoteOfATaskWithoutOne() {
      taskwarrior.task("add", "Fix flaky test", "project:Alpha");

      TaskNote started = tasks.start("1");

      Path expected = taskwarrior.dataLocation().resolve("Alpha/20261015-fix-flaky-test.md");
      assertThat(started.note()).isEqualTo(expected);
      assertThat(expected).content().contains("task_uuid: " + started.task().uuid());
      assertThat(started.task().note()).contains("Alpha/20261015-fix-flaky-test.md");
      assertThat(started.task().active()).isTrue();
   }

   @Test
   void startLinksALegacyNoteFoundByItsTaskUuidInsteadOfCreatingOne() throws IOException {
      taskwarrior.task("add", "Fix flaky test", "project:Alpha");
      String uuid = taskwarrior.task("_get", "1.uuid");
      // Created by the shell script, without the note attribute, in a folder named differently from the project.
      Path legacy = taskwarrior.dataLocation().resolve("alpha/20250301-fix-flaky-test.md");
      Files.createDirectories(legacy.getParent());
      Files.writeString(legacy, """
            ---
            title: Fix flaky test
            date: Sat Mar  1 10:00:00 AM -03 2025
            task_uuid: %s
            project: Alpha
            issue:
            ---

            # Notes

            Findings so far.
            """.formatted(uuid));

      TaskNote started = tasks.start("1");

      assertThat(started.note()).isEqualTo(legacy);
      assertThat(started.task().note()).contains("alpha/20250301-fix-flaky-test.md");
      assertThat(legacy).content().contains("Findings so far.");
      assertThat(taskwarrior.dataLocation().resolve("Alpha")).doesNotExist();
   }

   @Test
   void startRefusesWhileAnotherTaskIsActiveAndSaysHowToProceed() {
      TaskNote first = tasks.add(List.of("Fix flaky test", "project:Alpha"));
      TaskNote second = tasks.add(List.of("Write spec", "project:Alpha", "+review"));
      tasks.start(String.valueOf(first.task().id()));

      assertThatThrownBy(() -> tasks.start(String.valueOf(second.task().id())))
            .isInstanceOfSatisfying(ZtkException.class,
                  e -> assertThat(e.exitCode()).isEqualTo(ZtkException.Kind.VALIDATION.exitCode()))
            // Names the blocking task and the commands that release it.
            .hasMessageContaining("Task " + first.task().id())
            .hasMessageContaining("Fix flaky test")
            .hasMessageContaining("ztk stop")
            .hasMessageContaining("ztk done " + first.task().id());

      assertThat(store.get(second.task().uuid())).isEqualTo(second.task());
      assertThat(store.get(first.task().uuid()).active()).isTrue();
   }

   @Test
   void startingTheActiveTaskAgainSucceedsWithTheSameNote() {
      TaskNote added = tasks.add(List.of("Fix flaky test", "project:Alpha"));
      String reference = String.valueOf(added.task().id());
      tasks.start(reference);
      taskwarrior.task(added.task().uuid(), "modify", "+review");

      TaskNote again = tasks.start(reference);

      assertThat(again.note()).isEqualTo(added.note());
      assertThat(again.task().active()).isTrue();
      assertThat(again.task().tags()).containsExactly("week");
   }

   @Test
   void startWithoutProjectIsRejectedBeforeStartingAndSaysHowToFixIt() {
      taskwarrior.task("add", "orphan");

      assertThatThrownBy(() -> tasks.start("1"))
            .isInstanceOfSatisfying(ZtkException.class,
                  e -> assertThat(e.exitCode()).isEqualTo(ZtkException.Kind.VALIDATION.exitCode()))
            .hasMessageContaining("Task 1")
            .hasMessageContaining("task 1 modify project:");

      assertThat(store.get("1").active()).isFalse();
      assertThat(taskwarrior.dataLocation()).isEmptyDirectory();
   }

   @Test
   void stopStopsEveryActiveTaskAndReturnsThem() {
      taskwarrior.task("add", "Fix flaky test", "project:Alpha");
      taskwarrior.task("add", "Write spec", "project:Alpha");
      taskwarrior.task("add", "Idle", "project:Alpha");
      // Plain Taskwarrior does not enforce a single active task.
      taskwarrior.task("1", "start");
      taskwarrior.task("2", "start");

      List<Task> stopped = tasks.stop();

      assertThat(stopped).extracting(Task::description).containsExactlyInAnyOrder("Fix flaky test", "Write spec");
      assertThat(stopped).noneMatch(Task::active);
      assertThat(store.query("+ACTIVE")).isEmpty();
   }

   @Test
   void stopWithNothingActiveStopsNothing() {
      taskwarrior.task("add", "Idle", "project:Alpha");

      assertThat(tasks.stop()).isEmpty();
   }

   @Test
   void reviewStopsTheActiveTaskAndTagsItForReviewKeepingItInTheWeek() {
      TaskNote added = tasks.add(List.of("Fix flaky test", "project:Alpha"));
      String reference = String.valueOf(added.task().id());
      tasks.start(reference);

      Task reviewed = tasks.review(reference);

      assertThat(reviewed.active()).isFalse();
      assertThat(reviewed.tags()).containsExactlyInAnyOrder("week", "review");
      // The returned task is the stored state, not a stale copy.
      assertThat(store.get(added.task().uuid())).isEqualTo(reviewed);
   }

   @Test
   void reviewTagsATaskThatIsNotActiveAndMovesItToTheWeek() {
      TaskNote added = tasks.add(List.of("Fix flaky test", "project:Alpha"));

      Task reviewed = tasks.review(String.valueOf(added.task().id()));

      assertThat(reviewed.active()).isFalse();
      assertThat(reviewed.tags()).containsExactlyInAnyOrder("week", "review");
   }

   @Test
   void doneCompletesTheActiveTaskAndTakesItOutOfTheWeek() {
      TaskNote added = tasks.add(List.of("Fix flaky test", "project:Alpha", "+backend"));
      String reference = String.valueOf(added.task().id());
      tasks.start(reference);

      Task completed = tasks.done(reference);

      assertThat(completed.uuid()).isEqualTo(added.task().uuid());
      assertThat(completed.status()).isEqualTo(Status.COMPLETED);
      assertThat(completed.active()).isFalse();
      // Only the week tag is removed; the task's own tags stay for reports.
      assertThat(completed.tags()).containsExactly("backend");
      assertThat(store.get(added.task().uuid())).isEqualTo(completed);
   }

   @Test
   void doneOnACompletedTaskIsRejectedAndSaysSo() {
      TaskNote added = tasks.add(List.of("Fix flaky test", "project:Alpha"));
      String uuid = added.task().uuid();
      tasks.done(uuid);

      assertThatThrownBy(() -> tasks.done(uuid))
            .isInstanceOfSatisfying(ZtkException.class,
                  e -> assertThat(e.exitCode()).isEqualTo(ZtkException.Kind.VALIDATION.exitCode()))
            // Completed tasks have no ID, so the task is named by its short UUID, as Taskwarrior does.
            .hasMessageContaining(uuid.substring(0, 8))
            .hasMessageContaining("Fix flaky test")
            .hasMessageContaining("already completed");
   }

   @Test
   void addWithoutProjectIsRejectedBeforeAnythingIsWritten() {
      assertThatThrownBy(() -> tasks.add(List.of("orphan", "+week")))
            .isInstanceOfSatisfying(ZtkException.class,
                  e -> assertThat(e.exitCode()).isEqualTo(ZtkException.Kind.VALIDATION.exitCode()))
            .hasMessageContaining("project:");

      assertThat(store.query()).isEmpty();
      assertThat(taskwarrior.dataLocation()).isEmptyDirectory();
   }
}
