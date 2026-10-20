package io.github.jabolina.ztk.planning;

import java.nio.file.Path;

import io.github.jabolina.ztk.util.JsonWritable;
import tools.jackson.databind.node.ObjectNode;

/**
 * A goal or effort just created or changed.
 *
 * @param id   The node ID, e.g. {@code goal:2026-G1} or {@code effort:infinijepsen-demo}.
 * @param path The absolute path of its note.
 * @since 1.0
 * @author José Bolina
 */
public record Saved(String id, Path path) implements JsonWritable {

   /**
    * Writes the {@code id} and {@code path}.
    *
    * @param target the JSON object to write into.
    * @return {@code target}, for chaining.
    */
   @Override
   public ObjectNode writeJson(ObjectNode target) {
      target.put("id", id);
      target.put("path", path.toString());
      return target;
   }
}
