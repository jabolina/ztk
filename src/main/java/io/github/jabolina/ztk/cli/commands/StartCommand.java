package io.github.jabolina.ztk.cli.commands;

import java.nio.file.Path;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.CompletionFallback;
import org.aesh.command.option.Option;

import io.github.jabolina.ztk.ZtkException;
import io.github.jabolina.ztk.cli.Editor;
import io.github.jabolina.ztk.cli.ZtkInvocation;
import io.github.jabolina.ztk.cli.completers.TaskCompleter;
import io.github.jabolina.ztk.tasks.TaskNote;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code ztk start}: starts working on a task and prints its note path, creating the note if the task has none.
 *
 * <p>
 * Only one task is active at a time: starting a task while another one is active fails, naming the active task.
 * Starting the active task again succeeds and prints the same path. Only the note path goes to stdout, which makes the
 * command usable in shell substitutions.
 * </p>
 *
 * <pre>
 * ztk start 3
 * NOTE=$(ztk start 3)
 * </pre>
 *
 * @since 1.0
 * @author José Bolina
 */
@CommandDefinition(name = "start", description = "Start a task and print its note path", generateHelp = true)
public class StartCommand implements Command<ZtkInvocation> {

   @Argument(description = "The task ID or UUID", required = true, completer = TaskCompleter.class, completeFallback = CompletionFallback.NONE)
   String reference;

   @Option(name = "editor", shortName = 'e', hasValue = false, description = "Also open the note in the configured editor")
   boolean editor;

   @Override
   public CommandResult execute(ZtkInvocation invocation) {
      // Checked before starting: failing afterwards would leave the task started without the note being opened.
      if (editor && !invocation.context().interactive()) {
         throw ZtkException.usage("Cannot open the editor: ztk is not running in an interactive session."
               + System.lineSeparator()
               + "Run 'ztk start " + reference + "' without --editor to start the task and print its note path.");
      }

      TaskNote started = invocation.context().tasks().start(reference);
      Path note = started.note();

      // Human message redirected to stderr, results to stdout.
      invocation.printErr("Started " + started.task().label() + ".");

      ObjectNode json = started.task().writeJson(invocation.jsonResult());
      json.put("note", note.toString());
      invocation.result(note + System.lineSeparator(), json);

      // The task was started and the note path is printed.
      // Now, if requested by the user, we open the configured editor pointing to the note file.
      if (editor) {
         Editor.open(invocation.context().configuration().editor(), note);
      }
      return CommandResult.SUCCESS;
   }
}
