package io.github.jabolina.ztk.util;

import tools.jackson.databind.node.ObjectNode;

/**
 * A value that describes itself in JSON results.
 *
 * <p>
 * The value writes its fields into an object owned by the caller, so the same description fits a whole result or one
 * entry of a list, next to fields the caller adds.
 * </p>
 *
 * @since 1.0
 * @author José Bolina
 */
public interface JsonWritable {

   /**
    * Writes this value's fields into a JSON object.
    *
    * @param target the object to write into.
    * @return the same {@code target}, for adding further fields.
    */
   ObjectNode writeJson(ObjectNode target);
}
