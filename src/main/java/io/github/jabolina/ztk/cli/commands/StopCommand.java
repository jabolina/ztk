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
 * {@code ztk stop}: stops working on every active task, and lists the tasks it stopped.
 *
 * <p>
 * Having nothing to stop is not an error. The tasks stay in the week; use {@code ztk review} or {@code ztk done} to
 * move them on.
 * </p>
 *
 * <pre>
 * ztk stop
 * ztk stop &amp;&amp; ztk week
 * </pre>
 *
 * @since 1.0
 * @author José Bolina
 */
@CommandDefinition(name = "stop", description = "Stop every active task", generateHelp = true)
public class StopCommand implements Command<ZtkInvocation> {

   @Override
   public CommandResult execute(ZtkInvocation invocation) {
      List<Task> stopped = invocation.context().tasks().stop();

      StringBuilder human = new StringBuilder();
      ObjectNode json = invocation.jsonResult();
      ArrayNode tasks = json.putArray("tasks");
      for (Task task : stopped) {
         human.append("Stopped ")
               .append(task.label())
               .append(System.lineSeparator());

         task.writeJson(tasks.addObject());
      }

      if (stopped.isEmpty()) {
         human.append("No task is active, nothing to stop.").append(System.lineSeparator());
      }
      invocation.result(human.toString(), json);
      return CommandResult.SUCCESS;
   }
}
