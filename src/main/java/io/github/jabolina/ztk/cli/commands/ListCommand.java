package io.github.jabolina.ztk.cli.commands;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.CompletionFallback;

import io.github.jabolina.ztk.ZtkException;
import io.github.jabolina.ztk.cli.ArgumentGuard;
import io.github.jabolina.ztk.cli.ProjectCompleter;
import io.github.jabolina.ztk.cli.ZtkInvocation;
import io.github.jabolina.ztk.tasks.Listing;

@CommandDefinition(name = "list", description = "List the pending tasks, most urgent first", generateHelp = true)
public class ListCommand implements Command<ZtkInvocation> {

   @Argument(description = "Only list this project's tasks", validator = ArgumentGuard.class, completer = ProjectCompleter.class,
         completeFallback = CompletionFallback.NONE)
   String project;

   @Override
   public CommandResult execute(ZtkInvocation invocation) {
      Listing list = invocation.context().tasks().list(project);
      invocation.result(() -> render(invocation, list), list.writeJson(invocation.jsonResult()));
      return CommandResult.SUCCESS;
   }

   /**
    * Shows the list for people.
    *
    * @param invocation the running command, giving access to the store.
    * @param list the tasks and their report.
    * @return Taskwarrior's table, or a hint when no task matches.
    * @throws ZtkException with {@link ZtkException.Kind#TASKWARRIOR} when Taskwarrior fails to render the report.
    */
   private String render(ZtkInvocation invocation, Listing list) {
      // Taskwarrior fails a report that matches nothing.
      if (list.tasks().isEmpty()) {
         String scope = project == null ? "" : " in project " + project;
         String example = project == null ? "<name>" : project;
         return "No pending tasks" + scope + ". Run 'ztk add \"<description>\" project:" + example
               + "' to create one." + System.lineSeparator();
      }
      return invocation.context().store().render(list.report());
   }
}
