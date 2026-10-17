package io.github.jabolina.ztk.cli.commands;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.CompletionFallback;

import io.github.jabolina.ztk.cli.ArgumentGuard;
import io.github.jabolina.ztk.cli.ProjectCompleter;
import io.github.jabolina.ztk.cli.ZtkInvocation;
import io.github.jabolina.ztk.tasks.Listing;

/**
 * {@code ztk week}: lists the tasks planned for the week, with tasks waiting for review highlighted.
 *
 * <pre>
 * ztk week
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

   @Override
   public CommandResult execute(ZtkInvocation invocation) {
      Listing week = invocation.context().tasks().week(project);
      invocation.result(() -> render(invocation, week), week.writeJson(invocation.jsonResult()));
      return CommandResult.SUCCESS;
   }

   private static String render(ZtkInvocation invocation, Listing week) {
      // Taskwarrior fails a report that matches nothing
      if (week.tasks().isEmpty()) {
         return "No tasks in the week. Run 'ztk start <id>' to bring one in." + System.lineSeparator();
      }
      return invocation.context().store().render(week.report());
   }
}
