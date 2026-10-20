package io.github.jabolina.ztk.planning;

import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import io.github.jabolina.ztk.ZtkException;
import io.github.jabolina.ztk.taskwarrior.Status;
import io.github.jabolina.ztk.taskwarrior.Task;
import io.github.jabolina.ztk.taskwarrior.TaskStore;

/**
 * Reads the tasks of efforts from Taskwarrior, where a task names its effort in the {@code effort} attribute.
 *
 * @since 1.0
 * @author José Bolina
 */
final class EffortTasks {

   private final Supplier<TaskStore> store;

   /**
    * @param store the tasks, reached only when they are read.
    */
   EffortTasks(Supplier<TaskStore> store) {
      this.store = store;
   }

   /**
    * Counts the tasks of every effort, with one export.
    *
    * @return the counts by effort slug; an effort without tasks is not in it.
    * @throws ZtkException with {@link ZtkException.Kind#TASKWARRIOR} when Taskwarrior fails.
    */
   Map<String, TaskCounts> counts() {
      return store.get().query(TaskStore.EFFORT_UDA + ".any:").stream()
            .collect(Collectors.groupingBy(task -> task.effort().orElseThrow(), TaskCounts.counting()));
   }

   /**
    * Reads the tasks of one effort.
    *
    * @param slug the effort's slug.
    * @return its tasks by status, in Taskwarrior's order; deleted tasks and recurring templates are left out.
    * @throws ZtkException with {@link ZtkException.Kind#TASKWARRIOR} when Taskwarrior fails.
    */
   Efforts.TasksByStatus of(String slug) {
      // ".is" matches the whole slug; a bare "effort:" filter matches its start, and would take infinijepsen-demo-two
      // as a task of infinijepsen-demo.
      return store.get().query(TaskStore.EFFORT_UDA + ".is:" + slug).stream()
            .collect(Collectors.teeing(
                  Collectors.filtering(Task::open, Collectors.toList()),
                  Collectors.filtering(task -> task.status() == Status.COMPLETED, Collectors.toList()),
                  Efforts.TasksByStatus::new));
   }
}
