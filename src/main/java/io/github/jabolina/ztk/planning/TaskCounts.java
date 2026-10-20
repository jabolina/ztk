package io.github.jabolina.ztk.planning;

import java.util.stream.Collector;
import java.util.stream.Collectors;

import io.github.jabolina.ztk.taskwarrior.Status;
import io.github.jabolina.ztk.taskwarrior.Task;
import io.github.jabolina.ztk.util.JsonWritable;
import tools.jackson.databind.node.ObjectNode;

/**
 * How many tasks of an effort are still to do, and how many are done.
 *
 * @param pending   The tasks still to do, waiting ones included.
 * @param completed The tasks done.
 * @since 1.0
 * @author José Bolina
 */
public record TaskCounts(long pending, long completed) implements JsonWritable {

   /**
    * The counts of an effort without tasks.
    */
   static final TaskCounts NONE = new TaskCounts(0, 0);

   /**
    * @return a collector counting tasks in one pass; deleted tasks and recurring templates are not counted.
    */
   static Collector<Task, ?, TaskCounts> counting() {
      return Collectors.teeing(
            Collectors.filtering(Task::open, Collectors.counting()),
            Collectors.filtering(task -> task.status() == Status.COMPLETED, Collectors.counting()),
            TaskCounts::new);
   }

   /**
    * Writes {@code pending} and {@code completed}.
    *
    * @param target the JSON object to write into.
    * @return {@code target}, for chaining.
    */
   @Override
   public ObjectNode writeJson(ObjectNode target) {
      target.put("pending", pending);
      target.put("completed", completed);
      return target;
   }
}
