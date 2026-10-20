package io.github.jabolina.ztk.planning;

import java.nio.file.Path;
import java.util.Optional;

import io.github.jabolina.ztk.util.JsonWritable;
import tools.jackson.databind.node.ObjectNode;

/**
 * An effort, as read from its note.
 *
 * @param slug        The effort's slug, from the file name, e.g. {@code infinijepsen-demo}.
 * @param title       The frontmatter {@code title}, or the file name when there is none.
 * @param status      The frontmatter {@code status}; empty when it is missing or names no effort status.
 * @param closed      The frontmatter {@code closed}, the day the effort closed, e.g. {@code 2026-12-15}.
 * @param goal        The frontmatter {@code goal}, the ID of the quarterly goal it moves forward.
 * @param quarter     The quarter of its goal, read from the goal's ID; empty when the goal names no quarter.
 * @param carriedOver Whether the effort is neither done nor dropped although its goal's quarter has ended.
 * @param path        The absolute path of the note.
 * @since 1.0
 * @author José Bolina
 */
public record Effort(String slug, String title, Optional<EffortStatus> status, Optional<String> closed,
                     Optional<String> goal, Optional<Period.OfQuarter> quarter, boolean carriedOver,
                     Path path) implements JsonWritable {

   /**
    * How efforts are named in JSON results and the graph, e.g. {@code effort:infinijepsen-demo}; accepted as input too.
    */
   static final String PREFIX = "effort:";

   /**
    * Writes the effort's {@code id}, {@code title}, {@code status}, {@code closed}, {@code goal}, {@code quarter},
    * {@code carriedOver} and {@code path}.
    *
    * @param target the JSON object to write into.
    * @return {@code target}, for chaining.
    */
   @Override
   public ObjectNode writeJson(ObjectNode target) {
      target.put("id", PREFIX + slug);
      target.put("title", title);
      // Null rather than left out, so every effort has the same fields; a status naming no effort status is null too.
      target.put("status", status.map(EffortStatus::label).orElse(null));
      target.put("closed", closed.orElse(null));
      // A node ID, like the effort's own, so it can be fed straight back.
      target.put("goal", goal.map(value -> Goal.PREFIX + value).orElse(null));
      target.put("quarter", quarter.map(Period.OfQuarter::toString).orElse(null));
      target.put("carriedOver", carriedOver);
      target.put("path", path.toString());
      return target;
   }
}
