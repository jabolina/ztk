package io.github.jabolina.ztk.taskwarrior;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import io.github.jabolina.ztk.util.JsonWritable;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A Taskwarrior task.
 *
 * @param id          The working-set number shown by Taskwarrior, or {@code 0} for completed and deleted tasks.
 * @param uuid        The stable identifier of the task.
 * @param description The task description.
 * @param status      The Taskwarrior status, e.g. {@code pending} or {@code completed}.
 * @param project     The project the task belongs to, if any.
 * @param tags        The task tags, without the leading {@code +}.
 * @param note        The path of the task's note, relative to the knowledge base, if one was created.
 * @param issue       The external issue the task tracks, e.g. {@code GH-1}, from the user's {@code issue} attribute.
 * @param effort      The slug of the effort the task belongs to, from ztk's {@code effort} attribute; empty for general work.
 * @param entry       When the task was created.
 * @param modified    When the task was last changed, by any change, an annotation included.
 * @param started     When the task was started, while it is being worked on.
 * @param end         When the task was completed or deleted.
 */
public record Task(
      int id,
      String uuid,
      String description,
      Status status,
      Optional<String> project,
      List<String> tags,
      Optional<String> note,
      Optional<String> issue,
      Optional<String> effort,
      Instant entry,
      Instant modified,
      Optional<Instant> started,
      Optional<Instant> end
) implements JsonWritable {

   /**
    * Names the task for people, in messages and errors.
    *
    * <p>
    * Tasks outside the working set, such as completed ones, have no ID and are named by the first eight characters
    * of their UUID, as Taskwarrior does.
    * </p>
    *
    * @return the ID and description, e.g. {@code Task 3 ('Fix flaky test')} or {@code Task 49d52b73 ('Fix flaky test')}.
    */
   public String label() {
      String name = id == 0 ? uuid.substring(0, 8) : String.valueOf(id);
      return "Task " + name + " ('" + description + "')";
   }

   /**
    * Tells whether the task is still to do.
    *
    * @return whether it is pending, or waiting until a later date; done and deleted tasks, and the templates of
    *         recurring tasks, are not.
    */
   public boolean open() {
      return status == Status.PENDING || status == Status.WAITING;
   }

   /**
    * @return whether the task is started, i.e., currently being worked on.
    */
   public boolean active() {
      return started.isPresent();
   }

   /**
    * @param slug the slug of an effort, e.g. {@code infinijepsen-demo}.
    * @return this task as it would be in that effort.
    */
   public Task withEffort(String slug) {
      return new Task(id, uuid, description, status, project, tags, note, issue, Optional.of(slug), entry, modified,
            started, end);
   }

   @Override
   public ObjectNode writeJson(ObjectNode target) {
      target.put("id", id);
      target.put("uuid", uuid);
      target.put("description", description);
      // Lowercase, to follow the same as Taskwarrior.
      target.put("status", status.label());
      // A null value is written as JSON null.
      target.put("project", project.orElse(null));
      ArrayNode array = target.putArray("tags");
      for (String tag : tags) {
         array.add(tag);
      }
      target.put("active", active());
      target.put("issue", issue.orElse(null));
      // Null for general work, so every task has the same fields.
      target.put("effort", effort.orElse(null));
      return target;
   }
}
