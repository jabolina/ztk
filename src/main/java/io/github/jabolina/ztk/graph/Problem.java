package io.github.jabolina.ztk.graph;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import io.github.jabolina.ztk.notes.Note;
import io.github.jabolina.ztk.notes.NoteWarning;
import io.github.jabolina.ztk.notes.Wikilink;
import io.github.jabolina.ztk.util.JsonWritable;
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
   enum Severity {
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
      target.put("severity", severity().name().toLowerCase(Locale.ROOT));
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
}
