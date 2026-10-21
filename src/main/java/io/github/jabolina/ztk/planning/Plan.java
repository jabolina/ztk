package io.github.jabolina.ztk.planning;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.stream.Stream;

import io.github.jabolina.ztk.configuration.Configuration;

/**
 * Every goal and effort of the knowledge base, read from their notes as they are consumed.
 *
 * <p>
 * For readers joining the plan with the rest of the knowledge base, such as the graph. Commands changing the plan go
 * through {@link Goals} and {@link Efforts}.
 * </p>
 *
 * @since 1.0
 * @author José Bolina
 */
public final class Plan {

   private final GoalNotes goals;
   private final EffortNotes efforts;

   /**
    * @param configuration the configuration giving the goals and efforts folders.
    * @param clock the clock telling which goals need closing and which efforts are carried over.
    */
   public Plan(Configuration configuration, Clock clock) {
      this.goals = new GoalNotes(configuration, clock);
      this.efforts = new EffortNotes(configuration, clock);
   }

   /**
    * Reads every yearly and quarterly goal, of every period.
    *
    * @return the goals, one per file, in no particular order: two files with one goal ID are both there. It must be
    *         closed, e.g. with try-with-resources, to release the folders.
    * @throws UncheckedIOException when a folder or a goal cannot be read.
    */
   public Stream<Goal> goals() {
      return goals.all();
   }

   /**
    * Reads every effort.
    *
    * @return the efforts, in no particular order. It must be closed, e.g. with try-with-resources, to release the
    *         folder.
    * @throws UncheckedIOException when the folder or an effort cannot be read.
    */
   public Stream<Effort> efforts() {
      return efforts.all();
   }

   /**
    * Lists the notes sitting where goals do, directly in a year's or a quarter's folder, but not named after a goal ID
    * of that folder; they are read as plain notes.
    *
    * @return their absolute paths, in no particular order. It must be closed, e.g. with try-with-resources, to release
    *         the folders.
    * @throws UncheckedIOException when a folder cannot be listed.
    */
   public Stream<Path> misnamed() {
      return goals.misnamed();
   }
}
