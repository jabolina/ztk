package io.github.jabolina.ztk.planning;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import io.github.jabolina.ztk.taskwarrior.Task;
import io.github.jabolina.ztk.util.Labeled;

/**
 * Something that breaks the rules of the plan, with what to do about it.
 *
 * <p>
 * Each rule is its own variant, carrying what it is about; its code is how agents filter and match it. The same
 * problems are reported by {@code check} and refuse the changes that would cause them.
 * </p>
 *
 * @since 1.0
 * @author José Bolina
 */
public sealed interface PlanProblem {

   /**
    * @return what is wrong, e.g. {@code unknown-goal}.
    */
   String code();

   /**
    * @return whether it is an error, which breaks the plan; otherwise it is a warning, likely not as meant.
    */
   boolean error();

   /**
    * @return what is wrong, naming what it is about.
    */
   String message();

   /**
    * @return what to do to fix it.
    */
   String hint();

   /**
    * @return the node ID of the goal, effort or note it is found in; empty for a task, which the caller names.
    */
   Optional<String> id();

   /**
    * @return the file to open to fix it; empty for a task, whose note the caller knows.
    */
   Optional<Path> path();

   /**
    * @return the task it is found in; empty when it is found in a note.
    */
   default Optional<Task> task() {
      return Optional.empty();
   }

   /**
    * @return the ID of what it names besides where it is found, e.g. the goal an effort points to; empty when it
    *         names nothing else.
    */
   default String names() {
      return "";
   }

   /**
    * Tells the problems of two versions of the plan apart: a problem with the same key in both is the same problem,
    * even when what it is found in has changed.
    *
    * @return its code, where it is found, and what it names.
    */
   default String key() {
      String where = id().or(() -> task().map(Task::uuid)).orElse("");
      return code() + " " + where + " " + names();
   }

   /**
    * @param item a goal or effort.
    * @return the command changing it, e.g. {@code ztk goal set 2026-Q4-G1}.
    */
   private static String set(PlanItem item) {
      return switch (item) {
         case Goal goal -> "ztk goal set " + goal.id();
         case Effort effort -> "ztk effort set " + effort.slug();
      };
   }

   /**
    * @param item a quarterly goal or an effort.
    * @return what it must point to: a yearly goal of its year for a quarterly goal, a quarterly goal for an effort.
    */
   private static String wanted(PlanItem item) {
      return switch (item) {
         case Goal goal -> "yearly goal of " + goal.period().year();
         case Effort effort -> "quarterly goal";
      };
   }

   /**
    * @param item a quarterly goal or an effort.
    * @return how to point it at the right goal, and where to find one.
    */
   private static String pointAt(PlanItem item) {
      return switch (item) {
         case Goal goal -> "Point it at a " + wanted(item) + ": " + set(item) + " --parent <goal ID>. "
               + "List them with: ztk goal list " + goal.period().year();
         case Effort effort -> "Point it at a " + wanted(item) + ": " + set(item) + " --goal <goal ID>. "
               + "List them with: ztk goal list";
      };
   }

   /**
    * @param statuses the statuses of a kind, e.g. {@link GoalStatus}.
    * @param closed whether to keep the closed statuses, or the open ones.
    * @param <S> the kind of status.
    * @return their labels, as a command line choice, e.g. {@code reached|missed|dropped}.
    */
   private static <S extends Enum<S> & PlanningStatus> String choice(Class<S> statuses, boolean closed) {
      return Arrays.stream(statuses.getEnumConstants())
            .filter(status -> status.closed() == closed)
            .map(Labeled::label)
            .collect(Collectors.joining("|"));
   }

   /**
    * A problem with a goal or effort, found in its own note.
    */
   sealed interface InItem extends PlanProblem {

      /**
       * @return the goal or effort.
       */
      PlanItem item();

      @Override
      default Optional<String> id() {
         return Optional.of(item().nodeId());
      }

      @Override
      default Optional<Path> path() {
         return Optional.of(item().path());
      }
   }

   /**
    * A problem with a task, found in the task itself.
    */
   sealed interface InTask extends PlanProblem {

      /**
       * @return the task, never empty.
       */
      @Override
      Optional<Task> task();

      @Override
      default Optional<String> id() {
         return Optional.empty();
      }

      @Override
      default Optional<Path> path() {
         return Optional.empty();
      }
   }

   /**
    * A goal or effort whose status is missing, or not one of its kind's.
    *
    * @param item The goal or effort.
    */
   record InvalidStatus(PlanItem item) implements InItem {

      @Override
      public String code() {
         return "invalid-status";
      }

      @Override
      public boolean error() {
         return true;
      }

      @Override
      public String message() {
         String kind = switch (item) {
            case Goal goal -> "goal";
            case Effort effort -> "effort";
         };
         return "Its status is missing, or not a " + kind + " status.";
      }

      @Override
      public String hint() {
         List<String> statuses = switch (item) {
            case Goal goal -> Labeled.labels(GoalStatus.class);
            case Effort effort -> Labeled.labels(EffortStatus.class);
         };
         return "Set it: " + set(item) + " --status <" + String.join("|", statuses) + ">";
      }
   }

   /**
    * A quarterly goal without {@code parent}, or an effort without {@code goal}.
    *
    * @param item The quarterly goal or effort.
    */
   record MissingGoal(PlanItem item) implements InItem {

      @Override
      public String code() {
         return "missing-goal";
      }

      @Override
      public boolean error() {
         return true;
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
   record UnknownGoal(PlanItem item, String named, boolean exists) implements InItem {

      @Override
      public String names() {
         return named;
      }

      @Override
      public String code() {
         return "unknown-goal";
      }

      @Override
      public boolean error() {
         return true;
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
   record DuplicateGoal(Goal item, List<Path> others) implements InItem {

      @Override
      public String code() {
         return "duplicate-goal";
      }

      @Override
      public boolean error() {
         return true;
      }

      @Override
      public String message() {
         return "Its goal ID " + item.id() + " is also the ID of "
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
    * A quarterly goal or an effort still open under a goal that is closed.
    *
    * @param item   The open quarterly goal or effort.
    * @param parent The closed goal it points to.
    */
   record OpenUnderClosed(PlanItem item, Goal parent) implements InItem {

      @Override
      public String names() {
         return parent.id();
      }

      @Override
      public String code() {
         return "open-under-closed";
      }

      @Override
      public boolean error() {
         return true;
      }

      @Override
      public String message() {
         // Only reported for a goal with a closed status, so it has one.
         String level = item instanceof Goal ? "yearly goal " : "goal ";
         return "Still open, but its " + level + parent.id() + " is " + parent.status().orElseThrow().label() + ".";
      }

      @Override
      public String hint() {
         String close = switch (item) {
            case Goal goal -> set(item) + " --status <" + choice(GoalStatus.class, true) + ">";
            case Effort effort -> set(item) + " --status <" + choice(EffortStatus.class, true) + ">";
         };
         String move = set(item) + (item instanceof Goal ? " --parent" : " --goal") + " <goal ID>";
         return "Close it: " + close + "; move it: " + move + "; or reopen " + parent.id() + ": ztk goal set "
               + parent.id() + " --status " + GoalStatus.OPEN.label();
      }
   }

   /**
    * A goal still open after its period ended.
    *
    * @param item The goal.
    */
   record NeedsClosing(Goal item) implements InItem {

      @Override
      public String code() {
         return "needs-closing";
      }

      @Override
      public boolean error() {
         return false;
      }

      @Override
      public String message() {
         return "Still open, but " + item.period() + " ended on " + item.period().end() + ".";
      }

      @Override
      public String hint() {
         // Whether it was reached, missed or dropped is for the user to say.
         return "Close it: " + set(item) + " --status <" + choice(GoalStatus.class, true) + ">";
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
   record ClosedMismatch(PlanItem item, String status, Optional<String> closed) implements InItem {

      @Override
      public String code() {
         return "closed-mismatch";
      }

      @Override
      public boolean error() {
         return false;
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
    * A task naming an effort that does not exist, done tasks included.
    *
    * @param named The task.
    */
   record UnknownEffort(Task named) implements InTask {

      @Override
      public Optional<Task> task() {
         return Optional.of(named);
      }

      @Override
      public String names() {
         return named.effort().orElseThrow();
      }

      @Override
      public String code() {
         return "unknown-effort";
      }

      @Override
      public boolean error() {
         return true;
      }

      @Override
      public String message() {
         return named.label() + " names effort " + named.effort().orElseThrow() + ", which no effort has.";
      }

      @Override
      public String hint() {
         // The full UUID: ztk takes no short one, and done tasks have no number.
         return "Move it to an effort: ztk effort assign <slug> " + named.uuid() + ", or out of any: ztk effort "
               + "unassign " + named.uuid();
      }
   }

   /**
    * A task still open in an effort that is done or dropped.
    *
    * @param open   The task.
    * @param effort Its effort.
    */
   record OpenTaskUnderClosed(Task open, Effort effort) implements InTask {

      @Override
      public Optional<Task> task() {
         return Optional.of(open);
      }

      @Override
      public String names() {
         return effort.slug();
      }

      @Override
      public String code() {
         return "open-under-closed";
      }

      @Override
      public boolean error() {
         return true;
      }

      @Override
      public String message() {
         // Only reported for an effort with a closed status, so it has one.
         return open.label() + " is still open in effort " + effort.slug() + ", which is "
               + effort.status().orElseThrow().label() + ".";
      }

      @Override
      public String hint() {
         return "Finish it: task " + open.uuid().substring(0, 8) + " done; move it: ztk effort assign <slug> "
               + open.uuid() + "; or reopen the effort: ztk effort set " + effort.slug() + " --status "
               + EffortStatus.ACTIVE.label();
      }
   }

   /**
    * A note sitting where goals do, directly in a year's or a quarter's folder, but not named after a goal ID of it.
    *
    * @param file   The note's absolute path.
    * @param noteId The note's node ID, as it is read as a plain note.
    */
   record InvalidGoalName(Path file, String noteId) implements PlanProblem {

      @Override
      public String code() {
         return "invalid-goal-name";
      }

      @Override
      public boolean error() {
         return true;
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
         return Optional.of(noteId);
      }

      @Override
      public Optional<Path> path() {
         return Optional.of(file);
      }
   }
}
