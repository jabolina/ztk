package io.github.jabolina.ztk.notes;

/**
 * One line of a note's body.
 *
 * @param number The line number in the file, starting at 1 and counting the frontmatter, so it can be opened directly.
 * @param text   The line, without its line terminator.
 * @since 1.0
 * @author José Bolina
 */
public record Line(int number, String text) { }
