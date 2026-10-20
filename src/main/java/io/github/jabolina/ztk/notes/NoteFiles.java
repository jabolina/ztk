package io.github.jabolina.ztk.notes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.SequencedMap;

import io.github.jabolina.ztk.util.Frontmatter;
import io.github.jabolina.ztk.util.Slugs;

/**
 * Writes new note files in the knowledge base.
 *
 * <p>
 * Every note created by ztk has the same layout: a file named after its creation day and title, a YAML frontmatter
 * starting with {@code title} and {@code date}, and a Markdown body.
 * </p>
 *
 * @since 1.0
 * @author José Bolina
 */
public final class NoteFiles {

   // File name prefix, e.g. 20261015.
   private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

   private NoteFiles() { }

   /**
    * Creates a note under a name not taken yet, creating the folder if needed.
    *
    * @param directory the folder of the note.
    * @param title the note title, written in the frontmatter and the source of the file name.
    * @param now the creation time, for the file name and the {@code date} field.
    * @param fields the frontmatter fields after {@code title} and {@code date}, in order.
    * @param body the Markdown after the frontmatter.
    * @return the absolute path of the new note, e.g. {@code <directory>/20261015-fix-flaky-test.md}.
    * @throws UncheckedIOException when the note cannot be written.
    */
   public static Path create(Path directory, String title, OffsetDateTime now, SequencedMap<String, String> fields, String body) {
      // Sequenced to keep insertion order.
      SequencedMap<String, String> frontmatter = new LinkedHashMap<>();
      frontmatter.put("title", title);
      frontmatter.put("date", Frontmatter.timestamp(now));
      frontmatter.putAll(fields);

      return write(directory, now.toLocalDate().format(DAY) + "-" + Slugs.of(title), Frontmatter.render(frontmatter, body));
   }

   /**
    * Writes a new note at an exact path, creating its folder if needed.
    *
    * @param file the note's path, e.g. {@code <papers>/ATC/2014/in-search-of-an-understandable-consensus-algorithm.md}.
    * @param frontmatter the frontmatter fields, in order; an empty value is written as {@code ""}.
    * @param body the Markdown after the frontmatter.
    * @return {@code file}.
    * @throws UncheckedIOException when the note cannot be written, including when {@code file} already exists.
    */
   public static Path write(Path file, SequencedMap<String, String> frontmatter, String body) {
      try {
         Files.createDirectories(file.getParent());
         // CREATE_NEW: an existing note is never overwritten.
         return Files.writeString(file, Frontmatter.render(frontmatter, body), StandardOpenOption.CREATE_NEW);
      } catch (IOException e) {
         throw new UncheckedIOException(e);
      }
   }

   /**
    * Writes a new note under a name not taken yet, creating the project folder if needed.
    *
    * <p>
    * The first candidate is {@code <name>.md}; when it exists, {@code <name>-2.md}, {@code <name>-3.md} and so on are
    * tried. Existing notes are never overwritten.
    * </p>
    *
    * @param directory the project folder.
    * @param name the file name without the {@code .md} extension, e.g. {@code 20261015-fix-flaky-test}.
    * @param content the note content.
    * @return the path of the written note.
    * @throws UncheckedIOException when the note cannot be written.
    */
   private static Path write(Path directory, String name, String content) {
      try {
         Files.createDirectories(directory);
         for (int attempt = 1; ; attempt++) {
            Path note = directory.resolve(attempt == 1 ? name + ".md" : name + "-" + attempt + ".md");
            try {
               // CREATE_NEW checks and creates in one step, so a concurrent writer cannot claim the same name.
               Files.writeString(note, content, StandardOpenOption.CREATE_NEW);
               return note;
            } catch (FileAlreadyExistsException e) {
               // Taken by another note: try the next suffix.
            }
         }
      } catch (IOException e) {
         throw new UncheckedIOException(e);
      }
   }
}
