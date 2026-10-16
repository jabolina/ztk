package io.github.jabolina.ztk.cli.commands;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;

import io.github.jabolina.ztk.cli.ZtkInvocation;
import io.github.jabolina.ztk.taskwarrior.Task;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code ztk review}: hands a task over for review, stopping it if it is active.
 *
 * <p>
 * The task keeps its other tags, so a task of the current week stays in the week while it waits for review. Starting
 * it again moves it out of review.
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

   @Argument(description = "The task ID or UUID", required = true)
   String reference;

   @Override
   public CommandResult execute(ZtkInvocation invocation) {
      Task task = invocation.context().tasks().review(reference);
      String human = "Task " + task.id() + " ('" + task.description() + "') is ready for review."
            + System.lineSeparator();
      ObjectNode json = invocation.jsonResult();
      json.put("id", task.id());
      json.put("uuid", task.uuid());
      json.put("description", task.description());
      invocation.result(human, json);
      return CommandResult.SUCCESS;
   }
}
