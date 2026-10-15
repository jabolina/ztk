package io.github.jabolina.ztk.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
   void addWithoutProjectIsRejectedBeforeAnythingIsWritten() {
      assertThatThrownBy(() -> tasks.add(List.of("orphan", "+week")))
            .isInstanceOfSatisfying(ZtkException.class,
                  e -> assertThat(e.exitCode()).isEqualTo(ZtkException.Kind.VALIDATION.exitCode()))
            .hasMessageContaining("project:");

      assertThat(store.query()).isEmpty();
      assertThat(taskwarrior.dataLocation()).isEmptyDirectory();
   }
}
