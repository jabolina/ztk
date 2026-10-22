package io.github.jabolina.ztk.cli.commands;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.CompletionFallback;
import org.aesh.command.option.Option;

import io.github.jabolina.ztk.ZtkException;
import io.github.jabolina.ztk.cli.ZtkInvocation;
import io.github.jabolina.ztk.cli.completers.TaskCompleter;
import io.github.jabolina.ztk.journal.Session;
import io.github.jabolina.ztk.taskwarrior.Task;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code ztk session}: commands for the sessions of the journal.
 *
 * @since 1.0
 * @author José Bolina
 */
@CommandDefinition(name = "session", description = "Record work sessions in the journal", generateHelp = true,
      groupCommands = { SessionCommand.Add.class })
public class SessionCommand implements Command<ZtkInvocation> {

   @Override
   public CommandResult execute(ZtkInvocation invocation) {
      invocation.println(invocation.getHelpInfo());
      return CommandResult.SUCCESS;
   }

   /**
    * {@code ztk session add}: writes a session on a task at the end of today's journal, and prints the journal's path.
    *
    * <p>
    * Written by an agent at the end of its work on a task, for the next agent to pick up from. The task is always
    * named, active or not. Only the path goes to stdout, which makes the command usable in shell substitutions.
    * </p>
    *
    * <pre>
    * ztk session add 3 --title "Review round 1" --done "Addressed the comments." --now "Waiting for round 2." --next "Rebase on main."
    * </pre>
    *
    * @since 1.0
    * @author José Bolina
    */
   @CommandDefinition(name = "add", description = "Write a session on a task in today's journal and print its path",
         generateHelp = true)
   public static final class Add implements Command<ZtkInvocation> {

      // The conventional marker for "read this value from stdin".
      private static final String STDIN = "-";
      // Past this, a part is likely drifting into what the task note is for.
      private static final int MAX_LINES = 3;

      @Argument(description = "The task ID or UUID", required = true, completer = TaskCompleter.class, completeFallback = CompletionFallback.NONE)
      String reference;

      @Option(description = "What the session was about, in one line", required = true)
      String title;

      @Option(description = "What was done", required = true)
      String done;

      @Option(description = "Where things are", required = true)
      String now;

      @Option(description = "What comes next", required = true)
      String next;

      @Override
      public CommandResult execute(ZtkInvocation invocation) {
         Task task = invocation.context().store().get(reference);
         SequencedMap<String, String> parts = parts(invocation);
         // Too long is still written: a session lost at the end of an agent's work costs more than a long one.
         parts.forEach((flag, value) -> {
            long lines = value.lines().count();
            if (lines > MAX_LINES) {
               invocation.printErr("warning: " + flag + " is " + lines + " lines. Each part is a few lines;"
                     + " details belong in the task note.");
            }
         });
         Session session = invocation.context().journal()
               .add(task, parts.get("--title"), parts.get("--done"), parts.get("--now"), parts.get("--next"));

         ObjectNode json = invocation.jsonResult();
         json.put("id", session.id());
         json.put("path", session.path().toString());
         json.put("task", session.task());

         // Human messages redirected to stderr, the result goes to stdout.
         invocation.printErr("Session " + session.id() + " of " + task.label() + " written.");
         invocation.result(session.path() + System.lineSeparator(), json);
         return CommandResult.SUCCESS;
      }

      /**
       * Gathers the parts of the session as given, the one given as {@code -} read from stdin.
       *
       * @param invocation the running command, which reads stdin.
       * @return each part's text by its flag, in the order they are written; the title first.
       * @throws ZtkException with {@link ZtkException.Kind#USAGE} when more than one part is {@code -}, a part is
       *                      blank, or the title is more than one line.
       */
      private SequencedMap<String, String> parts(ZtkInvocation invocation) {
         SequencedMap<String, String> parts = new LinkedHashMap<>();
         parts.put("--title", title);
         parts.put("--done", done);
         parts.put("--now", now);
         parts.put("--next", next);

         List<String> piped = parts.entrySet().stream()
               .filter(part -> part.getValue().equals(STDIN))
               .map(Map.Entry::getKey)
               .toList();
         // Stdin can be read only once.
         if (piped.size() > 1) {
            throw ZtkException.usage("Only one part can be read from stdin, but " + String.join(" and ", piped)
                  + " are all '-'. Give the others as text.");
         }
         piped.forEach(flag -> parts.put(flag, invocation.stdin().strip()));

         parts.forEach((flag, value) -> {
            if (value.isBlank()) {
               throw ZtkException.usage(flag + " is blank: every part of a session says something."
                     + " Run 'ztk session add --help' to see what each part holds.");
            }
         });
         // The title is the session's heading, which ends at the line's end.
         if (parts.get("--title").lines().count() > 1) {
            throw ZtkException.usage("--title must be one line, as it is the session's heading."
                  + " Put the rest in --done, --now or --next.");
         }
         return parts;
      }
   }
}
