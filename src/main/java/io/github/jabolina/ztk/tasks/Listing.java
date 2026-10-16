package io.github.jabolina.ztk.tasks;

import java.util.List;

import io.github.jabolina.ztk.taskwarrior.Report;
import io.github.jabolina.ztk.taskwarrior.Task;
import io.github.jabolina.ztk.util.JsonWritable;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tasks selected by a listing, together with the Taskwarrior report that shows the same tasks.
 *
 * <p>
 * Its JSON form has only the tasks. The report is for people, and is only rendered when someone reads it.
 * </p>
 *
 * @param tasks the selected tasks.
 * @param report the Taskwarrior report showing them.
 * @since 1.0
 * @author José Bolina
 */
public record Listing(List<Task> tasks, Report report) implements JsonWritable {

   public static final Listing EMPTY = new Listing(List.of(), null);

   /**
    * Writes the tasks as a {@code tasks} array, empty when nothing was selected.
    *
    * @param target the JSON object to write into.
    * @return {@code target}, for chaining.
    */
   @Override
   public ObjectNode writeJson(ObjectNode target) {
      ArrayNode array = target.putArray("tasks");
      for (Task task : tasks) {
         task.writeJson(array.addObject());
      }
      return target;
   }
}
