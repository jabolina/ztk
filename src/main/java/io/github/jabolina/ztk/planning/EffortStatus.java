package io.github.jabolina.ztk.planning;

/**
 * Where an effort stands, set by the user, never derived from its tasks.
 *
 * @since 1.0
 * @author José Bolina
 */
public enum EffortStatus implements PlanningStatus {
   /**
    * Agreed on, not started.
    */
   PLANNED(false),

   /**
    * Being worked on.
    */
   ACTIVE(false),

   /**
    * Delivered.
    */
   DONE(true),

   /**
    * No longer matters.
    */
   DROPPED(true);

   private final boolean closed;

   EffortStatus(boolean closed) {
      this.closed = closed;
   }

   /**
    * @return whether an effort with this status is closed, so no pending task joins it.
    */
   @Override
   public boolean closed() {
      return closed;
   }
}
