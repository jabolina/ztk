package io.github.jabolina.ztk.planning;

import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.jabolina.ztk.configuration.Configuration;
import io.github.jabolina.ztk.notes.NoteType;
import io.github.jabolina.ztk.notes.PlanningNotes;

/**
 * The levels of planning: the folders where a note is a goal or an effort.
 *
 * <p>
 * Yearly goals sit directly in their year's folder, {@code goals/2026/}, and quarterly goals directly in their
 * quarter's, {@code goals/2026/Q4/}, each named after its ID. Efforts sit directly in the efforts folder, each named
 * after its slug. Anything else under those folders is material: files related to a goal or effort, read as plain
 * notes.
 * </p>
 *
 * @since 1.0
 * @author José Bolina
 */
public final class Levels implements PlanningNotes {

   // A yearly goal directly in its year's folder, its ID agreeing with it, e.g. 2026/2026-G1-provably-correct.md.
   private static final Pattern YEARLY = Pattern.compile("(\\d{4})/(\\1-G\\d+)-[^/]+\\.md");

   // A quarterly goal directly in its quarter's folder, its ID agreeing with both, e.g. 2026/Q4/2026-Q4-G1-fix-bugs.md.
   private static final Pattern QUARTERLY = Pattern.compile("(\\d{4})/Q([1-4])/(\\1-Q\\2-G\\d+)-[^/]+\\.md");

   // An effort directly in the efforts folder; the file name is its slug, e.g. infinijepsen-demo.md.
   private static final Pattern EFFORT = Pattern.compile("([^/]+)\\.md");

   // A note directly in a year's or a quarter's folder, e.g. 2026/notes.md or 2026/Q4/notes.md: a goal, or meant to be.
   private static final Pattern LEVEL = Pattern.compile("\\d{4}(?:/Q[1-4])?/[^/]+\\.md");

   private final Configuration configuration;

   /**
    * @param configuration the configuration giving the goals and efforts folders.
    */
   public Levels(Configuration configuration) {
      this.configuration = configuration;
   }

   /**
    * Tells which goal or effort a note is.
    *
    * @param note the note's absolute path.
    * @return the goal or effort; empty for any other note, including material kept next to one.
    */
   @Override
   public Optional<Planned> planned(Path note) {
      Path goals = configuration.folders().goals();
      if (note.startsWith(goals)) {
         return goal(goals.relativize(note)).map(id -> new Planned(NoteType.GOAL, Goal.PREFIX + id));
      }
      Path efforts = configuration.folders().efforts();
      if (note.startsWith(efforts)) {
         return effort(efforts.relativize(note)).map(slug -> new Planned(NoteType.EFFORT, Effort.PREFIX + slug));
      }
      return Optional.empty();
   }

   /**
    * Tells which goal a file of the goals folder is.
    *
    * @param relative the file's path relative to the goals folder, e.g. {@code 2026/Q4/2026-Q4-G1-fix-bugs.md}.
    * @return the goal ID, e.g. {@code 2026-Q4-G1}; empty when the file is not a goal: material, or a note not named
    *         after a goal ID of its level.
    */
   static Optional<String> goal(Path relative) {
      // Matched as text: ztk runs where paths are separated by '/'.
      String path = relative.toString();
      Matcher yearly = YEARLY.matcher(path);
      if (yearly.matches()) {
         return Optional.of(yearly.group(2));
      }
      Matcher quarterly = QUARTERLY.matcher(path);
      return quarterly.matches() ? Optional.of(quarterly.group(3)) : Optional.empty();
   }

   /**
    * Tells whether a file of the goals folder sits where goals do.
    *
    * @param relative the file's path relative to the goals folder, e.g. {@code 2026/notes.md}.
    * @return whether it is a note directly in a year's or a quarter's folder, named after a goal ID or not; material
    *         sits in subfolders, and is not.
    */
   static boolean level(Path relative) {
      return LEVEL.matcher(relative.toString()).matches();
   }

   /**
    * Tells which effort a file of the efforts folder is.
    *
    * @param relative the file's path relative to the efforts folder, e.g. {@code infinijepsen-demo.md}.
    * @return the effort's slug, e.g. {@code infinijepsen-demo}; empty when the file is material, in a folder of its own.
    */
   static Optional<String> effort(Path relative) {
      Matcher effort = EFFORT.matcher(relative.toString());
      return effort.matches() ? Optional.of(effort.group(1)) : Optional.empty();
   }
}
