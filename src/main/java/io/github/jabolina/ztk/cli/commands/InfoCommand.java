package io.github.jabolina.ztk.cli.commands;

import java.nio.file.Path;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.CompletionFallback;

import io.github.jabolina.ztk.cli.ZtkInvocation;
import io.github.jabolina.ztk.cli.completers.TaskCompleter;
import io.github.jabolina.ztk.taskwarrior.Task;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code ztk info}: shows a task and the path of its note.
 *
 * <p>
 * Read-only: it never creates or links a note. The path is what editors, scripts and agents need to work on the note
 * with their own tools.
 * </p>
 *
 * <pre>
 * ztk info 3
 * nvim "$(ztk info 3 | jq -r .note)"
 * </pre>
 *
 * @since 1.0
 * @author José Bolina
 */
@CommandDefinition(name = "info", description = "Show a task information and its note path.")
public class InfoCommand implements Command<ZtkInvocation> {

   @Argument(description = "The task ID or UUID", required = true, completer = TaskCompleter.class, completeFallback = CompletionFallback.NONE)
   String reference;

   @Override
   public CommandResult execute(ZtkInvocation invocation) {
      Task task = invocation.context().store().get(reference);
      StringBuilder human = new StringBuilder();
      ObjectNode json = task.writeJson(invocation.jsonResult());
      describe(invocation, task, human, json);
      invocation.result(human.toString(), json);
      return CommandResult.SUCCESS;
   }

   /**
    * Describes a task and the path of its note, for both output modes.
    *
    * @param invocation the running command, which knows where the knowledge base is.
    * @param task the task to describe.
    * @param human the human output, which receives the task label and its note line.
    * @param json the task's JSON entry, which receives the absolute {@code note} path, or {@code null} without a note.
    */
   static void describe(ZtkInvocation invocation, Task task, StringBuilder human, ObjectNode json) {
      human.append(task.label()).append(System.lineSeparator());
      if (task.note().isPresent()) {
         Path note = invocation.context().configuration().dataLocation().resolve(task.note().get());
         human.append("  Note: ").append(note).append(System.lineSeparator());
         json.put("note", note.toString());
      } else {
         // Created or started outside ztk.
         // Starting it through ztk creates or links the note file.
         human.append("  No note. Run 'ztk start ").append(task.id()).append("' to create or link it.")
               .append(System.lineSeparator());
         json.putNull("note");
      }
   }
}
