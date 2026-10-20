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
    * A planning item's own note, as the knowledge base shows it.
    *
    * @param type The note type, e.g. {@link NoteType#GOAL}.
    * @param id   The item's node ID, e.g. {@code goal:2026-Q4-G1} or {@code effort:infinijepsen-demo}.
    */
   record Planned(NoteType type, String id) { }

   /**
    * Tells which planning item a note is.
    *
    * @param note the note's absolute path.
    * @return the item; empty when the note is not a planning item, including material kept next to one.
    */
   Optional<Planned> planned(Path note);
}
