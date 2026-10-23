package io.github.jabolina.ztk.cli.commands;

import java.nio.file.Path;
import java.util.List;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.CompletionFallback;

import io.github.jabolina.ztk.cli.ZtkInvocation;
import io.github.jabolina.ztk.cli.completers.TaskCompleter;
import io.github.jabolina.ztk.journal.Session;
import io.github.jabolina.ztk.taskwarrior.Task;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code ztk info}: shows a task, the path of its note, and its last sessions.
 *
 * <p>
 * Read-only: it never creates or links a note. The path is what editors, scripts and agents need to work on the note
 * with their own tools.
 * </p>
 *
 * <p>
 * The sessions are what an agent picking up the task reads first: what was done, where things are, and what is next.
 * </p>
 *
 * <pre>
 * ztk info 3
 * nvim "$(ztk info 3 | jq -r .note)"
 * ztk info 3 | jq '.sessions[0].next'
 * </pre>
 *
 * @since 1.0
 * @author José Bolina
 */
@CommandDefinition(name = "info", description = "Show a task information, its note path and its last sessions.", generateHelp = true)
public class InfoCommand implements Command<ZtkInvocation> {

   // Enough to pick up the work; older sessions are in the journal.
   private static final int SESSIONS = 3;

   @Argument(description = "The task ID or UUID", required = true, completer = TaskCompleter.class, completeFallback = CompletionFallback.NONE)
   String reference;

   @Override
   public CommandResult execute(ZtkInvocation invocation) {
      Task task = invocation.context().store().get(reference);
      StringBuilder human = new StringBuilder();
      ObjectNode json = task.writeJson(invocation.jsonResult());
      describe(invocation, task, human, json);
      sessions(invocation.context().journal().last(task, SESSIONS), human, json.putArray("sessions"));
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

   /**
    * Describes the last sessions of a task, for both output modes.
    *
    * @param sessions the sessions, newest first.
    * @param human the human output, which receives each session's heading and parts.
    * @param json the {@code sessions} array, which receives each session.
    */
   private static void sessions(List<Session> sessions, StringBuilder human, ArrayNode json) {
      if (sessions.isEmpty()) {
         human.append("  No sessions.").append(System.lineSeparator());
         return;
      }
      human.append("  Sessions:").append(System.lineSeparator());
      for (Session session : sessions) {
         session.writeJson(json.addObject());
         human.append("    ").append(session.id()).append(" · ").append(session.title()).append(System.lineSeparator());
         part(human, "Done", session.done());
         part(human, "Now", session.now());
         part(human, "Next", session.next());
      }
   }

   /**
    * Describes one part of a session for people.
    *
    * @param human the human output, which receives the part under its label.
    * @param label the part's label, e.g. {@code Done}.
    * @param text the part's text; each of its lines after the first is indented under the label.
    */
   private static void part(StringBuilder human, String label, String text) {
      human.append("      ").append(label).append(": ")
            .append(text.replace("\n", System.lineSeparator() + "        "))
            .append(System.lineSeparator());
   }
}
