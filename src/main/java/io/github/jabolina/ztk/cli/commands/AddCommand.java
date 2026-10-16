package io.github.jabolina.ztk.cli.commands;

import java.nio.file.Path;
import java.util.List;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Arguments;

import io.github.jabolina.ztk.cli.ArgumentGuard;
import io.github.jabolina.ztk.cli.ZtkInvocation;
import io.github.jabolina.ztk.tasks.TaskNote;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code ztk add}: creates a task in Taskwarrior together with its note, and prints the note path.
 *
 * <p>
 * The words are passed to Taskwarrior as given, so any Taskwarrior attribute works. A {@code project:<name>} is
 * required, because the note lives in the project folder. Only the note path goes to stdout, which makes the command
 * usable in shell substitutions.
 * </p>
 *
 * <pre>
 * ztk add Fix flaky test project:Infinispan
 * ztk add "Review PR" project:Hyperfoil +week issue:GH-847
 * NOTE=$(ztk add Write spec project:ztk)
 * </pre>
 *
 * @since 1.0
 * @author José Bolina
 */
@CommandDefinition(name = "add", description = "Add a task and create its note", generateHelp = true)
public class AddCommand implements Command<ZtkInvocation> {

   @Arguments(description = "The description and Taskwarrior attributes, e.g. 'fix bug project:Alpha +week'",
         validator = ArgumentGuard.class, required = true)
   List<String> words;

   @Override
   public CommandResult execute(ZtkInvocation invocation) {
      TaskNote added = invocation.context().tasks().add(words);
      Path note = added.note();

      // Human messages redirected to stderr, the result will go stdout.
      invocation.printErr("Created task " + added.task().id() + ".");

      ObjectNode json = added.task().writeJson(invocation.jsonResult());
      json.put("path", note.toString());
      invocation.result(note + System.lineSeparator(), json);
      return CommandResult.SUCCESS;
   }
}
