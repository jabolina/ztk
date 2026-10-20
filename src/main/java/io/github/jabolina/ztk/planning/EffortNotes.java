package io.github.jabolina.ztk.planning;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import java.util.stream.Stream;

import io.github.jabolina.ztk.configuration.Configuration;
import io.github.jabolina.ztk.util.Frontmatter;
import io.github.jabolina.ztk.util.Labeled;
import tools.jackson.databind.JsonNode;

/**
 * Reads efforts from their notes.
 *
 * <p>
 * Only reads: what an effort may become, and the checks around it, belong to the commands changing it.
 * </p>
 *
 * @since 1.0
 * @author José Bolina
 */
final class EffortNotes {

   private final Configuration configuration;
   private final Clock clock;

   /**
    * @param configuration the configuration giving the efforts folder.
    * @param clock the clock telling which efforts are carried over.
    */
   EffortNotes(Configuration configuration, Clock clock) {
      this.configuration = configuration;
      this.clock = clock;
   }

   /**
    * Finds an effort by its slug.
    *
    * @param slug the effort's slug, bare or as a node ID, e.g. {@code infinijepsen-demo} or
    *             {@code effort:infinijepsen-demo}.
    * @return the effort; empty when no effort has the slug.
    * @throws UncheckedIOException when the effort's note cannot be read.
    */
   Optional<Effort> find(String slug) {
      // Accepted as JSON results print it, so an agent can feed it straight back.
      String bare = slug.startsWith(Effort.PREFIX) ? slug.substring(Effort.PREFIX.length()) : slug;
      Path efforts = configuration.folders().efforts();
      Path file = efforts.resolve(bare + ".md");
      // Only a file directly in the efforts folder: a slug like ../x never reaches outside it.
      return Levels.effort(efforts.relativize(file))
            .filter(found -> Files.isRegularFile(file))
            .map(found -> read(found, file));
   }

   /**
    * Reads every effort: the notes directly in the efforts folder.
    *
    * @return the efforts, read as the stream is consumed; empty when the folder does not exist. It must be closed, e.g.
    *         with try-with-resources, to release the folder.
    * @throws UncheckedIOException when the folder cannot be listed or an effort cannot be read.
    */
   Stream<Effort> all() {
      Path efforts = configuration.folders().efforts();
      if (!Files.isDirectory(efforts)) {
         return Stream.empty();
      }
      Stream<Path> files;
      try {
         files = Files.list(efforts);
      } catch (IOException e) {
         throw new UncheckedIOException("Failed reading folder: " + efforts, e);
      }
      // Only files directly in the folder: anything in a subfolder is material.
      // The listing's close handler carries over to the returned pipeline.
      return files.filter(Files::isRegularFile)
            .flatMap(file -> Levels.effort(efforts.relativize(file)).map(slug -> read(slug, file)).stream());
   }

   /**
    * Reads one effort from its note.
    *
    * @param slug the effort's slug, from the file name.
    * @param file the effort's note.
    * @return the effort.
    * @throws UncheckedIOException when the note cannot be read.
    */
   private Effort read(String slug, Path file) {
      JsonNode frontmatter = Frontmatter.read(file);
      Optional<EffortStatus> status = frontmatter.path("status").asStringOpt()
            .flatMap(label -> Labeled.parse(EffortStatus.class, label));
      Optional<String> goal = frontmatter.path("goal").asStringOpt();
      // From the goal's ID alone: the goal's note is not read, and may not even exist.
      Optional<Period.OfQuarter> quarter = goal.flatMap(GoalNotes::period)
            .filter(Period.OfQuarter.class::isInstance)
            .map(Period.OfQuarter.class::cast);
      // Neither done nor dropped, and its quarter is over: it moves on by pointing it at a later goal, or by closing it.
      boolean carriedOver = !status.map(EffortStatus::closed).orElse(false)
            && quarter.filter(value -> LocalDate.now(clock).isAfter(value.end())).isPresent();
      return new Effort(slug,
            frontmatter.path("title").asStringOpt().orElse(slug),
            status,
            frontmatter.path("closed").asStringOpt(),
            goal,
            quarter,
            carriedOver,
            file);
   }
}
