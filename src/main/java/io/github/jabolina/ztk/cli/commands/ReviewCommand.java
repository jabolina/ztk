package io.github.jabolina.ztk.cli.commands;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.CompletionFallback;

import io.github.jabolina.ztk.cli.ZtkInvocation;
import io.github.jabolina.ztk.cli.completers.TaskCompleter;
import io.github.jabolina.ztk.taskwarrior.Task;

/**
 * {@code ztk review}: hands a task over for review, stopping it if it is active.
 *
 * <p>
 * The task joins the current week, so it waits for review in the week listing. Starting it again moves it out of
 * review.
 * </p>
 *
 * <pre>
 * ztk review 3
 * </pre>
 *
 * @since 1.0
 * @author José Bolina
 */
@CommandDefinition(name = "review", description = "Stop a task and tag it for review", generateHelp = true)
public class ReviewCommand implements Command<ZtkInvocation> {

   @Argument(description = "The task ID or UUID", required = true, completer = TaskCompleter.class, completeFallback = CompletionFallback.NONE)
   String reference;

   @Override
   public CommandResult execute(ZtkInvocation invocation) {
      Task task = invocation.context().tasks().review(reference);
      String human = task.label() + " is ready for review." + System.lineSeparator();
      invocation.result(human, task.writeJson(invocation.jsonResult()));
      return CommandResult.SUCCESS;
   }
}
