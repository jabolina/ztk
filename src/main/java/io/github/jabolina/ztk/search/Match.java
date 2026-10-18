package io.github.jabolina.ztk.search;

import java.util.List;

import io.github.jabolina.ztk.notes.Line;
import io.github.jabolina.ztk.notes.Note;
import io.github.jabolina.ztk.util.JsonWritable;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A note found by a search, with the lines that matched.
 *
 * @param note  The note containing every term.
 * @param titleMatched Whether the title contains a term.
 * @param count        How many body lines contain a term.
 * @param lines The first body lines containing any term, in file order; empty when only the title matched.
 * @since 1.0
 * @author José Bolina
 */
public record Match(Note note, boolean titleMatched, int count, List<Line> lines) implements JsonWritable {

   /**
    * Writes the note's fields and its matching {@code lines}, each with its {@code line} number and {@code text}.
    *
    * @param target the JSON object to write into.
    * @return {@code target}, for chaining.
    */
   @Override
   public ObjectNode writeJson(ObjectNode target) {
      note.writeJson(target);
      ArrayNode array = target.putArray("lines");
      for (Line line : lines) {
         array.addObject()
               .put("line", line.number())
               .put("text", line.text());
      }
      return target;
   }
}
