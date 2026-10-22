package io.github.jabolina.ztk.reports;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;

import io.github.jabolina.ztk.journal.Session;
import io.github.jabolina.ztk.taskwarrior.Task;
import io.github.jabolina.ztk.util.Frontmatter;
import io.github.jabolina.ztk.util.JsonWritable;
import io.github.jabolina.ztk.util.MarkdownWritable;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The facts of a week: per project, the tasks completed in it, and those under review and in progress when taken.
 *
 * @param week     The week.
 * @param date     The day it was taken, when the tasks under review and in progress were so.
 * @param projects The projects with tasks in the week, by name, the tasks without a project last.
 * @since 1.0
 * @author José Bolina
 */
public record WeeklySummary(Week week, LocalDate date, List<Project> projects)
      implements JsonWritable, MarkdownWritable {

   @Override
   public ObjectNode writeJson(ObjectNode target) {
      target.put("period", week.toString());
      target.put("from", week.first().toString());
      target.put("to", week.last().toString());
      target.put("date", date.toString());
      ArrayNode array = target.putArray("projects");
      projects.forEach(project -> project.writeJson(array.addObject()));
      return target;
   }

   @Override
   public StringBuilder writeMarkdown(StringBuilder target) {
      SequencedMap<String, String> fields = new LinkedHashMap<>();
      fields.put("title", "Week " + week);
      fields.put("period", week.toString());
      fields.put("date", date.toString());
      StringBuilder body = new StringBuilder("# Week " + week + ", " + week.first() + " to " + week.last() + "\n");
      if (projects.isEmpty()) {
         body.append("\nNo tasks in the week.\n");
      }
      projects.forEach(project -> project.writeMarkdown(body, date));
      return target.append(Frontmatter.render(fields, body.toString()));
   }

   /**
    * The tasks of one project in the week, each group oldest first.
    *
    * @param name      The project; empty for tasks without one.
    * @param completed The tasks completed in the week.
    * @param review    The pending tasks under review.
    * @param progress  The other pending tasks.
    */
   public record Project(Optional<String> name, List<Item> completed, List<Item> review, List<Item> progress)
         implements JsonWritable {

      @Override
      public ObjectNode writeJson(ObjectNode target) {
         target.put("project", name.orElse(null));
         write(target.putArray("completed"), completed);
         write(target.putArray("review"), review);
         write(target.putArray("progress"), progress);
         return target;
      }

      /**
       * @param target the array to write into.
       * @param items the tasks of a group.
       */
      private static void write(ArrayNode target, List<Item> items) {
         items.forEach(item -> item.writeJson(target.addObject()));
      }

      /**
       * Writes the project's section, leaving out the groups without tasks.
       *
       * @param target the text to append to.
       * @param date the day the summary was taken, when the tasks under review and in progress were so.
       */
      private void writeMarkdown(StringBuilder target, LocalDate date) {
         target.append("\n## ").append(name.orElse("No project")).append("\n");
         section(target, "Completed", completed);
         // The current state: past weeks show the tasks as they are on the day taken, not as they were.
         section(target, "Under review as of " + date, review);
         section(target, "In progress as of " + date, progress);
      }

      /**
       * @param target the text to append to.
       * @param heading the group's heading.
       * @param items the group's tasks; nothing is written when there are none.
       */
      private static void section(StringBuilder target, String heading, List<Item> items) {
         if (items.isEmpty()) {
            return;
         }
         target.append("\n### ").append(heading).append("\n\n");
         items.forEach(item -> item.writeMarkdown(target));
      }
   }

   /**
    * A task in the week, with what tells its case apart.
    *
    * @param task      The task.
    * @param note      The absolute path of its note, when it has one.
    * @param goal      The ID of its effort's goal, when its effort names one.
    * @param sessions  Its sessions in the week, in the order they were written.
    * @param untouched The day it was last changed, when it is in only for being on the plate now, e.g. a review
    *                  waiting on reviewers.
    */
   public record Item(Task task, Optional<Path> note, Optional<String> goal, List<Session> sessions,
                      Optional<LocalDate> untouched) implements JsonWritable, MarkdownWritable {

      @Override
      public ObjectNode writeJson(ObjectNode target) {
         task.writeJson(target);
         target.put("note", note.map(Path::toString).orElse(null));
         target.put("goal", goal.orElse(null));
         target.put("modified", task.modified().toString());
         target.put("started", task.started().map(Instant::toString).orElse(null));
         target.put("end", task.end().map(Instant::toString).orElse(null));
         target.put("untouched", untouched.map(LocalDate::toString).orElse(null));
         ArrayNode array = target.putArray("sessions");
         sessions.forEach(session -> session.writeJson(array.addObject()));
         return target;
      }

      /**
       * Writes the task as one list line, ending with a line break.
       *
       * @param target the text to append to.
       * @return the same {@code target}, for appending further text.
       */
      @Override
      public StringBuilder writeMarkdown(StringBuilder target) {
         // Linked by the note's name, and shown by its description, which reads better than a file name.
         target.append("- ").append(note
               .map(path -> "[[" + path.getFileName().toString().replaceFirst("\\.md$", "") + "|"
                     + task.description() + "]]")
               .orElse(task.description()));
         task.effort().ifPresent(effort -> target.append(" · effort ").append(effort));
         goal.ifPresent(id -> target.append(" · goal ").append(id));
         untouched.ifPresent(day -> target.append(" (untouched since ").append(day).append(")"));
         return target.append("\n");
      }
   }
}
