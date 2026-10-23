package io.github.jabolina.ztk.cli.commands;

import java.time.Year;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.CompletionFallback;
import org.aesh.command.option.Option;

import io.github.jabolina.ztk.ZtkException;
import io.github.jabolina.ztk.cli.ZtkInvocation;
import io.github.jabolina.ztk.cli.completers.GoalCompleter;
import io.github.jabolina.ztk.cli.completers.PeriodCompleter;
import io.github.jabolina.ztk.cli.renderer.Table;
import io.github.jabolina.ztk.planning.Efforts;
import io.github.jabolina.ztk.planning.GoalStatus;
import io.github.jabolina.ztk.planning.Goals;
import io.github.jabolina.ztk.planning.Period;
import io.github.jabolina.ztk.planning.Saved;
import io.github.jabolina.ztk.util.Labeled;

/**
 * {@code ztk goal}: commands for yearly and quarterly goals.
 *
 * @since 1.0
 * @author José Bolina
 */
@CommandDefinition(name = "goal", description = "Plan yearly and quarterly goals", generateHelp = true,
      groupCommands = { GoalCommand.New.class, GoalCommand.Set.class, GoalCommand.ListGoals.class, GoalCommand.Info.class })
public class GoalCommand implements Command<ZtkInvocation> {

   // One goal per row, quarterly goals indented under the yearly goal put right before them.
   private static final Table<Goals.Listed> TABLE_RENDERER = Table.<Goals.Listed>builder()
         .column("ID", listed -> (listed.goal().period() instanceof Period.OfQuarter ? "  " : "") + listed.goal().id())
         .column("STATUS", listed -> listed.goal().status().map(GoalStatus::label).orElse("invalid")
               + (listed.goal().needsClosing() ? ", needs closing" : ""))
         .column("EFFORTS", listed -> String.valueOf(listed.efforts()))
         .column("TITLE", listed -> listed.goal().title())
         .build();

   @Override
   public CommandResult execute(ZtkInvocation invocation) {
      invocation.println(invocation.getHelpInfo());
      return CommandResult.SUCCESS;
   }

   /**
    * Reads a goal status as typed on the command line.
    *
    * @param label the status, e.g. {@code reached}; one of the option's allowed values.
    * @return the status.
    */
   private static GoalStatus status(String label) {
      return Labeled.parse(GoalStatus.class, label).orElseThrow();
   }

   /**
    * {@code ztk goal new}: creates a goal from its template and prints its note's path.
    *
    * <p>
    * The goal is open and numbered after the highest goal of its year or quarter. A quarterly goal names the yearly
    * goal it moves forward. Only the path goes to stdout, which makes the command usable in shell substitutions.
    * </p>
    *
    * <pre>
    * ztk goal new 2026 --title "Infinispan provably correct"
    * ztk goal new 2026 --title "Infinispan provably correct" --slug provably-correct
    * ztk goal new 2026-Q4 --title "Fix found data loss bugs" --parent 2026-G1
    * </pre>
    *
    * @since 1.0
    * @author José Bolina
    */
   @CommandDefinition(name = "new", description = "Create a goal from its template and print its note's path",
         generateHelp = true)
   public static final class New implements Command<ZtkInvocation> {

      @Argument(description = "The year of a yearly goal, e.g., 2026, or the quarter of a quarterly goal, e.g., 2026-Q4",
            required = true, completeFallback = CompletionFallback.NONE)
      String period;

      @Option(description = "The goal's title", required = true)
      String title;

      @Option(description = "The yearly goal a quarterly goal moves forward, e.g., 2026-G1", completer = GoalCompleter.Yearly.class)
      String parent;

      @Option(description = "The end of the file name, e.g., provably-correct; derived from the title when not given")
      String slug;

      @Override
      public CommandResult execute(ZtkInvocation invocation) {
         Saved created = invocation.context().goals()
               .create(new Goals.Details(Period.parse(period), title, Optional.ofNullable(parent), Optional.ofNullable(slug)));

         // Human messages redirected to stderr, the result goes to stdout.
         invocation.printErr("Created " + created.id() + ".");
         invocation.result(created.path() + System.lineSeparator(), created.writeJson(invocation.jsonResult()));
         return CommandResult.SUCCESS;
      }
   }

   /**
    * {@code ztk goal set}: changes a goal's status, title or parent, and prints its node ID.
    *
    * <p>
    * Only the goal's frontmatter changes, never its body or file name. Closing a goal dates it in {@code closed};
    * reopening it removes that date. A closed goal takes no new quarterly goals until it is reopened.
    * </p>
    *
    * <pre>
    * ztk goal set 2026-G1 --status reached
    * ztk goal set 2026-Q4-G1 --title "Fix every found data loss bug"
    * ztk goal set 2026-Q4-G1 --parent 2026-G2
    * </pre>
    *
    * @since 1.0
    * @author José Bolina
    */
   @CommandDefinition(name = "set", description = "Change a goal's status, title or parent", generateHelp = true)
   public static final class Set implements Command<ZtkInvocation> {

      @Argument(description = "The goal, e.g., 2026-G1 or 2026-Q4-G1", required = true, completer = GoalCompleter.class, completeFallback = CompletionFallback.NONE)
      String goal;

      // The labels of GoalStatus, which a completion test keeps in step.
      @Option(description = "The goal's new status", allowedValues = { "open", "reached", "missed", "dropped" })
      String status;

      @Option(description = "The goal's new title; its file name stays")
      String title;

      @Option(description = "The new yearly goal of a quarterly goal, e.g., 2026-G2", completer = GoalCompleter.Yearly.class)
      String parent;

      @Override
      public CommandResult execute(ZtkInvocation invocation) {
         if (status == null && title == null && parent == null) {
            throw ZtkException.usage("Nothing to change. Give --status, --title or --parent, e.g. ztk goal set "
                  + goal + " --status reached");
         }
         Optional<GoalStatus> newStatus = Optional.ofNullable(status).map(GoalCommand::status);

         Saved saved = invocation.context().goals()
               .update(goal, new Goals.Changes(newStatus, Optional.ofNullable(title), Optional.ofNullable(parent)));

         // Human messages redirected to stderr, the result goes to stdout.
         invocation.printErr("Updated " + saved.id() + ".");
         invocation.result(saved.id() + System.lineSeparator(), saved.writeJson(invocation.jsonResult()));
         return CommandResult.SUCCESS;
      }
   }

   /**
    * {@code ztk goal list}: lists the goals of a year or quarter, each yearly goal followed by its quarterly goals.
    *
    * <p>
    * Without a period, the current year is listed. A quarter also lists the yearly goals its goals move forward. Goals
    * still open after their period ended are marked as needing closing.
    * </p>
    *
    * <pre>
    * ztk goal list
    * ztk goal list 2026-Q4
    * ztk goal list 2026 --status open
    * </pre>
    *
    * @since 1.0
    * @author José Bolina
    */
   @CommandDefinition(name = "list", description = "List the goals of a year or quarter", generateHelp = true)
   public static final class ListGoals implements Command<ZtkInvocation> {

      @Argument(description = "The year or quarter, e.g., 2026 or 2026-Q4; the current year when not given", completer = PeriodCompleter.class, completeFallback = CompletionFallback.NONE)
      String period;

      // The labels of GoalStatus, which a completion test keeps in step.
      @Option(description = "Only list goals with this status", allowedValues = { "open", "reached", "missed", "dropped" })
      String status;

      @Override
      public CommandResult execute(ZtkInvocation invocation) {
         Period listed = period == null ? new Period.OfYear(Year.now()) : Period.parse(period);
         EnumSet<GoalStatus> statuses = status == null ? EnumSet.noneOf(GoalStatus.class) : EnumSet.of(status(status));

         Goals.Listing listing = invocation.context().goals().list(listed, statuses);

         // Rendered only in human mode.
         invocation.result(() -> listing.goals().isEmpty()
                     ? "No goals in " + listed + ". Create one with: ztk goal new " + listed + " --title \"…\"" + System.lineSeparator()
                     : TABLE_RENDERER.render(listing.goals()),
               listing.writeJson(invocation.jsonResult()));
         return CommandResult.SUCCESS;
      }
   }

   /**
    * {@code ztk goal info}: shows one goal with what moves it forward.
    *
    * <p>
    * A yearly goal is shown with its quarterly goals and their efforts; a quarterly goal with its efforts, each with
    * its tasks counted. A quarterly goal names its yearly goal as its parent.
    * </p>
    *
    * <pre>
    * ztk goal info 2026-G1
    * ztk goal info 2026-Q4-G1
    * </pre>
    *
    * @since 1.0
    * @author José Bolina
    */
   @CommandDefinition(name = "info", description = "Show a goal with the goals that move it forward", generateHelp = true)
   public static final class Info implements Command<ZtkInvocation> {

      @Argument(description = "The goal, e.g., 2026-G1 or 2026-Q4-G1", required = true, completer = GoalCompleter.class, completeFallback = CompletionFallback.NONE)
      String goal;

      @Override
      public CommandResult execute(ZtkInvocation invocation) {
         Goals.Info info = invocation.context().goals().info(goal);

         // Rendered only in human mode: the goal's row, then its quarterly goals; then the efforts under them.
         invocation.result(() -> {
                  String goals = TABLE_RENDERER.render(info.nodes().map(Goals.Info::listed).toList());
                  List<Efforts.Listed> efforts = info.nodes().flatMap(node -> node.efforts().stream()).toList();
                  return efforts.isEmpty() ? goals : goals + System.lineSeparator() + EffortCommand.TABLE_RENDERER.render(efforts);
               },
               info.writeJson(invocation.jsonResult()));
         return CommandResult.SUCCESS;
      }
   }
}
