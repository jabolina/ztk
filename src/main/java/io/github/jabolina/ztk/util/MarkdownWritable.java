package io.github.jabolina.ztk.util;

/**
 * A value that describes itself in Markdown, for people and for the files ztk writes.
 *
 * <p>
 * The value appends to text owned by the caller, so the same description fits a whole file or one part of it.
 * </p>
 *
 * @since 1.0
 * @author José Bolina
 */
public interface MarkdownWritable {

   /**
    * Writes this value as Markdown.
    *
    * @param target the text to append to.
    * @return the same {@code target}, for appending further text.
    */
   StringBuilder writeMarkdown(StringBuilder target);
}
