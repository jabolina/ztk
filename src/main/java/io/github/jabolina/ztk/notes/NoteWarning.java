package io.github.jabolina.ztk.notes;

import java.nio.file.Path;

import io.github.jabolina.ztk.util.JsonWritable;
import tools.jackson.databind.node.ObjectNode;

/**
 * A file of the knowledge base that could not be read as a note, and was skipped.
 *
 * @param path    The absolute path of the file.
 * @param message What is wrong, e.g. {@code Not valid UTF-8, note skipped.}
 * @param hint    What to do to fix it.
 * @since 1.0
 * @author José Bolina
 */
public record NoteWarning(Path path, String message, String hint) implements JsonWritable {

   /**
    * Writes the warning's {@code path} and {@code message}.
    *
    * @param target the JSON object to write into.
    * @return {@code target}, for chaining.
    */
   @Override
   public ObjectNode writeJson(ObjectNode target) {
      target.put("path", path.toString());
      target.put("message", message);
      target.put("hint", hint);
      return target;
   }
}
