package io.github.jabolina.ztk.notes;

import java.nio.file.Path;

import io.github.jabolina.ztk.util.JsonWritable;
import tools.jackson.databind.node.ObjectNode;

/**
 * A zettel, a note that belongs to no task.
 *
 * @param path the absolute path of the zettel.
 * @param uuid the identifier in the zettel's frontmatter, independent of its file name.
 * @since 1.0
 * @author José Bolina
 */
public record Zettel(Path path, String uuid) implements JsonWritable {

   /**
    * Writes the zettel's {@code path} and {@code uuid}.
    *
    * @param target the JSON object to write into.
    * @return {@code target}, for chaining.
    */
   @Override
   public ObjectNode writeJson(ObjectNode target) {
      target.put("path", path.toString());
      target.put("uuid", uuid);
      return target;
   }
}
