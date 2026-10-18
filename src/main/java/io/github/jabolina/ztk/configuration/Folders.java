package io.github.jabolina.ztk.configuration;

import java.nio.file.Path;
import java.util.List;

/**
 * The folders of the knowledge base, one per kind of note.
 *
 * <p>
 * Every folder is an absolute path inside {@code data.location}. A folder does not need to exist: the first note
 * written into it creates it.
 * </p>
 *
 * @param zettel    The folder for zettels.
 * @param papers    The folder for paper notes and their PDFs.
 * @param goals     The folder for goal notes.
 * @param efforts   The folder for effort notes.
 * @param journal   The folder for sessions.
 * @param summaries The folder for summaries.
 * @since 1.0
 * @author José Bolina
 */
public record Folders(Path zettel, Path papers, Path goals, Path efforts, Path journal, Path summaries) {

   /**
    * @return every folder, in the order of their configuration keys.
    */
   public List<Path> all() {
      return List.of(zettel, papers, goals, efforts, journal, summaries);
   }
}
