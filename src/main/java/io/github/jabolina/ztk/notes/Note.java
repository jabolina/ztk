package io.github.jabolina.ztk.notes;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import io.github.jabolina.ztk.util.JsonWritable;
import tools.jackson.databind.node.ObjectNode;

/**
 * A note of the knowledge base: what ztk knows about it without keeping its body.
 *
 * <p>
 * Every note has a {@link Header}. Types that carry more than that, such as task notes, are their own variant, so a
 * value only exists on the notes that have it.
 * </p>
 *
 * @since 1.0
 * @author José Bolina
 */
public sealed interface Note extends JsonWritable {

   /**
    * What every note has, known before its body is read.
    *
    * @param name     The path relative to {@code data.location}, without {@code .md}, e.g. {@code zettel/20261015-causality}.
    * @param path     The absolute path of the file.
    * @param title    The frontmatter {@code title}, or the file name when there is none.
    * @param modified When the file was last modified; orders search results that are otherwise equal.
    */
   record Header(String name, Path path, String title, Instant modified) { }

   /**
    * A note in a project folder that belongs to a task.
    *
    * @param header The note's common data.
    * @param uuid   The task's UUID, from {@code task_uuid} in the frontmatter.
    */
   record Task(Header header, String uuid) implements Note {

      @Override
      public NoteType type() {
         return NoteType.TASK;
      }

      @Override
      public ObjectNode writeJson(ObjectNode target) {
         return Note.super.writeJson(target)
               .put("task", uuid);
      }
   }

   /**
    * A note whose type carries nothing beyond its header, e.g. a zettel.
    *
    * @param header The note's common data.
    * @param type   The kind of note, from its folder.
    */
   record Plain(Header header, NoteType type) implements Note {
      public Plain {
         // A task note without its task, or a paper without its PDF, would lose what makes it that type.
         if (type == NoteType.TASK || type == NoteType.PAPER) {
            throw new IllegalArgumentException("Task and paper notes have their own variants: use Note.Task or Note.Paper");
         }
      }
   }

   /**
    * A note under the papers folder.
    *
    * @param header The note's common data.
    * @param pdf    The absolute path of the paper's PDF, from {@code pdf} in the frontmatter, which is relative to the
    *               note; empty when the note has none.
    */
   record Paper(Header header, Optional<Path> pdf) implements Note {

      @Override
      public NoteType type() {
         return NoteType.PAPER;
      }

      @Override
      public ObjectNode writeJson(ObjectNode target) {
         ObjectNode json = Note.super.writeJson(target);
         // Null rather than left out, so every paper result has the same fields.
         return pdf.map(file -> json.put("pdf", file.toString()))
               .orElseGet(() -> json.putNull("pdf"));
      }
   }

   /**
    * @return the node ID, e.g. {@code note:zettel/20261015-causality}.
    */
   default String id() {
      return "note:" + header().name();
   }

   /**
    * @return the note's common data.
    */
   Header header();

   /**
    * @return the kind of note, from its folder.
    */
   NoteType type();

   /**
    * Writes the note's {@code id}, {@code path}, {@code type} and {@code title}; variants add their own fields.
    *
    * @param target the JSON object to write into.
    * @return {@code target}, for chaining.
    */
   @Override
   default ObjectNode writeJson(ObjectNode target) {
      Header header = header();
      target.put("id", "note:" + header.name());
      target.put("path", header.path().toString());
      target.put("type", type().label());
      target.put("title", header.title());
      return target;
   }
}
