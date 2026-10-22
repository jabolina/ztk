package io.github.jabolina.ztk.planning;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;

import io.github.jabolina.ztk.util.JsonWritable;
import tools.jackson.databind.node.ObjectNode;

/**
 * A yearly or quarterly goal, as read from its note.
 *
 * @param id           The goal ID, from the file name, e.g. {@code 2026-Q4-G1}.
 * @param period       The year or quarter the goal is for, from its ID.
 * @param title        The frontmatter {@code title}, or the file name when there is none.
 * @param status       The frontmatter {@code status}; empty when it is missing or names no goal status.
 * @param closed       The frontmatter {@code closed}, the day the goal closed, e.g. {@code 2026-09-30}.
 * @param parent       The frontmatter {@code parent}, the ID of a quarterly goal's yearly goal; empty for yearly goals.
 * @param path         The absolute path of the note.
 * @param needsClosing Whether the goal is still open after its period ended.
 * @since 1.0
 * @author José Bolina
 */
public record Goal(String id, Period period, String title, Optional<GoalStatus> status, Optional<String> closed,
                   Optional<String> parent, Path path, boolean needsClosing) implements PlanItem, JsonWritable {

   /**
    * How goals are named in JSON results and the graph, e.g. {@code goal:2026-G1}; accepted as input too.
    */
   static final String PREFIX = "goal:";

   /**
    * The order goals are listed in: yearly goals first, then quarterly goals by quarter, each period by goal number.
    */
   // Within one period IDs differ only in their number, so the shorter is the smaller: G2 before G10.
   public static final Comparator<Goal> ORDER = Comparator.comparing((Goal goal) -> goal.period().toString())
         .thenComparing(goal -> goal.id().length())
         .thenComparing(Goal::id);

   @Override
   public String nodeId() {
      return PREFIX + id;
   }

   @Override
   public boolean open() {
      return status.filter(value -> !value.closed()).isPresent();
   }

   /**
    * Writes the goal's {@code id}, {@code title}, {@code status}, {@code closed}, {@code parent}, {@code path} and
    * {@code needsClosing}.
    *
    * @param target the JSON object to write into.
    * @return {@code target}, for chaining.
    */
   @Override
   public ObjectNode writeJson(ObjectNode target) {
      target.put("id", PREFIX + id);
      target.put("title", title);
      // Null rather than left out, so every goal has the same fields; a status naming no goal status is null too.
      target.put("status", status.map(GoalStatus::label).orElse(null));
      target.put("closed", closed.orElse(null));
      // A node ID, like the goal's own, so it can be fed straight back.
      target.put("parent", parent.map(value -> PREFIX + value).orElse(null));
      target.put("path", path.toString());
      target.put("needsClosing", needsClosing);
      return target;
   }
}
