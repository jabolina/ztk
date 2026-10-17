package io.github.jabolina.ztk.cli.commands;

import java.util.List;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;

import io.github.jabolina.ztk.cli.ZtkInvocation;
import io.github.jabolina.ztk.taskwarrior.Task;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code ztk current}: lists the tasks being worked on, with the path of their notes.
 *
 * <p>
 * Read-only: it never creates or links a note.
 * </p>
 *
 * <pre>
 * ztk current
 * ztk current | jq -r '.tasks[0].note'
 * </pre>
 *
 * @since 1.0
 * @author José Bolina
 */
@CommandDefinition(name = "current", description = "list the active tasks and their notes", generateHelp = true)
public class CurrentCommand implements Command<ZtkInvocation> {
   @Override
   public CommandResult execute(ZtkInvocation invocation) {
      StringBuilder human = new StringBuilder();
      ObjectNode json = invocation.jsonResult();
      ArrayNode tasks = json.putArray("tasks");

      // In theory, manipulating tasks outside ztk could allow multiple active tasks.
      List<Task> active = invocation.context().tasks().current();
      for (Task task : active) {
         InfoCommand.describe(invocation, task, human, task.writeJson(tasks.addObject()));
      }
      if (active.isEmpty()) {
         human.append("No task is active. Run 'ztk start <id>' to start one.").append(System.lineSeparator());
      }
      invocation.result(human.toString(), json);
      return CommandResult.SUCCESS;
   }
}

