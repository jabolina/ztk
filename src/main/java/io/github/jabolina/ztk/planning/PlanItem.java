package io.github.jabolina.ztk.planning;

import java.nio.file.Path;

/**
 * A goal or an effort: what the plan is made of, each with its own note and status.
 *
 * @since 1.0
 * @author José Bolina
 */
public sealed interface PlanItem permits Goal, Effort {

   /**
    * @return its node ID, e.g. {@code goal:2026-Q4-G1} or {@code effort:infinijepsen-demo}.
    */
   String nodeId();

   /**
    * @return the absolute path of its note.
    */
   Path path();

   /**
    * @return whether its status is an open one; a missing or invalid status is neither open nor closed.
    */
   boolean open();
}
