package io.github.jabolina.ztk.graph;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

import io.github.jabolina.ztk.notes.Note;
import io.github.jabolina.ztk.taskwarrior.Task;
import io.github.jabolina.ztk.util.JsonWritable;
import tools.jackson.databind.node.ObjectNode;

/**
 * One point of the graph: a note, a task, a project, or a wikilink target nothing has.
 *
 * @since 1.0
 * @author José Bolina
 */
public sealed interface Node extends JsonWritable {

   /**
    * @return the node ID, e.g. {@code note:zettel/20261015-causality} or {@code task:<uuid>}.
    */
   String id();

   /**
    * @return what the node is, the prefix of its ID: {@code note}, {@code task}, {@code project} or {@code dangling}.
    */
   String kind();

   /**
    * @return what people call it: a note's title, a task's description, a project's name, a dangling link's target.
    */
   String title();

   /**
    * @return the file to open for it: the note itself, the task's note, or the project's folder; empty when there is
    *         none.
    */
   Optional<Path> path();

   /**
    * A note as a node.
    *
    * @param note The note.
    */
   record OfNote(Note note) implements Node {

      @Override
      public String id() {
         return note.id();
      }

      @Override
      public String kind() {
         return "note";
      }

      @Override
      public String title() {
         return note.header().title();
      }

      @Override
      public Optional<Path> path() {
         return Optional.of(note.header().path());
      }

      /**
       * Writes the note's fields as {@code search} prints them, with its {@code kind} after its {@code id}.
       *
       * @param target the JSON object to write into.
       * @return {@code target}, for chaining.
       */
      @Override
      public ObjectNode writeJson(ObjectNode target) {
         // Written first to set the key order: the note writes its id again, which keeps the position.
         target.put("id", id());
         target.put("kind", kind());
         return note.writeJson(target);
      }
   }

   /**
    * A Taskwarrior task as a node.
    *
    * @param task The task.
    * @param note Its note, when the task names one that exists.
    */
   record OfTask(Task task, Optional<Note> note) implements Node {

      @Override
      public String id() {
         return "task:" + task.uuid();
      }

      @Override
      public String kind() {
         return "task";
      }

      @Override
      public String title() {
         return task.description();
      }

      @Override
      public Optional<Path> path() {
         return note.map(found -> found.header().path());
      }

      /**
       * Writes the task's {@code id}, {@code kind}, {@code title}, {@code path} and {@code status}.
       *
       * @param target the JSON object to write into.
       * @return {@code target}, for chaining.
       */
      @Override
      public ObjectNode writeJson(ObjectNode target) {
         target.put("id", id());
         target.put("kind", kind());
         target.put("title", title());
         // Null rather than left out, so every task has the same fields.
         target.put("path", path().map(Path::toString).orElse(null));
         // Lowercase, as Taskwarrior prints it.
         target.put("status", task.status().name().toLowerCase(Locale.ROOT));
         return target;
      }
   }

   /**
    * A Taskwarrior project as a node.
    *
    * @param name   The project name, e.g. {@code Alpha}.
    * @param folder The folder holding its notes, when it has one.
    */
   record OfProject(String name, Optional<Path> folder) implements Node {

      @Override
      public String id() {
         return "project:" + name;
      }

      @Override
      public String kind() {
         return "project";
      }

      @Override
      public String title() {
         return name;
      }

      @Override
      public Optional<Path> path() {
         return folder;
      }

      /**
       * Writes the project's {@code id}, {@code kind}, {@code title} and {@code path}.
       *
       * @param target the JSON object to write into.
       * @return {@code target}, for chaining.
       */
      @Override
      public ObjectNode writeJson(ObjectNode target) {
         target.put("id", id());
         target.put("kind", kind());
         target.put("title", title());
         // Null rather than left out, so every node has a path field.
         target.put("path", folder.map(Path::toString).orElse(null));
         return target;
      }
   }

   /**
    * A wikilink target no note or task has: the note still to write.
    *
    * <p>
    * One node per target, however many notes link to it.
    * </p>
    *
    * @param target The wikilink target, e.g. {@code 20261020-vector-clocks}.
    */
   record OfDangling(String target) implements Node {

      @Override
      public String id() {
         return "dangling:" + target;
      }

      @Override
      public String kind() {
         return "dangling";
      }

      @Override
      public String title() {
         return target;
      }

      @Override
      public Optional<Path> path() {
         // Where the note would be written depends on who writes it.
         return Optional.empty();
      }

      /**
       * Writes the target's {@code id}, {@code kind}, {@code title} and {@code path}.
       *
       * @param target the JSON object to write into.
       * @return {@code target}, for chaining.
       */
      @Override
      public ObjectNode writeJson(ObjectNode target) {
         target.put("id", id());
         target.put("kind", kind());
         target.put("title", title());
         // Null rather than left out, so every node has a path field.
         target.putNull("path");
         return target;
      }
   }
}
