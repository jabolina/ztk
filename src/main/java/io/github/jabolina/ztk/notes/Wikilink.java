package io.github.jabolina.ztk.notes;

/**
 * A wikilink in a note's body.
 *
 * @param target The name it links to: the text before any alias or heading, e.g. {@code 20261016-lamport-clocks}.
 * @param line   The line it is written on, counted from the top of the file.
 * @since 1.0
 * @author José Bolina
 */
public record Wikilink(String target, int line) { }
