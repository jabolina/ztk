package io.github.jabolina.ztk.reports;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import io.github.jabolina.ztk.journal.Session;
import io.github.jabolina.ztk.taskwarrior.Task;
import io.github.jabolina.ztk.util.JsonWritable;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A task in a summary, with what tells its case apart.
 *
 * @param task      The task.
 * @param note      The absolute path of its note, when it has one.
 * @param goal      The ID of its effort's goal, when its effort names one.
 * @param sessions  Its sessions in the period, in the order they were written; empty when not gathered, as for the
 *                  work without an effort of a quarter or a year.
 * @param untouched The day it was last changed, when it is in without a sign of life in the period, e.g. a review
 *                  waiting on reviewers.
 * @since 1.0
 * @author José Bolina
 */
public record Item(Task task, Optional<Path> note, Optional<String> goal, Optional<List<Session>> sessions,
                   Optional<LocalDate> untouched) implements JsonWritable {

   @Override
   public ObjectNode writeJson(ObjectNode target) {
      task.writeJson(target);
      target.put("note", note.map(Path::toString).orElse(null));
      target.put("goal", goal.orElse(null));
      target.put("modified", task.modified().toString());
      target.put("started", task.started().map(Instant::toString).orElse(null));
      target.put("end", task.end().map(Instant::toString).orElse(null));
      target.put("untouched", untouched.map(LocalDate::toString).orElse(null));
      // Left out when not gathered, so an agent never takes it for a task without sessions.
      sessions.ifPresent(list -> {
         ArrayNode array = target.putArray("sessions");
         list.forEach(session -> session.writeJson(array.addObject()));
      });
      return target;
   }

   /**
    * Writes the task as one list line, ending with a line break: its note's link or its description, the details the
    * summary gives it, and when it was last changed if untouched.
    *
    * @param target the text to append to.
    * @param details what the summary tells of the task besides its name, e.g. its effort or its project, in order.
    * @return the same {@code target}, for appending further text.
    */
   public StringBuilder writeLine(StringBuilder target, Stream<String> details) {
      // Linked by the note's name, and shown by its description, which reads better than a file name.
      target.append("- ").append(note
            .map(path -> "[[" + path.getFileName().toString().replaceFirst("\\.md$", "") + "|"
                  + task.description() + "]]")
            .orElse(task.description()));
      details.forEach(detail -> target.append(" · ").append(detail));
      untouched.ifPresent(day -> target.append(" (untouched since ").append(day).append(")"));
      return target.append("\n");
   }
}
