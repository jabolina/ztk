package io.github.jabolina.ztk.graph;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import io.github.jabolina.ztk.notes.Note;
import io.github.jabolina.ztk.notes.NoteWarning;
import io.github.jabolina.ztk.notes.Wikilink;
import io.github.jabolina.ztk.planning.EffortStatus;
import io.github.jabolina.ztk.planning.GoalStatus;
import io.github.jabolina.ztk.taskwarrior.Task;
import io.github.jabolina.ztk.util.JsonWritable;
import io.github.jabolina.ztk.util.Labeled;
import tools.jackson.databind.node.ObjectNode;

/**
 * Something wrong in the knowledge base, with what to do about it.
 *
 * <p>
 * Each kind of problem is its own variant, carrying what it is about; its code is how agents filter and match it.
 * </p>
 *
 * @since 1.0
 * @author José Bolina
 */
public sealed interface Problem extends JsonWritable {

   /**
    * How much a problem matters.
    */
   enum Severity implements Labeled {
      /**
       * Something is broken: {@code check} fails until it is fixed.
       */
      ERROR,

      /**
       * Something works, but likely not as meant.
       */
      WARNING
   }

   /**
    * @return what is wrong, e.g. {@code dangling-link}.
    */
   String code();

   /**
    * @return how much it matters.
    */
   Severity severity();

   /**
    * @return what is wrong, naming what it is about.
    */
   String message();

   /**
    * @return what to do to fix it.
    */
   String hint();

   /**
    * @return the node ID of the note or task it is found in; empty when it is in no node.
    */
   default Optional<String> id() {
      return Optional.empty();
   }

   /**
    * @return the file to open to fix it; empty when there is none.
    */
   default Optional<Path> path() {
      return Optional.empty();
   }

   /**
    * @return the line it is on in its file; empty when it is about the whole file, or there is no file.
    */
   default Optional<Integer> line() {
      return Optional.empty();
   }

   /**
    * @return the node IDs it could be about, to choose from; empty unless it is ambiguous.
    */
   default List<String> candidates() {
      return List.of();
   }

   /**
    * Writes the problem's {@code code}, {@code severity}, {@code id}, {@code path}, {@code line}, {@code message},
    * {@code hint} and {@code candidates}.
    *
    * @param target the JSON object to write into.
    * @return {@code target}, for chaining.
    */
   @Override
   default ObjectNode writeJson(ObjectNode target) {
      target.put("code", code());
      // Lowercase, as the codes are.
      target.put("severity", severity().label());
      // Null rather than left out, so every problem has the same fields.
      target.put("id", id().orElse(null));
      target.put("path", path().map(Path::toString).orElse(null));
      target.put("line", line().orElse(null));
      target.put("message", message());
      target.put("hint", hint());
      if (candidates().isEmpty()) {
         target.putNull("candidates");
      } else {
         candidates().forEach(target.putArray("candidates")::add);
      }
      return target;
   }

   /**
    * @param item a goal or effort.
    * @return the command changing it, e.g. {@code ztk goal set 2026-Q4-G1}.
    */
   private static String set(Node.Planned item) {
      return switch (item) {
         case Node.OfGoal goal -> "ztk goal set " + goal.goal().id();
         case Node.OfEffort effort -> "ztk effort set " + effort.effort().slug();
      };
   }

   /**
    * @param item a quarterly goal or an effort.
    * @return what it must point to: a yearly goal of its year for a quarterly goal, a quarterly goal for an effort.
    */
   private static String wanted(Node.Planned item) {
      return switch (item) {
         case Node.OfGoal goal -> "yearly goal of " + goal.goal().period().year();
         case Node.OfEffort effort -> "quarterly goal";
      };
   }

   /**
    * @param item a quarterly goal or an effort.
    * @return how to point it at the right goal, and where to find one.
    */
   private static String pointAt(Node.Planned item) {
      return switch (item) {
         case Node.OfGoal goal -> "Point it at a " + wanted(item) + ": " + set(item) + " --parent <goal ID>. "
               + "List them with: ztk goal list " + goal.goal().period().year();
         case Node.OfEffort effort -> "Point it at a " + wanted(item) + ": " + set(item) + " --goal <goal ID>. "
               + "List them with: ztk goal list";
      };
   }

   /**
    * A problem with a wikilink, found where the wikilink is written.
    */
   sealed interface InLink extends Problem {

      /**
       * @return the note the wikilink is written in.
       */
      Note from();

      /**
       * @return the wikilink.
       */
      Wikilink wikilink();

      @Override
      default Optional<String> id() {
         return Optional.of(from().id());
      }

      @Override
      default Optional<Path> path() {
         return Optional.of(from().header().path());
      }

      @Override
      default Optional<Integer> line() {
         return Optional.of(wikilink().line());
      }
   }

   /**
    * A wikilink to a name no note or task has.
    *
    * @param from     The note it is written in.
    * @param wikilink The wikilink.
    */
   record DanglingLink(Note from, Wikilink wikilink) implements InLink {

      @Override
      public String code() {
         return "dangling-link";
      }

      @Override
      public Severity severity() {
         return Severity.ERROR;
      }

      @Override
      public String message() {
         return "Links to " + wikilink.target() + ", which no note or task has.";
      }

      @Override
      public String hint() {
         return "Write a note named " + wikilink.target() + ".md, or correct the link.";
      }
   }

   /**
    * A wikilink to a name several notes have, none in the linking note's folder: it leads to all of them.
    *
    * @param from     The note it is written in.
    * @param wikilink The wikilink.
    * @param to       The notes it leads to, two or more.
    */
   record AmbiguousLink(Note from, Wikilink wikilink, List<Node> to) implements InLink {

      @Override
      public String code() {
         return "ambiguous-link";
      }

      @Override
      public Severity severity() {
         return Severity.WARNING;
      }

      @Override
      public String message() {
         return "Links to " + wikilink.target() + ", which " + to.size() + " notes have; it leads to all of them.";
      }

      @Override
      public String hint() {
         // Wikilinks name no folder, so only names, or the linking note's own folder, tell the notes apart.
         return "Rename all but one of the candidates, or move the one meant into this note's folder.";
      }

      @Override
      public List<String> candidates() {
         // Sorted, so the output is the same on every run.
         return to.stream().map(Node::id).sorted().toList();
      }
   }

   /**
    * A problem with a task, found in the task itself.
    */
   sealed interface InTask extends Problem {

      /**
       * @return the task.
       */
      Node.OfTask task();

      @Override
      default Optional<String> id() {
         return Optional.of(task().id());
      }

      @Override
      default Optional<Path> path() {
         // Its note when one is found, e.g. through task_uuid.
         return task().path();
      }
   }

   /**
    * An open task whose note attribute names a file that is not a readable note.
    *
    * @param task The task.
    */
   record MissingNote(Node.OfTask task) implements InTask {

      @Override
      public String code() {
         return "missing-note";
      }

      @Override
      public Severity severity() {
         return Severity.ERROR;
      }

      @Override
      public String message() {
         return task.task().label() + " names note " + task.task().note().orElseThrow()
               + ", which is not a readable note of the knowledge base.";
      }

      @Override
      public String hint() {
         // The short UUID, as task numbers change when tasks are done.
         return "Restore the file, or point the task at its note: task " + task.task().uuid().substring(0, 8)
               + " modify note:<path relative to the knowledge base>";
      }
   }

   /**
    * An open task without a project.
    *
    * @param task The task.
    */
   record TaskWithoutProject(Node.OfTask task) implements InTask {

      @Override
      public String code() {
         return "task-without-project";
      }

      @Override
      public Severity severity() {
         return Severity.ERROR;
      }

      @Override
      public String message() {
         return task.task().label() + " has no project.";
      }

      @Override
      public String hint() {
         return "Give it one: task " + task.task().uuid().substring(0, 8) + " modify project:<name>";
      }
   }

   /**
    * A task note whose {@code task_uuid} names no task, e.g. a deleted one.
    *
    * @param note The note.
    */
   record OrphanTaskNote(Note.Task note) implements Problem {

      @Override
      public String code() {
         return "orphan-task-note";
      }

      @Override
      public Severity severity() {
         return Severity.WARNING;
      }

      @Override
      public String message() {
         return "Its task_uuid is " + note.uuid() + ", which no task has.";
      }

      @Override
      public String hint() {
         return "Correct task_uuid, or remove it if the task was deleted.";
      }

      @Override
      public Optional<String> id() {
         return Optional.of(note.id());
      }

      @Override
      public Optional<Path> path() {
         return Optional.of(note.header().path());
      }
   }

   /**
    * A file of the knowledge base that could not be read as a note, and was skipped.
    *
    * @param warning Why it was skipped, and how to fix it.
    */
   record UnreadableNote(NoteWarning warning) implements Problem {

      @Override
      public String code() {
         return "unreadable-note";
      }

      @Override
      public Severity severity() {
         return Severity.WARNING;
      }

      @Override
      public String message() {
         return warning.message();
      }

      @Override
      public String hint() {
         return warning.hint();
      }

      // No ID: never read, so it is no node.

      @Override
      public Optional<Path> path() {
         return Optional.of(warning.path());
      }
   }

   /**
    * A problem with a goal or effort, found in its own note.
    */
   sealed interface InPlan extends Problem {

      /**
       * @return the goal or effort.
       */
      Node.Planned item();

      @Override
      default Optional<String> id() {
         return Optional.of(item().id());
      }

      @Override
      default Optional<Path> path() {
         return item().path();
      }
   }

   /**
    * A goal or effort whose status is missing, or not one of its kind's.
    *
    * @param item The goal or effort.
    */
   record InvalidStatus(Node.Planned item) implements InPlan {

      @Override
      public String code() {
         return "invalid-status";
      }

      @Override
      public Severity severity() {
         return Severity.ERROR;
      }

      @Override
      public String message() {
         return "Its status is missing, or not a " + item.kind() + " status.";
      }

      @Override
      public String hint() {
         List<String> statuses = switch (item) {
            case Node.OfGoal goal -> Labeled.labels(GoalStatus.class);
            case Node.OfEffort effort -> Labeled.labels(EffortStatus.class);
         };
         return "Set it: " + set(item) + " --status <" + String.join("|", statuses) + ">";
      }
   }

   /**
    * A quarterly goal without {@code parent}, or an effort without {@code goal}.
    *
    * @param item The quarterly goal or effort.
    */
   record MissingGoal(Node.Planned item) implements InPlan {

      @Override
      public String code() {
         return "missing-goal";
      }

      @Override
      public Severity severity() {
         return Severity.ERROR;
      }

      @Override
      public String message() {
         return "Points to no " + wanted(item) + ".";
      }

      @Override
      public String hint() {
         return pointAt(item);
      }
   }

   /**
    * A quarterly goal or an effort pointing to a goal that does not exist, or is not of the level or year it must be.
    *
    * @param item   The quarterly goal or effort.
    * @param named  The goal ID it points to, e.g. {@code 2026-Q4-G9}.
    * @param exists Whether a goal has that ID, of the wrong level or year.
    */
   record UnknownGoal(Node.Planned item, String named, boolean exists) implements InPlan {

      @Override
      public String code() {
         return "unknown-goal";
      }

      @Override
      public Severity severity() {
         return Severity.ERROR;
      }

      @Override
      public String message() {
         return exists
               ? "Points to " + named + ", which is not a " + wanted(item) + "."
               : "Points to " + named + ", which no goal has; it must be a " + wanted(item) + ".";
      }

      @Override
      public String hint() {
         return pointAt(item);
      }
   }

   /**
    * A goal whose ID another goal's file has too.
    *
    * @param item   One of the goals sharing the ID.
    * @param others The other files with that ID.
    */
   record DuplicateGoal(Node.OfGoal item, List<Path> others) implements InPlan {

      @Override
      public String code() {
         return "duplicate-goal";
      }

      @Override
      public Severity severity() {
         return Severity.ERROR;
      }

      @Override
      public String message() {
         return "Its goal ID " + item.goal().id() + " is also the ID of "
               + others.stream().map(Path::toString).collect(Collectors.joining(", ")) + ".";
      }

      @Override
      public String hint() {
         // ztk numbers new goals after the highest in use, so any free number will do.
         return "Rename all but one to an unused number of their period, then point what named the renamed ones at "
               + "their new IDs.";
      }
   }

   /**
    * A task naming an effort that does not exist, done tasks included.
    *
    * @param task The task.
    */
   record UnknownEffort(Node.OfTask task) implements InTask {

      @Override
      public String code() {
         return "unknown-effort";
      }

      @Override
      public Severity severity() {
         return Severity.ERROR;
      }

      @Override
      public String message() {
         return task.task().label() + " names effort " + task.task().effort().orElseThrow()
               + ", which no effort has.";
      }

      @Override
      public String hint() {
         // The full UUID: ztk takes no short one, and done tasks have no number.
         String uuid = task.task().uuid();
         return "Move it to an effort: ztk effort assign <slug> " + uuid + ", or out of any: ztk effort unassign "
               + uuid;
      }
   }

   /**
    * A task still open in an effort that is done or dropped.
    *
    * @param task   The task.
    * @param effort Its effort.
    */
   record OpenTaskInClosedEffort(Node.OfTask task, Node.OfEffort effort) implements InTask {

      @Override
      public String code() {
         return "open-task-in-closed-effort";
      }

      @Override
      public Severity severity() {
         return Severity.WARNING;
      }

      @Override
      public String message() {
         // Only reported for an effort with a closed status, so it has one.
         return task.task().label() + " is still open in effort " + effort.effort().slug() + ", which is "
               + effort.effort().status().orElseThrow().label() + ".";
      }

      @Override
      public String hint() {
         Task open = task.task();
         return "Finish it: task " + open.uuid().substring(0, 8) + " done; move it: ztk effort assign <slug> "
               + open.uuid() + "; or reopen the effort: ztk effort set " + effort.effort().slug() + " --status active";
      }
   }

   /**
    * A goal still open after its period ended.
    *
    * @param item The goal.
    */
   record NeedsClosing(Node.OfGoal item) implements InPlan {

      @Override
      public String code() {
         return "needs-closing";
      }

      @Override
      public Severity severity() {
         return Severity.WARNING;
      }

      @Override
      public String message() {
         return "Still open, but " + item.goal().period() + " ended on " + item.goal().period().end() + ".";
      }

      @Override
      public String hint() {
         // Whether it was reached, missed or dropped is for the user to say.
         return "Close it: " + set(item) + " --status <" + Arrays.stream(GoalStatus.values())
               .filter(GoalStatus::closed)
               .map(GoalStatus::label)
               .collect(Collectors.joining("|")) + ">";
      }
   }

   /**
    * A goal or effort whose {@code closed} date does not agree with its status: a date on an open status, or none on a
    * closed one.
    *
    * @param item   The goal or effort.
    * @param status Its status, e.g. {@code reached}.
    * @param closed Its {@code closed} date; empty when it has none.
    */
   record ClosedMismatch(Node.Planned item, String status, Optional<String> closed) implements InPlan {

      @Override
      public String code() {
         return "closed-mismatch";
      }

      @Override
      public Severity severity() {
         return Severity.WARNING;
      }

      @Override
      public String message() {
         return closed
               .map(date -> "Its status is " + status + ", which is open, but it is dated closed: " + date + ".")
               .orElseGet(() -> "Its status is " + status + ", which is closed, but it has no closed date.");
      }

      @Override
      public String hint() {
         // Setting the status again writes or removes the date, as the status needs.
         return closed
               .map(date -> "Clear the date: " + set(item) + " --status " + status)
               .orElseGet(() -> "Date it today: " + set(item) + " --status " + status
                     + ", or write the day it closed as closed: YYYY-MM-DD.");
      }
   }

   /**
    * A note sitting where goals do, directly in a year's or a quarter's folder, but not named after a goal ID of it.
    *
    * @param note The note, read as a plain note.
    */
   record InvalidGoalName(Note note) implements Problem {

      @Override
      public String code() {
         return "invalid-goal-name";
      }

      @Override
      public Severity severity() {
         return Severity.ERROR;
      }

      @Override
      public String message() {
         return "Sits directly in a goal folder, but its name does not start with a goal ID of that folder, e.g. "
               + "2026-G1 in goals/2026 or 2026-Q4-G1 in goals/2026/Q4.";
      }

      @Override
      public String hint() {
         return "Rename it to start with an unused goal ID of its folder, or move it into a subfolder to keep it as "
               + "material.";
      }

      @Override
      public Optional<String> id() {
         return Optional.of(note.id());
      }

      @Override
      public Optional<Path> path() {
         return Optional.of(note.header().path());
      }
   }
}
