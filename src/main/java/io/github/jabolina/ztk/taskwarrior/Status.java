package io.github.jabolina.ztk.taskwarrior;

import io.github.jabolina.ztk.ZtkException;
import io.github.jabolina.ztk.util.Labeled;

/**
 * The Taskwarrior status of a task.
 *
 * <p>
 * Only Taskwarrior's own statuses. Whether a task is in the week, in review or active is not a status: those are tags
 * and the start time, and plain Taskwarrior can combine them freely.
 * </p>
 *
 * @since 1.0
 * @author José Bolina
 */
public enum Status implements Labeled {
   PENDING,
   WAITING,
   COMPLETED,
   DELETED,
   // The template of a recurring task; its instances are pending.
   RECURRING;

   /**
    * Reads a status as exported by Taskwarrior.
    *
    * @param exported the exported value, e.g. {@code pending}.
    * @return the matching status.
    * @throws ZtkException with {@link ZtkException.Kind#TASKWARRIOR} when the value is not a known status.
    */
   static Status parse(String exported) {
      try {
         return Labeled.parse(Status.class, exported)
               .orElseThrow(() -> ZtkException.taskwarrior("Taskwarrior exported an unknown task status: '" + exported + "'."));
      } catch (IllegalArgumentException e) {
         throw ZtkException.taskwarrior("Taskwarrior exported an unknown task status: '" + exported + "'.");
      }
   }
}
