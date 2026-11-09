package io.github.jabolina.ztk.cli.commands;

import java.util.List;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.CompletionFallback;
import org.aesh.command.option.OptionList;

import io.github.jabolina.ztk.cli.ArgumentGuard;
import io.github.jabolina.ztk.cli.ZtkInvocation;
import io.github.jabolina.ztk.cli.completers.ProjectCompleter;
import io.github.jabolina.ztk.cli.completers.TaskCompleter;
import io.github.jabolina.ztk.tasks.Listing;

/**
 * {@code ztk week}: lists the tasks planned for the week, with tasks waiting for review highlighted, after putting
 * tasks in it or taking tasks out of it when asked.
 *
 * <pre>
 * ztk week
 * ztk week --add 12,15
 * ztk week --drop 7
 * ztk week | jq -r '.tasks[].description'
 * </pre>
 *
 * @since 1.0
 * @author José Bolina
 */
@CommandDefinition(name = "week", description = "List the tasks planned for the week", generateHelp = true)
public class WeekCommand implements Command<ZtkInvocation> {

   @Argument(description = "Only list this project's tasks", validator = ArgumentGuard.class, completer = ProjectCompleter.class,
         completeFallback = CompletionFallback.NONE)
   String project;

   @OptionList(description = "Put these tasks in the week, comma-separated IDs or UUIDs, e.g., '12,15'",
         completer = TaskCompleter.class)
   List<String> add;

   @OptionList(description = "Take these tasks out of the week, comma-separated IDs or UUIDs, e.g., '7'",
         completer = TaskCompleter.class)
   List<String> drop;

   @Override
   public CommandResult execute(ZtkInvocation invocation) {
      // Both remain null when not given.
      invocation.context().tasks().changeWeek(add == null ? List.of() : add, drop == null ? List.of() : drop);
      Listing week = invocation.context().tasks().week(project);
      invocation.result(() -> render(invocation, week), week.writeJson(invocation.jsonResult()));
      return CommandResult.SUCCESS;
   }

   private static String render(ZtkInvocation invocation, Listing week) {
      // Taskwarrior fails a report that matches nothing
      if (week.tasks().isEmpty()) {
         return "No tasks in the week. Run 'ztk week --add <id>' to plan one, or 'ztk start <id>' to start one." + System.lineSeparator();
      }
      return invocation.context().store().render(week.report());
   }
}
