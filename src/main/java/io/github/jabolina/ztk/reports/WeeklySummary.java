package io.github.jabolina.ztk.reports;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.stream.Stream;

import io.github.jabolina.ztk.util.Frontmatter;
import io.github.jabolina.ztk.util.JsonWritable;
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
public record WeeklySummary(Week week, LocalDate date, List<Project> projects) implements Summary {

   @Override
   public String name() {
      return week.toString();
   }

   @Override
   public LocalDate last() {
      return week.last();
   }

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
         // The project is the heading's: the effort and the goal tell where the task stands in the plan.
         items.forEach(item -> item.writeLine(target, Stream.of(
                     item.task().effort().map(effort -> "effort " + effort),
                     item.goal().map(goal -> "goal " + goal))
               .flatMap(Optional::stream)));
      }
   }
}
