package io.github.jabolina.ztk.reports;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.github.jabolina.ztk.planning.Effort;
import io.github.jabolina.ztk.planning.EffortStatus;
import io.github.jabolina.ztk.planning.Goal;
import io.github.jabolina.ztk.planning.GoalStatus;
import io.github.jabolina.ztk.planning.Period;
import io.github.jabolina.ztk.util.Frontmatter;
import io.github.jabolina.ztk.util.JsonWritable;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The facts of a quarter or a year, grouped by plan: its goals, their efforts and their tasks, then the goals of other
 * periods holding efforts of this one, then the work without an effort, per project.
 *
 * @param period     The quarter or year.
 * @param date       The day it was taken.
 * @param goals      The goals of the period, by ID: a quarter's goals, or a year's yearly goals, each with its
 *                   quarterly goals, then the quarterly goals pointing to none of them.
 * @param otherGoals The goals of other periods holding an effort listed for this one, by ID: carried over, closed or
 *                   worked on in it.
 * @param general    The tasks with a sign of life in the period outside every listed effort, per project: by name,
 *                   the tasks without a project last.
 * @since 1.0
 * @author José Bolina
 */
public record PlanSummary(Period period, LocalDate date, List<GoalFacts> goals, List<GoalFacts> otherGoals,
                          List<ProjectFacts> general) implements Summary {

   // The level of the top goals' headings, below the period's own.
   private static final int TOP = 2;

   @Override
   public String name() {
      return period.toString();
   }

   @Override
   public LocalDate last() {
      return period.end();
   }

   /**
    * @return the goals of the summary still open although their period ended by the summary's end, by ID: closing them
    *         is the review the summary records, so it is not written before.
    */
   public Stream<Goal> needsClosing() {
      return Stream.concat(goals.stream(), otherGoals.stream())
            .flatMap(GoalFacts::nodes)
            .map(GoalFacts::goal)
            .filter(goal -> goal.needsClosing() && !goal.period().end().isAfter(period.end()))
            .sorted(Goal.ORDER);
   }

   @Override
   public ObjectNode writeJson(ObjectNode target) {
      target.put("period", period.toString());
      target.put("from", period.start().toString());
      target.put("to", period.end().toString());
      target.put("date", date.toString());
      write(target.putArray("goals"), goals);
      write(target.putArray("otherGoals"), otherGoals);
      write(target.putArray("general"), general);
      ArrayNode blocking = target.putArray("needsClosing");
      needsClosing().forEach(goal -> blocking.add(goal.nodeId()));
      return target;
   }

   @Override
   public StringBuilder writeMarkdown(StringBuilder target) {
      SequencedMap<String, String> fields = new LinkedHashMap<>();
      fields.put("title", period.toString());
      fields.put("period", period.toString());
      fields.put("date", date.toString());
      StringBuilder body = new StringBuilder("# " + period + ", " + period.start() + " to " + period.end() + "\n");
      if (goals.isEmpty() && otherGoals.isEmpty() && general.isEmpty()) {
         body.append("\nNo goals or tasks in ").append(period).append(".\n");
      }
      goals.forEach(goal -> goal.writeMarkdown(body, TOP, Stream.empty()));
      // Marked with their own period: an earlier goal holds work carried over, a later one work moved on to it.
      otherGoals.forEach(goal -> goal.writeMarkdown(body, TOP, Stream.of(
            (goal.goal().period().end().isBefore(period.start()) ? "from " : "in ") + goal.goal().period())));
      if (!general.isEmpty()) {
         body.append("\n## General work\n");
         general.forEach(project -> project.writeMarkdown(body));
      }
      return target.append(Frontmatter.render(fields, body.toString()));
   }

   /**
    * @param target the array to write into.
    * @param items the goals, efforts, projects or tasks to write, in order.
    */
   private static void write(ArrayNode target, List<? extends JsonWritable> items) {
      items.forEach(item -> item.writeJson(target.addObject()));
   }

   /**
    * Writes the heading of a goal or an effort, starting with a blank line: its name, then its state in brackets.
    *
    * @param target the text to append to.
    * @param level the heading's level, e.g. 2 for {@code ##}.
    * @param name the goal's ID or the effort's slug, then its title.
    * @param status the label of its status; empty when missing or not one of its kind's.
    * @param closed the day it closed, when it has one.
    * @param marks what else tells its state, e.g. {@code carried over}, in order.
    */
   private static void heading(StringBuilder target, int level, String name, Optional<String> status,
                               Optional<String> closed, Stream<String> marks) {
      String state = Stream.of(Stream.of(status.orElse("invalid status")), closed.map(day -> "closed " + day).stream(),
                  marks)
            .flatMap(Function.identity())
            .collect(Collectors.joining(", "));
      target.append("\n").append("#".repeat(level)).append(" ").append(name).append(" (").append(state).append(")\n");
   }

   /**
    * A goal of the summary, with its efforts and the goals under it.
    *
    * @param goal    The goal.
    * @param efforts Its efforts in the summary, by slug.
    * @param goals   A yearly goal's quarterly goals in the summary, by ID; empty for a quarterly goal.
    */
   public record GoalFacts(Goal goal, List<EffortFacts> efforts, List<GoalFacts> goals) implements JsonWritable {

      @Override
      public ObjectNode writeJson(ObjectNode target) {
         goal.writeJson(target);
         write(target.putArray("efforts"), efforts);
         write(target.putArray("goals"), goals);
         return target;
      }

      /**
       * @return this goal, then the goals under it, depth first.
       */
      private Stream<GoalFacts> nodes() {
         return Stream.concat(Stream.of(this), goals.stream().flatMap(GoalFacts::nodes));
      }

      /**
       * Writes the goal's section: its heading, then its efforts and its goals one level down.
       *
       * @param target the text to append to.
       * @param level the level of the goal's heading.
       * @param marks what else tells its state besides its status, in order.
       */
      private void writeMarkdown(StringBuilder target, int level, Stream<String> marks) {
         heading(target, level, goal.id() + " · " + goal.title(), goal.status().map(GoalStatus::label),
               goal.closed(), marks);
         efforts.forEach(effort -> effort.writeMarkdown(target, level + 1));
         goals.forEach(child -> child.writeMarkdown(target, level + 1, Stream.empty()));
      }
   }

   /**
    * An effort of the summary, with its tasks.
    *
    * @param effort      The effort.
    * @param carriedOver Whether it was carried over as of the period's end: neither done nor dropped, its goal's
    *                    quarter ended by then.
    * @param tasks       Its tasks with a sign of life in the period, and its open ones, oldest first.
    */
   public record EffortFacts(Effort effort, boolean carriedOver, List<Item> tasks) implements JsonWritable {

      @Override
      public ObjectNode writeJson(ObjectNode target) {
         effort.writeJson(target);
         // As of the period's end, in place of today's: the facts of a period stay the same as time goes by.
         target.put("carriedOver", carriedOver);
         write(target.putArray("tasks"), tasks);
         return target;
      }

      /**
       * Writes the effort's section: its heading, then its tasks.
       *
       * @param target the text to append to.
       * @param level the level of the effort's heading.
       */
      private void writeMarkdown(StringBuilder target, int level) {
         heading(target, level, effort.slug() + " · " + effort.title(), effort.status().map(EffortStatus::label),
               effort.closed(), carriedOver ? Stream.of("carried over") : Stream.empty());
         if (!tasks.isEmpty()) {
            target.append("\n");
         }
         // The tasks of an effort may span projects: each tells its own.
         tasks.forEach(item -> item.writeLine(target,
               Stream.concat(item.task().project().stream(), Stream.of(item.task().status().label()))));
      }
   }

   /**
    * The work of one project outside every effort of the summary.
    *
    * @param name  The project; empty for tasks without one.
    * @param tasks Its tasks with a sign of life in the period, oldest first, without their sessions.
    */
   public record ProjectFacts(Optional<String> name, List<Item> tasks) implements JsonWritable {

      @Override
      public ObjectNode writeJson(ObjectNode target) {
         target.put("project", name.orElse(null));
         write(target.putArray("tasks"), tasks);
         return target;
      }

      /**
       * Writes the project's section: its heading, then its tasks.
       *
       * @param target the text to append to.
       */
      private void writeMarkdown(StringBuilder target) {
         target.append("\n### ").append(name.orElse("No project")).append("\n\n");
         tasks.forEach(item -> item.writeLine(target, Stream.of(item.task().status().label())));
      }
   }
}
