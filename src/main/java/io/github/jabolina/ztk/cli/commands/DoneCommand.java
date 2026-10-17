package io.github.jabolina.ztk.cli.commands;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.CompletionFallback;

import io.github.jabolina.ztk.cli.TaskCompleter;
import io.github.jabolina.ztk.cli.ZtkInvocation;
import io.github.jabolina.ztk.taskwarrior.Task;

@CommandDefinition(name = "done", description = "Complete a task", generateHelp = true)
public class DoneCommand implements Command<ZtkInvocation> {

   @Argument(description = "The task ID or UUID", required = true, completer = TaskCompleter.class, completeFallback = CompletionFallback.NONE)
   String reference;

   @Override
   public CommandResult execute(ZtkInvocation invocation) {
      Task task = invocation.context().tasks().done(reference);

      String human = "Completed " + task.label() + "." + System.lineSeparator();
      invocation.result(human, task.writeJson(invocation.jsonResult()));
      return CommandResult.SUCCESS;
   }
}
