package io.github.jabolina.ztk.cli.commands;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Arguments;
import org.aesh.command.option.CompletionFallback;
import org.aesh.command.option.Option;

import io.github.jabolina.ztk.ZtkException;
import io.github.jabolina.ztk.cli.ArgumentGuard;
import io.github.jabolina.ztk.cli.ZtkInvocation;
import io.github.jabolina.ztk.cli.completers.AttributeCompleter;
import io.github.jabolina.ztk.cli.completers.EffortCompleter;
import io.github.jabolina.ztk.tasks.TaskNote;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code ztk add}: creates a task in Taskwarrior together with its note, and prints the note path.
 *
 * <p>
 * The words are passed to Taskwarrior as given, so any Taskwarrior attribute works. A {@code project:<name>} is
 * required, because the note lives in the project folder. With {@code --effort}, the task joins an effort, which must
 * exist and be open. Only the note path goes to stdout, which makes the command usable in shell substitutions.
 * </p>
 *
 * <pre>
 * ztk add Fix flaky test project:Infinispan
 * ztk add "Review PR" project:Hyperfoil +week issue:GH-847
 * ztk add Write demo project:Infinispan --effort infinijepsen-demo
 * NOTE=$(ztk add Write spec project:ztk)
 * </pre>
 *
 * @since 1.0
 * @author José Bolina
 */
@CommandDefinition(name = "add", description = "Add a task and create its note", generateHelp = true)
public class AddCommand implements Command<ZtkInvocation> {

   // The effort attribute as Taskwarrior takes it, abbreviations included: effort:x, effo:x, … ef:x.
   private static final Pattern EFFORT_ATTRIBUTE = Pattern.compile("ef(?:f(?:o(?:r(?:t)?)?)?)?:(.*)");

   @Arguments(description = "The description and Taskwarrior attributes, e.g. 'fix bug project:Alpha +week'",
         validator = ArgumentGuard.class, required = true, completer = AttributeCompleter.class, completeFallback = CompletionFallback.NONE)
   List<String> words;

   @Option(description = "The effort the task joins, e.g., infinijepsen-demo; it must exist and be open",
         completer = EffortCompleter.Open.class)
   String effort;

   @Override
   public CommandResult execute(ZtkInvocation invocation) {
      for (String word : words) {
         Matcher attribute = EFFORT_ATTRIBUTE.matcher(word);
         // Taskwarrior would set it unchecked: the effort might not exist, or be closed.
         if (attribute.matches()) {
            throw ZtkException.usage("Join the task to an effort with --effort " + attribute.group(1) + " instead of "
                  + word + ", so ztk checks the effort exists and is open.");
         }
      }
      TaskNote added = effort == null
            ? invocation.context().tasks().add(words)
            : invocation.context().efforts().add(effort, words);
      Path note = added.note();

      // Human messages redirected to stderr, the result will go stdout.
      invocation.printErr("Created task " + added.task().id() + ".");

      ObjectNode json = added.task().writeJson(invocation.jsonResult());
      json.put("note", note.toString());
      invocation.result(note + System.lineSeparator(), json);
      return CommandResult.SUCCESS;
   }
}
