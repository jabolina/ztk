package io.github.jabolina.ztk.planning;

import io.github.jabolina.ztk.util.Labeled;

/**
 * Where a goal stands, set by the user.
 *
 * @since 1.0
 * @author José Bolina
 */
public enum GoalStatus implements Labeled {
   /**
    * Still being worked toward.
    */
   OPEN(false),

   /**
    * Met.
    */
   REACHED(true),

   /**
    * Tried, and not met.
    */
   MISSED(true),

   /**
    * No longer matters.
    */
   DROPPED(true);

   private final boolean closed;

   GoalStatus(boolean closed) {
      this.closed = closed;
   }

   /**
    * @return whether a goal with this status is closed, so no new work goes under it.
    */
   public boolean closed() {
      return closed;
   }
}
