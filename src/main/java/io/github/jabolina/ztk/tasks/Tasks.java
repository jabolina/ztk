package io.github.jabolina.ztk.tasks;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import io.github.jabolina.ztk.ZtkException;
import io.github.jabolina.ztk.configuration.Configuration;
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
}
