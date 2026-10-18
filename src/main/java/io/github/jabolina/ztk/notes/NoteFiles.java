package io.github.jabolina.ztk.notes;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.SequencedMap;

import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;

/**
 * Writes and reads note files in the knowledge base.
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

   // Writes frontmatter quoting values only when YAML requires it, numbers included (so "123" stays a string).
   private static final YAMLMapper YAML = YAMLMapper.builder()
         .enable(YAMLWriteFeature.MINIMIZE_QUOTES, YAMLWriteFeature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS)
         // The "---" delimiters are written explicitly, since they also close the block.
         .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
         .build();

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
      frontmatter.put("date", date(now));
      frontmatter.putAll(fields);

      return write(directory, now.toLocalDate().format(DAY) + "-" + slug(title), render(frontmatter, body));
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
         return Files.writeString(file, render(frontmatter, body), StandardOpenOption.CREATE_NEW);
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

   /**
    * Renders a note: the frontmatter between {@code ---} delimiters, a blank line, then the body.
    *
    * @param frontmatter the frontmatter fields, in order.
    * @param body the Markdown after the frontmatter.
    * @return the note content.
    */
   private static String render(SequencedMap<String, String> frontmatter, String body) {
      // ObjectNode keeps insertion order, so the keys appear exactly in this order.
      ObjectNode node = YAML.createObjectNode();
      frontmatter.forEach(node::put);
      // The "---" delimiters are written explicitly, since the mapper does not close the block.
      return "---\n" + YAML.writeValueAsString(node) + "---\n\n" + body;
   }

   /**
    * Turns a task description into a file name fragment.
    *
    * <p>
    * Same rule as the original shell script: lower case, every run of characters other than ASCII letters and digits
    * becomes a single {@code -}, without leading or trailing dashes.
    * </p>
    *
    * @param description the task description.
    * @return the slug, e.g. {@code fix-flaky-test}.
    */
   static String slug(String description) {
      return description.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-|-$", "");
   }

   /**
    * Formats a note creation date.
    *
    * @param now the creation time.
    * @return the ISO-8601 date with offset, e.g. {@code 2026-10-15T09:30:00+02:00}.
    */
   private static String date(OffsetDateTime now) {
      // Seconds precision, always explicit: toString() drops ":00" seconds, and a real clock carries nanoseconds.
      return now.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
   }

   /**
    * Tells whether a note's frontmatter contains a line.
    *
    * <p>
    * Only the frontmatter is read, so the cost does not depend on the size of the note.
    * </p>
    *
    * @param file the note to read.
    * @param line the line to look for, e.g. {@code task_uuid: <uuid>}.
    * @return whether the frontmatter contains the line. A file without frontmatter, or that cannot be read as UTF-8,
    *         never does.
    */
   public static boolean frontmatterContains(Path file, String line) {
      try (BufferedReader reader = Files.newBufferedReader(file)) {
         // The frontmatter must open on the first line; any other file cannot be a task note.
         if (!"---".equals(reader.readLine())) {
            return false;
         }
         // Stops at the closing delimiter, or at the end of a file whose frontmatter is never closed.
         for (String current = reader.readLine(); current != null && !current.equals("---"); current = reader.readLine()) {
            // strip() tolerates trailing spaces and Windows line endings.
            if (current.strip().equals(line)) {
               return true;
            }
         }
         return false;
      } catch (IOException e) {
         // Unreadable or not UTF-8: it cannot be the note we look for, and must not stop the search.
         return false;
      }
   }
}
