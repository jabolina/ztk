package io.github.jabolina.ztk.planning;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import io.github.jabolina.ztk.configuration.Configuration;
import io.github.jabolina.ztk.util.Frontmatter;
import io.github.jabolina.ztk.util.Labeled;
import tools.jackson.databind.JsonNode;

/**
 * Reads goals from their notes.
 *
 * <p>
 * Only reads: what a goal may become, and the checks around it, belong to the commands changing it.
 * </p>
 *
 * @since 1.0
 * @author José Bolina
 */
final class GoalNotes {

   // A goal ID: the period it is for, then its number, e.g. 2026-Q4-G1.
   private static final Pattern ID = Pattern.compile("(\\d{4}(?:-Q[1-4])?)-G\\d+");

   private final Configuration configuration;
   private final Clock clock;

   /**
    * @param configuration the configuration giving the goals folder.
    * @param clock the clock telling which goals need closing.
    */
   GoalNotes(Configuration configuration, Clock clock) {
      this.configuration = configuration;
      this.clock = clock;
   }

   /**
    * Finds a goal by its ID.
    *
    * @param id the goal ID, bare or as a node ID, e.g. {@code 2026-G1} or {@code goal:2026-G1}.
    * @return the goal; empty when no goal has the ID, or the text is not a goal ID.
    * @throws UncheckedIOException when the goals cannot be read.
    */
   Optional<Goal> find(String id) {
      // Accepted as JSON results print it, so an agent can feed it straight back.
      String bare = id.startsWith(Goal.PREFIX) ? id.substring(Goal.PREFIX.length()) : id;
      Optional<Period> period = period(bare);
      if (period.isEmpty()) {
         return Optional.empty();
      }
      try (Stream<Goal> goals = of(period.get())) {
         return goals.filter(goal -> goal.id().equals(bare)).findFirst();
      }
   }

   /**
    * Reads the goals of a period: the notes directly in its folder whose names are goal IDs of that period.
    *
    * @param period the year or quarter.
    * @return the goals, read as the stream is consumed; empty when the folder does not exist. It must be closed, e.g.
    *         with try-with-resources, to release the folder.
    * @throws UncheckedIOException when the folder cannot be listed or a goal cannot be read.
    */
   Stream<Goal> of(Period period) {
      Path goals = configuration.folders().goals();
      Path folder = period.folder(goals);
      if (!Files.isDirectory(folder)) {
         return Stream.empty();
      }
      Stream<Path> files;
      try {
         files = Files.list(folder);
      } catch (IOException e) {
         throw new UncheckedIOException("Failed reading folder: " + folder, e);
      }
      // Only files directly in the folder: anything in a subfolder is material, or a goal of another level.
      // The listing's close handler carries over to the returned pipeline.
      return files.filter(Files::isRegularFile)
            .flatMap(file -> Levels.goal(goals.relativize(file)).map(id -> read(id, file)).stream());
   }

   /**
    * Reads the period a goal ID is for.
    *
    * @param id the bare goal ID, e.g. {@code 2026-Q4-G1}.
    * @return the year or quarter; empty when the text is not a goal ID.
    */
   static Optional<Period> period(String id) {
      Matcher matcher = ID.matcher(id);
      return matcher.matches() ? Optional.of(Period.parse(matcher.group(1))) : Optional.empty();
   }

   /**
    * Reads one goal from its note.
    *
    * @param id the goal ID, from the file name.
    * @param file the goal's note.
    * @return the goal.
    * @throws UncheckedIOException when the note cannot be read.
    */
   private Goal read(String id, Path file) {
      JsonNode frontmatter = Frontmatter.read(file);
      // Only called with the IDs of goal files, which always name a period.
      Period period = period(id).orElseThrow();
      Optional<GoalStatus> status = frontmatter.path("status").asStringOpt()
            .flatMap(label -> Labeled.parse(GoalStatus.class, label));
      // Still open after its period ended: whether it was reached, missed or dropped is for the user to say.
      boolean needsClosing = status.equals(Optional.of(GoalStatus.OPEN)) && LocalDate.now(clock).isAfter(period.end());
      return new Goal(id, period,
            frontmatter.path("title").asStringOpt().orElse(file.getFileName().toString()),
            status,
            frontmatter.path("closed").asStringOpt(),
            frontmatter.path("parent").asStringOpt(),
            file,
            needsClosing);
   }
}
