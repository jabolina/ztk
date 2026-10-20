package io.github.jabolina.ztk.notes;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Recognizes the notes that are planning items rather than notes about something, e.g. a goal's own note.
 *
 * <p>
 * Where planning keeps its notes, and how they are named, is up to planning: the knowledge base only asks.
 * </p>
 *
 * @since 1.0
 * @author José Bolina
 */
public interface PlanningNotes {

   /**
    * Tells which goal a note is.
    *
    * @param note the note's absolute path.
    * @return the goal ID, e.g. {@code 2026-Q4-G1}; empty when the note is not a goal.
    */
   Optional<String> goal(Path note);
}
