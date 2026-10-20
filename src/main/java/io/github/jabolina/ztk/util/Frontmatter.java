package io.github.jabolina.ztk.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.SequencedMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;

/**
 * The YAML frontmatter of a note: the fields between two {@code ---} lines at the top of the file, before the
 * Markdown body.
 *
 * <p>
 * It is read leniently: fields that are not valid YAML are read as flat {@code key: value} lines, the only shape ztk
 * and the bash script write. It is written with values quoted only when YAML requires it.
 * </p>
 *
 * @since 1.0
 * @author José Bolina
 */
public final class Frontmatter {

   /**
    * The line opening and closing the frontmatter.
    */
   public static final String DELIMITER = "---";

   // Writes values quoted only when YAML requires it, numbers included (so "123" stays a string).
   // Its write features do not change how it reads.
   private static final YAMLMapper YAML = YAMLMapper.builder()
         .enable(YAMLWriteFeature.MINIMIZE_QUOTES, YAMLWriteFeature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS)
         // The "---" delimiters are written explicitly, since they also close the block.
         .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
         .build();

   // The frontmatter at the very start of a note, up to and including its closing line; the fields are group 1.
   private static final Pattern BLOCK = Pattern.compile("\\A---\\n(.*?)^---$\\n?", Pattern.DOTALL | Pattern.MULTILINE);

   private Frontmatter() { }

   public static JsonNode read(Path note) {
      try (BufferedReader reader = Files.newBufferedReader(note)) {
         if (!DELIMITER.equals(reader.readLine()))
            return MissingNode.getInstance();

         List<String> lines = new ArrayList<>();
         // Stops at the closing delimiter, or at the end of a file whose frontmatter is never closed.
         for (String line = reader.readLine(); line != null && !line.equals(DELIMITER); line = reader.readLine()) {
            lines.add(line);
         }
         return parse(lines);
      } catch (IOException e) {
         throw new UncheckedIOException("Failed reading note: " + note, e);
      }
   }

   /**
    * Parses the frontmatter lines.
    *
    * <p>
    * The lines are read as YAML. When they are not valid YAML, they are read as flat {@code key: value} lines
    * instead, the only shape ztk and the bash script write.
    * </p>
    *
    * @param fields the lines between the delimiters.
    * @return the parsed fields; a missing node when there are none, so lookups on it find nothing.
    */
   public static JsonNode parse(List<String> fields) {
      if (fields.isEmpty()) {
         return MissingNode.getInstance();
      }
      try {
         return YAML.readTree(String.join("\n", fields));
      } catch (JacksonException e) {
         // The bash script wrote values unquoted, so "title: ISPN-1234: Fix the merge" is not valid YAML: the second
         // ": " reads as a nested mapping. Losing those notes would be worse than reading them leniently.
         return flat(fields);
      }
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
   public static boolean contains(Path file, String line) {
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

   /**
    * Renders a note: the frontmatter between delimiters, a blank line, then the body.
    *
    * @param fields the frontmatter fields, in order; an empty value is written as {@code ""}.
    * @param body the Markdown after the frontmatter.
    * @return the note content.
    */
   public static String render(SequencedMap<String, String> fields, String body) {
      // ObjectNode keeps insertion order, so the keys appear exactly in this order.
      ObjectNode node = YAML.createObjectNode();
      fields.forEach(node::put);
      // The delimiters are written explicitly, since the mapper does not close the block.
      return DELIMITER + "\n" + YAML.writeValueAsString(node) + DELIMITER + "\n\n" + body;
   }

   /**
    * Changes a note's frontmatter, leaving its body exactly as it is.
    *
    * <p>
    * The note is read right before the change, since it may be open in an editor. Fields keep their order and new
    * ones go last. A note without frontmatter gets one.
    * </p>
    *
    * @param note the note to change.
    * @param change changes the fields, e.g. puts a new {@code status}.
    * @throws UncheckedIOException when the note cannot be read or written.
    */
   public static void update(Path note, Consumer<ObjectNode> change) {
      try {
         String content = Files.readString(note);
         Matcher block = BLOCK.matcher(content);
         boolean found = block.lookingAt();
         // Anything but a mapping, e.g. an empty frontmatter, starts afresh.
         ObjectNode fields = found && parse(block.group(1).lines().toList()) instanceof ObjectNode existing
               ? existing
               : YAML.createObjectNode();
         change.accept(fields);
         // Everything after the closing line is the body, kept as is; a note without frontmatter gets the usual blank line.
         String body = found ? content.substring(block.end()) : "\n" + content;
         Files.writeString(note, DELIMITER + "\n" + YAML.writeValueAsString(fields) + DELIMITER + "\n" + body);
      } catch (IOException e) {
         throw new UncheckedIOException("Failed updating note: " + note, e);
      }
   }

   /**
    * Formats a moment as frontmatter dates are written, e.g. a note's {@code date}.
    *
    * @param now the moment.
    * @return the ISO-8601 date with offset, e.g. {@code 2026-10-15T09:30:00+02:00}.
    */
   public static String timestamp(OffsetDateTime now) {
      // Seconds precision, always explicit: toString() drops ":00" seconds, and a real clock carries nanoseconds.
      return now.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
   }

   /**
    * Reads frontmatter lines as flat {@code key: value} pairs, without YAML rules.
    *
    * @param fields the lines between the delimiters.
    * @return each top-level key and the rest of its line, stripped; lines without a key are ignored.
    */
   private static JsonNode flat(List<String> fields) {
      ObjectNode node = YAML.createObjectNode();
      for (String field : fields) {
         int colon = field.indexOf(':');
         // Indented lines continue a YAML value; the bash script never wrote any, so they carry nothing to read here.
         if (colon > 0 && !Character.isWhitespace(field.charAt(0))) {
            node.put(field.substring(0, colon).strip(), field.substring(colon + 1).strip());
         }
      }
      return node;
   }
}
