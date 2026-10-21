package io.github.jabolina.ztk.cli.commands;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.CompletionFallback;
import org.aesh.command.option.Arguments;
import org.aesh.command.option.Option;

import io.github.jabolina.ztk.ZtkException;
import io.github.jabolina.ztk.cli.ZtkInvocation;
import io.github.jabolina.ztk.cli.completers.EffortCompleter;
import io.github.jabolina.ztk.cli.completers.GoalCompleter;
import io.github.jabolina.ztk.cli.completers.TaskCompleter;
import io.github.jabolina.ztk.cli.renderer.Table;
import io.github.jabolina.ztk.planning.Effort;
import io.github.jabolina.ztk.planning.EffortStatus;
import io.github.jabolina.ztk.planning.Efforts;
import io.github.jabolina.ztk.planning.Goal;
import io.github.jabolina.ztk.planning.GoalStatus;
import io.github.jabolina.ztk.planning.Period;
import io.github.jabolina.ztk.planning.Saved;
import io.github.jabolina.ztk.taskwarrior.Task;
import io.github.jabolina.ztk.util.Labeled;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code ztk effort}: commands for efforts, the groups of tasks that move a quarterly goal forward.
 *
 * @since 1.0
 * @author José Bolina
 */
@CommandDefinition(name = "effort", description = "Plan efforts toward quarterly goals", generateHelp = true,
      groupCommands = { EffortCommand.New.class, EffortCommand.Set.class, EffortCommand.ListEfforts.class,
            EffortCommand.Info.class, EffortCommand.Assign.class, EffortCommand.Unassign.class })
public class EffortCommand implements Command<ZtkInvocation> {

   // One effort per row: where it stands, the goal it moves forward, and how far its tasks are.
   // Shared with goal info, which lists the efforts of its goals the same way.
   static final Table<Efforts.Listed> TABLE_RENDERER = Table.<Efforts.Listed>builder()
         .column("SLUG", listed -> listed.effort().slug())
         .column("STATUS", listed -> listed.effort().status().map(EffortStatus::label).orElse("invalid")
               + (listed.effort().carriedOver() ? ", carried over" : ""))
         .column("GOAL", listed -> listed.effort().goal().orElse(""))
         .column("TASKS", listed -> listed.tasks().pending() + " pending, " + listed.tasks().completed() + " completed")
         .column("TITLE", listed -> listed.effort().title())
         .build();

   @Override
   public CommandResult execute(ZtkInvocation invocation) {
      invocation.println(invocation.getHelpInfo());
      return CommandResult.SUCCESS;
   }

   /**
    * Reads an effort status as typed on the command line.
    *
    * @param label the status, e.g. {@code active}; one of the option's allowed values.
    * @return the status.
    */
   private static EffortStatus status(String label) {
      return Labeled.parse(EffortStatus.class, label).orElseThrow();
   }

   /**
    * Prints changed tasks: one line each for people, a {@code tasks} array in the common task shape for programs.
    *
    * @param invocation the command invocation.
    * @param changed the tasks, as stored after the change.
    * @param line what to say about one task, e.g. {@code Task 3 ('Fix bug') joined infinijepsen-demo.}
    */
   private static void printTasks(ZtkInvocation invocation, List<Task> changed, Function<Task, String> line) {
      ObjectNode json = invocation.jsonResult();
      ArrayNode array = json.putArray("tasks");
      changed.forEach(task -> task.writeJson(array.addObject()));
      invocation.result(() -> changed.stream()
            .map(task -> line.apply(task) + System.lineSeparator())
            .collect(Collectors.joining()), json);
   }

   /**
    * {@code ztk effort new}: creates an effort from its template and prints its note's path.
    *
    * <p>
    * The effort is planned and moves one open quarterly goal forward. Its slug is its file name and ID, so it must be
    * the name of no other note. Only the path goes to stdout, which makes the command usable in shell substitutions.
    * </p>
    *
    * <pre>
    * ztk effort new infinijepsen-demo --title "InfiniJepsen demo" --goal 2026-Q4-G1
    * </pre>
    *
    * @since 1.0
    * @author José Bolina
    */
   @CommandDefinition(name = "new", description = "Create an effort from its template and print its note's path",
         generateHelp = true)
   public static final class New implements Command<ZtkInvocation> {

      @Argument(description = "The effort's slug, its file name and ID, e.g., infinijepsen-demo", required = true, completeFallback = CompletionFallback.NONE)
      String slug;

      @Option(description = "The effort's title", required = true)
      String title;

      @Option(description = "The quarterly goal the effort moves forward, e.g., 2026-Q4-G1", required = true,
            completer = GoalCompleter.Quarterly.class)
      String goal;

      @Override
      public CommandResult execute(ZtkInvocation invocation) {
         Saved created = invocation.context().efforts().create(new Efforts.Details(slug, title, goal));

         // Human messages redirected to stderr, the result goes to stdout.
         invocation.printErr("Created " + created.id() + ".");
         invocation.result(created.path() + System.lineSeparator(), created.writeJson(invocation.jsonResult()));
         return CommandResult.SUCCESS;
      }
   }

   /**
    * {@code ztk effort set}: changes an effort's status, title or goal, and prints its node ID.
    *
    * <p>
    * Only the effort's frontmatter changes, never its body or file name. Closing an effort dates it in {@code closed};
    * reopening it removes that date. A goal of a later quarter carries the effort over.
    * </p>
    *
    * <pre>
    * ztk effort set infinijepsen-demo --status active
    * ztk effort set infinijepsen-demo --title "InfiniJepsen live demo"
    * ztk effort set infinijepsen-demo --goal 2027-Q1-G1
    * </pre>
    *
    * @since 1.0
    * @author José Bolina
    */
   @CommandDefinition(name = "set", description = "Change an effort's status, title or goal", generateHelp = true)
   public static final class Set implements Command<ZtkInvocation> {

      @Argument(description = "The effort's slug, e.g., infinijepsen-demo", required = true, completer = EffortCompleter.class,
            completeFallback = CompletionFallback.NONE)
      String effort;

      // The labels of EffortStatus, which a completion test keeps in step.
      @Option(description = "The effort's new status", allowedValues = { "planned", "active", "done", "dropped" })
      String status;

      @Option(description = "The effort's new title; its file name stays")
      String title;

      @Option(description = "The effort's new quarterly goal, e.g., 2027-Q1-G1; a later quarter carries it over",
            completer = GoalCompleter.Quarterly.class)
      String goal;

      @Override
      public CommandResult execute(ZtkInvocation invocation) {
         if (status == null && title == null && goal == null) {
            throw ZtkException.usage("Nothing to change. Give --status, --title or --goal, e.g. ztk effort set "
                  + effort + " --status active");
         }
         Optional<EffortStatus> newStatus = Optional.ofNullable(status).map(EffortCommand::status);

         Saved saved = invocation.context().efforts()
               .update(effort, new Efforts.Changes(newStatus, Optional.ofNullable(title), Optional.ofNullable(goal)));

         // Human messages redirected to stderr, the result goes to stdout.
         invocation.printErr("Updated " + saved.id() + ".");
         invocation.result(saved.id() + System.lineSeparator(), saved.writeJson(invocation.jsonResult()));
         return CommandResult.SUCCESS;
      }
   }

   /**
    * {@code ztk effort list}: lists efforts with their tasks counted.
    *
    * <p>
    * Without a period, the open efforts of every quarter are listed: planned or active. Efforts whose goal's quarter
    * has ended are marked as carried over. A quarter, or a year, lists the efforts of its goals in any status.
    * </p>
    *
    * <pre>
    * ztk effort list
    * ztk effort list 2026-Q4
    * ztk effort list --status done
    * ztk effort list --goal 2026-Q4-G1
    * </pre>
    *
    * @since 1.0
    * @author José Bolina
    */
   @CommandDefinition(name = "list", description = "List efforts with their tasks counted", generateHelp = true)
   public static final class ListEfforts implements Command<ZtkInvocation> {

      @Argument(description = "The quarter or year of the efforts' goals, e.g., 2026-Q4 or 2026; open efforts of any quarter when not given", completeFallback = CompletionFallback.NONE)
      String period;

      // The labels of EffortStatus, which a completion test keeps in step.
      @Option(description = "Only list efforts with this status", allowedValues = { "planned", "active", "done", "dropped" })
      String status;

      @Option(description = "Only list the efforts of this quarterly goal, e.g., 2026-Q4-G1", completer = GoalCompleter.Quarterly.class)
      String goal;

      @Override
      public CommandResult execute(ZtkInvocation invocation) {
         EnumSet<EffortStatus> statuses = status == null ? EnumSet.noneOf(EffortStatus.class) : EnumSet.of(status(status));
         Efforts.Listing listing = invocation.context().efforts().list(new Efforts.Selection(
               Optional.ofNullable(period).map(Period::parse), statuses, Optional.ofNullable(goal)));

         // Rendered only in human mode.
         invocation.result(() -> listing.efforts().isEmpty()
                     ? "No efforts listed. Create one with: ztk effort new <slug> --title \"…\" --goal <quarterly goal>"
                           + System.lineSeparator()
                     : TABLE_RENDERER.render(listing.efforts()),
               listing.writeJson(invocation.jsonResult()));
         return CommandResult.SUCCESS;
      }
   }

   /**
    * {@code ztk effort info}: shows one effort with the goals it moves forward and its tasks.
    *
    * <p>
    * The goal chain leads from the effort's quarterly goal to the yearly goal it moves forward. Tasks are shown by
    * status, each with its note's path, as {@code ztk info} shows them.
    * </p>
    *
    * <pre>
    * ztk effort info infinijepsen-demo
    * </pre>
    *
    * @since 1.0
    * @author José Bolina
    */
   @CommandDefinition(name = "info", description = "Show an effort with its goals and tasks", generateHelp = true)
   public static final class Info implements Command<ZtkInvocation> {

      @Argument(description = "The effort's slug, e.g., infinijepsen-demo", required = true, completer = EffortCompleter.class,
            completeFallback = CompletionFallback.NONE)
      String effort;

      @Override
      public CommandResult execute(ZtkInvocation invocation) {
         Efforts.Info info = invocation.context().efforts().info(effort);
         Effort found = info.effort();
         ObjectNode json = info.writeJson(invocation.jsonResult());

         StringBuilder human = new StringBuilder();
         human.append("Effort ").append(found.slug()).append(" ('").append(found.title()).append("'), ")
               .append(found.status().map(EffortStatus::label).orElse("invalid"))
               .append(found.carriedOver() ? ", carried over" : "").append(System.lineSeparator());
         human.append("  Note: ").append(found.path()).append(System.lineSeparator());
         info.goal().ifPresentOrElse(
               goal -> human.append("  Goal: ").append(describe(goal)).append(System.lineSeparator()),
               // check reports it; until then, the effort shows where it was meant to lead.
               () -> human.append("  Goal: ").append(found.goal().orElse("none")).append(", not found")
                     .append(System.lineSeparator()));
         info.parent().ifPresent(
               yearly -> human.append("  Yearly goal: ").append(describe(yearly)).append(System.lineSeparator()));

         ObjectNode tasks = json.putObject("tasks");
         section(invocation, "Pending", info.tasks().pending(), tasks.putArray("pending"), human);
         section(invocation, "Completed", info.tasks().completed(), tasks.putArray("completed"), human);
         invocation.result(human.toString(), json);
         return CommandResult.SUCCESS;
      }

      /**
       * @param goal a goal.
       * @return its ID, title and status, e.g. {@code 2026-Q4-G1 ('Fix bugs'), open}.
       */
      private static String describe(Goal goal) {
         return goal.id() + " ('" + goal.title() + "'), " + goal.status().map(GoalStatus::label).orElse("invalid");
      }

      /**
       * Describes the tasks of one status, for both output modes.
       *
       * @param invocation the running command, which knows where the knowledge base is.
       * @param title the status heading, e.g. {@code Pending}.
       * @param tasks the tasks.
       * @param array the JSON array receiving each task, in the common task shape plus its {@code note}.
       * @param human the human output.
       */
      private static void section(ZtkInvocation invocation, String title, List<Task> tasks, ArrayNode array,
                                  StringBuilder human) {
         human.append(title).append(':').append(System.lineSeparator());
         if (tasks.isEmpty()) {
            human.append("  none").append(System.lineSeparator());
         }
         tasks.forEach(task -> InfoCommand.describe(invocation, task, human, task.writeJson(array.addObject())));
      }
   }

   /**
    * {@code ztk effort assign}: joins tasks to an effort, and prints them.
    *
    * <p>
    * A task leaves any effort it was in. A closed effort takes no pending task; a completed one may join it after the
    * fact. Either every task joins, or none does.
    * </p>
    *
    * <pre>
    * ztk effort assign infinijepsen-demo 3
    * ztk effort assign infinijepsen-demo 3 7 a1b2c3d4-…
    * </pre>
    *
    * @since 1.0
    * @author José Bolina
    */
   @CommandDefinition(name = "assign", description = "Join tasks to an effort", generateHelp = true)
   public static final class Assign implements Command<ZtkInvocation> {

      @Argument(description = "The effort's slug, e.g., infinijepsen-demo", required = true,
            completer = EffortCompleter.Open.class, completeFallback = CompletionFallback.NONE)
      String effort;

      @Arguments(description = "The tasks, by ID or UUID", required = true, completer = TaskCompleter.class)
      List<String> tasks;

      @Override
      public CommandResult execute(ZtkInvocation invocation) {
         List<Task> joined = invocation.context().efforts().assign(effort, tasks);
         printTasks(invocation, joined, task -> task.label() + " joined " + task.effort().orElseThrow() + ".");
         return CommandResult.SUCCESS;
      }
   }

   /**
    * {@code ztk effort unassign}: takes tasks out of their effort, making them general work, and prints them.
    *
    * <pre>
    * ztk effort unassign 3
    * ztk effort unassign 3 7
    * </pre>
    *
    * @since 1.0
    * @author José Bolina
    */
   @CommandDefinition(name = "unassign", description = "Take tasks out of their effort", generateHelp = true)
   public static final class Unassign implements Command<ZtkInvocation> {

      @Arguments(description = "The tasks, by ID or UUID", required = true, completer = TaskCompleter.class,
            completeFallback = CompletionFallback.NONE)
      List<String> tasks;

      @Override
      public CommandResult execute(ZtkInvocation invocation) {
         List<Task> left = invocation.context().efforts().unassign(tasks);
         printTasks(invocation, left, task -> task.label() + " is general work.");
         return CommandResult.SUCCESS;
      }
   }
}
