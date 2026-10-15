package io.github.jabolina.ztk.taskwarrior;

import java.util.List;
import java.util.Optional;

/**
 * A Taskwarrior task.
 *
 * @param id          The working-set number shown by Taskwarrior, or {@code 0} for completed and deleted tasks.
 * @param uuid        The stable identifier of the task.
 * @param description The task description.
 * @param status      The Taskwarrior status, e.g. {@code pending} or {@code completed}.
 * @param project     The project the task belongs to, if any.
 * @param tags        The task tags, without the leading {@code +}.
 * @param active      Whether the task is started, i.e., currently being worked on.
 * @param note        The path of the task's note, relative to the knowledge base, if one was created.
 * @param issue       The external issue the task tracks, e.g. {@code GH-1}, from the user's {@code issue} attribute.
 */
public record Task(
      int id,
      String uuid,
      String description,
      String status,
      Optional<String> project,
      List<String> tags,
      boolean active,
      Optional<String> note,
      Optional<String> issue
) { }
