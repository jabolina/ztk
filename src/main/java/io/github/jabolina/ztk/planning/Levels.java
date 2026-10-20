package io.github.jabolina.ztk.planning;

import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The levels of the goals folder: the folders where a note is a goal.
 *
 * <p>
 * Yearly goals sit directly in their year's folder, {@code goals/2026/}, and quarterly goals directly in their
 * quarter's, {@code goals/2026/Q4/}, each named after its ID. Anything else under the goals folder is material: files
 * related to a goal, read as plain notes.
 * </p>
 *
 * @since 1.0
 * @author José Bolina
 */
final class Levels {

   // A yearly goal directly in its year's folder, its ID agreeing with it, e.g. 2026/2026-G1-provably-correct.md.
   private static final Pattern YEARLY = Pattern.compile("(\\d{4})/(\\1-G\\d+)-[^/]+\\.md");

   // A quarterly goal directly in its quarter's folder, its ID agreeing with both, e.g. 2026/Q4/2026-Q4-G1-fix-bugs.md.
   private static final Pattern QUARTERLY = Pattern.compile("(\\d{4})/Q([1-4])/(\\1-Q\\2-G\\d+)-[^/]+\\.md");

   private Levels() { }

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
}
