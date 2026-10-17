package io.github.jabolina.ztk.notes;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.SequencedMap;
import java.util.UUID;

import io.github.jabolina.ztk.configuration.Configuration;

/**
 * Creates Zettelkasten notes, the knowledge notes that belong to no task.
 *
 * @since 1.0
 * @author José Bolina
 */
public final class Zettels {

   private final Configuration configuration;
   private final Clock clock;

   /**
    * Creates the zettel service.
    *
    * @param configuration the effective configuration, providing the zettel folder.
    * @param clock the clock for zettel names and creation dates.
    */
   public Zettels(Configuration configuration, Clock clock) {
      this.configuration = configuration;
      this.clock = clock;
   }

   /**
    * Creates a zettel in the zettel folder.
    *
    * @param title the zettel title, also the source of its file name.
    * @return the new zettel.
    * @throws UncheckedIOException when the zettel cannot be written.
    */
   public Zettel create(String title) {
      // Identifies the zettel independently of its file name.
      String uuid = UUID.randomUUID().toString();
      SequencedMap<String, String> fields = new LinkedHashMap<>();
      fields.put("uuid", uuid);
      Path path = NoteFiles.create(configuration.folders().zettel(), title, OffsetDateTime.now(clock), fields, "# Notes\n\n");
      return new Zettel(path, uuid);
   }
}
