package io.github.jabolina.ztk.notes;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Finding the notes a body links to, as Obsidian and Telekasten write wikilinks.
 */
class WikilinksTest {

   @Test
   void aTargetEndsWhereAnAliasOrAHeadingStarts() {
      List<String> targets = targets(
            "See [[20261016-lamport-clocks|clocks]] and [[20261017-vector-clocks#Merging]].",
            "Also [[ 20261018-hybrid-clocks | HLC ]] and [[20261019-crdts#Sets|CRDT sets]].",
            // A heading of the note itself names no other note.
            "As said [[#Below]].");

      assertThat(targets).containsExactly(
            "20261016-lamport-clocks", "20261017-vector-clocks", "20261018-hybrid-clocks", "20261019-crdts");
   }

   @Test
   void linksInsideCodeAreNotLinks() {
      List<String> targets = targets(
            // Inline code, also with longer backtick runs holding a backtick.
            "Write links as `[[note]]` or ``[[note|alias]] with a ` inside``, e.g. [[20261016-lamport-clocks]].",
            "```markdown",
            "[[20261017-inside-backticks]]",
            // Inside a backtick block, a tilde fence is content, not its end.
            "~~~",
            "[[20261018-still-inside]]",
            "```",
            "After the block: [[20261019-after-block]].",
            // Indented, as in a list item, and never closed: code until the end of the note.
            "  ~~~",
            "[[20261020-inside-tildes]]");

      assertThat(targets).containsExactly("20261016-lamport-clocks", "20261019-after-block");
   }

   /**
    * Finds the targets in a body.
    *
    * @param body the body lines.
    * @return the targets, in order.
    */
   private static List<String> targets(String... body) {
      AtomicInteger number = new AtomicInteger();
      return Wikilinks.links(Stream.of(body).map(text -> new Line(number.incrementAndGet(), text)))
            .map(Wikilink::target)
            .toList();
   }
}
