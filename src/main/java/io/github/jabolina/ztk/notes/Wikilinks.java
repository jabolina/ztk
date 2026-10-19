package io.github.jabolina.ztk.notes;

import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds the wikilinks a note's body contains.
 *
 * <p>
 * A wikilink is written {@code [[target]]}, where the target is the file name of another note, without {@code .md}.
 * </p>
 *
 * @since 1.0
 * @author José Bolina
 */
public class Wikilinks {

   // Group 1 is the target: the text before any alias ("|clocks") or heading ("#Merging"). Brackets are not allowed
   // inside, so "[[a]] and [[b]]" are two links, not one.
   private static final Pattern LINK = Pattern.compile("\\[\\[([^\\[\\]|#]*)[^\\[\\]]*]]");

   // A run of backticks closes only with a run of the same length, so ``a ` b`` is one span holding a backtick.
   private static final Pattern INLINE_CODE = Pattern.compile("(`+).+?\\1");

   /**
    * Lists the targets of the wikilinks in a note's body.
    *
    * <p>
    * A target is the text before any alias or heading, so {@code [[20261017-vector-clocks#Merging|merge]]} names
    * {@code 20261017-vector-clocks}. A link to a heading of the note itself, such as {@code [[#Merging]]}, names no
    * target.
    * </p>
    *
    * <p>
    * Links inside code, fenced or inline, are examples rather than links, and are not listed.
    * </p>
    *
    * @param lines the body lines, consumed here, in order.
    * @return the targets in the order they appear, trimmed; repeated when a target is linked more than once.
    */
   public static Stream<String> targets(Stream<Line> lines) {
      return lines.map(Line::text)
            .filter(new OutsideFences())
            .map(text -> INLINE_CODE.matcher(text).replaceAll(""))
            .flatMap(text -> LINK.matcher(text).results())
            .map(match -> match.group(1).strip())
            .filter(target -> !target.isEmpty());
   }

   /**
    * Tells the lines outside fenced code blocks, as it sees them in order.
    *
    * <p>
    * A block opens with a line starting with three backticks or tildes, and closes with the next one using the same
    * character. A block never closed runs until the end of the note.
    * </p>
    */
   private static final class OutsideFences implements Predicate<String> {

      // The character of the open block's fence, or 0 outside a block.
      private char fence;

      /**
       * @param line the next line of the body.
       * @return whether the line is outside a block; fence lines are never.
       */
      @Override
      public boolean test(String line) {
         // Indented fences open blocks too, e.g., in list items.
         String stripped = line.stripLeading();
         if (stripped.startsWith("```") || stripped.startsWith("~~~")) {
            char marker = stripped.charAt(0);
            if (fence == 0) {
               fence = marker;
            } else if (fence == marker) {
               fence = 0;
            }
            return false;
         }
         return fence == 0;
      }
   }
}
