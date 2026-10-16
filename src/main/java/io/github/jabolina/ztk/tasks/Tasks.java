package io.github.jabolina.ztk.tasks;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import io.github.jabolina.ztk.ZtkException;
import io.github.jabolina.ztk.configuration.Configuration;
import io.github.jabolina.ztk.taskwarrior.Status;
import io.github.jabolina.ztk.taskwarrior.Task;
import io.github.jabolina.ztk.taskwarrior.TaskStore;

/**
 * The task use cases offered by ztk.
 *
 * <p>
 * Each operation is complete on its own: callers never coordinate Taskwarrior and the knowledge base themselves.
 * Adding a task, for example, also creates its note and links the two.
 * </p>
 *
 * @since 1.0
 * @author José Bolina
 */
public final class Tasks {

   // Taskwarrior attribute assigning the project. Only the full name is accepted, not abbreviations like "pro:".
   private static final String PROJECT = "project:";

   private final TaskStore store;
   private final TaskNotes notes;

   /**
    * Creates the task use cases.
    *
    * @param configuration the effective configuration, providing the knowledge base root.
    * @param store the task store.
    * @param clock the clock for note names and creation dates.
    */
   public Tasks(Configuration configuration, TaskStore store, Clock clock) {
      this.store = store;
      this.notes = new TaskNotes(configuration, store, clock);
   }

   /**
    * Adds a task and creates its note.
    *
    * @param words the description words and Taskwarrior attributes, e.g. {@code "fix", "bug", "project:Alpha"}.
    * @return the created task, linked to its new note.
    * @throws ZtkException with {@link ZtkException.Kind#VALIDATION} when the words do not assign a project. Nothing is
    *                      written in that case.
    */
   public TaskNote add(List<String> words) {
      requireProject(words);

      Task task = store.add(words);
      Path note = notes.create(task);
      // Re-read: the task returned by add() predates the note link.
      return new TaskNote(store.get(task.uuid()), note);
   }

   /**
    * Starts working on a task: starts it in Taskwarrior and moves it from review to the current week.
    *
    * @param reference the task ID or UUID.
    * @return the started task, as stored by Taskwarrior, with its note.
    * @throws ZtkException with {@link ZtkException.Kind#NOT_FOUND} when no task has that ID or UUID.
    */
   public TaskNote start(String reference) {
      Task task = store.get(reference);
      requireNoOtherActive(task);
      Path note = notes.create(task);
      List<String> week = List.of("+week", "-review");
      if (task.active()) {
         // It requires a distinction between starting a task which is already started.
         store.modify(task, week);
      } else {
         store.start(task, week);
      }
      // Re-read: starting changes the tags and the start time.
      return new TaskNote(store.get(task.uuid()), note);
   }

   /**
    * Stops working on every active task.
    *
    * @return the stopped tasks, as stored by Taskwarrior after stopping. Empty when no task was active.
    */
   public List<Task> stop() {
      List<Task> stopped = new ArrayList<>();
      // Usually a single task, but plain Taskwarrior can start several.
      for (Task active : store.query("+ACTIVE")) {
         store.stop(active);
         // Re-read: stopping removes the start time.
         stopped.add(store.get(active.uuid()));
      }
      return stopped;
   }

   /**
    * Hands a task over for review: stops working on it and tags it for review, keeping it in the current week.
    *
    * @param reference the task ID or UUID.
    * @return the reviewed task, as stored by Taskwarrior.
    * @throws ZtkException with {@link ZtkException.Kind#NOT_FOUND} when no task has that ID or UUID.
    */
   public Task review(String reference) {
      Task task = store.get(reference);
      // Taskwarrior refuses to stop a task that is not started.
      if (task.active())
         store.stop(task);
      store.modify(task, List.of("+review"));
      // Re-read: stopping removes the start time and the tags changed.
      return store.get(task.uuid());
   }

   /**
    * Completes a task and takes it out of the current week, stopping it first if it is active.
    *
    * @param reference the task ID or UUID.
    * @return the completed task, as stored by Taskwarrior. Completed tasks leave the working set, so its ID is
    *         {@code 0}.
    * @throws ZtkException with {@link ZtkException.Kind#NOT_FOUND} when no task has that ID or UUID.
    * @throws ZtkException with {@link ZtkException.Kind#VALIDATION} when the task is already completed.
    */
   public Task done(String reference) {
      Task task = store.get(reference);
      if (task.status() == Status.COMPLETED) {
         throw ZtkException.validation(task.label() + " is already completed, nothing to do."
               + System.lineSeparator()
               + "To reopen it, run 'task " + task.uuid() + " modify status:pending'.");
      }
      // One command: Taskwarrior stops an active task when completing it, and applies the modification atomically.
      store.done(task, List.of("-week"));
      return store.get(task.uuid());
   }

   /**
    * Ensures the words assign a non-empty project, since a task note lives in its project folder.
    *
    * @param words the words given to {@link #add(List)}.
    * @throws ZtkException with {@link ZtkException.Kind#VALIDATION} when no word is {@code project:<name>}.
    */
   private static void requireProject(List<String> words) {
      for (String word : words) {
         if (word.startsWith(PROJECT) && word.length() > PROJECT.length()) {
            return;
         }
      }
      throw ZtkException.validation("A task needs a project: add project:<name>, e.g. ztk add \"Fix bug\" project:Alpha");
   }

   /**
    * Ensures no task other than the given one is being worked on, since only one task is active at a time.
    *
    * @param task the task about to be started.
    * @throws ZtkException with {@link ZtkException.Kind#VALIDATION} when another task is active, naming it and the
    *                      commands that release it.
    */
   private void requireNoOtherActive(Task task) {
      for (Task active : store.query("+ACTIVE")) {
         // The task itself may already be active: starting it again is allowed.
         if (!active.uuid().equals(task.uuid())) {
            throw ZtkException.validation("Cannot start " + task.label() + ": " + active.label() + " is currently active."
                  + System.lineSeparator()
                  + "Run 'ztk stop' to pause it, or 'ztk done " + active.id() + "' to complete it, then start again.");
         }
      }
   }
}
