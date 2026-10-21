package io.github.jabolina.ztk.planning;

import java.time.LocalDate;
import java.util.Optional;

import io.github.jabolina.ztk.util.Labeled;
import tools.jackson.databind.node.ObjectNode;

/**
 * The status of a goal or an effort: open, or closed on the day it moved to a closing status.
 *
 * @since 1.0
 * @author José Bolina
 */
interface PlanningStatus extends Labeled {

   /**
    * @return whether an item with this status is closed, so no new open work goes under it.
    */
   boolean closed();

   /**
    * Writes this status into a planning note's frontmatter, with the day the item closed.
    *
    * <p>
    * Moving to a closed status dates it in {@code closed}; setting the current closed status again keeps the date it
    * first closed, or dates it when it has none, and moving to an open status removes it.
    * </p>
    *
    * @param fields the frontmatter fields, changed in place.
    * @param current the status before the change; empty when it was missing or not a status of this kind.
    * @param today the day of the change.
    */
   default void write(ObjectNode fields, Optional<? extends PlanningStatus> current, LocalDate today) {
      fields.put("status", label());
      if (!closed()) {
         fields.remove("closed");
      } else if (current.filter(this::equals).isEmpty() || !fields.has("closed")) {
         fields.put("closed", today.toString());
      }
   }
}
