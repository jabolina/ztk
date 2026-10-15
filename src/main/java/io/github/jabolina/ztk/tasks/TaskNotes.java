package io.github.jabolina.ztk.tasks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

import io.github.jabolina.ztk.configuration.Configuration;
import io.github.jabolina.ztk.taskwarrior.Task;
import io.github.jabolina.ztk.taskwarrior.TaskStore;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;

/**
 * Locates and creates the notes attached to tasks.
 *
 * <p>
 * A task note lives under its project folder in the knowledge base and is linked to the task through the
 * {@code note} attribute, holding the note path relative to the knowledge base.
 * </p>
 *
 * @since 1.0
 * @author José Bolina
 */
final class TaskNotes {

   // Writes frontmatter quoting values only when YAML requires it, numbers included (so "123" stays a string).
   private static final YAMLMapper YAML = YAMLMapper.builder()
         .enable(YAMLWriteFeature.MINIMIZE_QUOTES, YAMLWriteFeature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS)
         // The "---" delimiters are written explicitly, since they also close the block.
         .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
         .build();

   // File name prefix, e.g. 20261015.
   private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

   private final Configuration configuration;
   private final TaskStore store;
   private final Clock clock;

   /**
    * Creates the task note service.
    *
    * @param configuration the effective configuration, providing the knowledge base root.
    * @param store the task store, used to link tasks to their notes.
    * @param clock the clock for note names and creation dates.
    */
   public TaskNotes(Configuration configuration, TaskStore store, Clock clock) {
      this.configuration = configuration;
      this.store = store;
      this.clock = clock;
   }

   /**
    * Creates the task's note from the template and links it to the task.
    *
    * @param task the task the note belongs to. It must have a project.
    * @return the absolute path of the created note.
    */
   public Path create(Task task) {
      String project = task.project().orElseThrow();
      OffsetDateTime now = OffsetDateTime.now(clock);

      // Seconds precision, always explicit: toString() drops ":00" seconds, and a real clock carries nanoseconds.
      String date = now.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
      String reference = task.issue().map(value -> "Ref: **" + value + "**\n\n").orElse("");
      String name = now.toLocalDate().format(DAY) + "-" + slug(task.description());
      String content = frontmatter(task, project, date) + "\n" + reference + "# Notes\n\n";
      Path note = write(configuration.dataLocation().resolve(project), name, content);
      store.setNote(task, configuration.dataLocation().relativize(note));
      return note;
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
   private static String slug(String description) {
      return description.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-|-$", "");
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
    * Renders the note frontmatter, including its {@code ---} delimiters.
    *
    * @param task the task the note belongs to.
    * @param project the task project.
    * @param date the creation date, already formatted.
    * @return the frontmatter block, ending with a line break.
    */
   private static String frontmatter(Task task, String project, String date) {
      // ObjectNode keeps insertion order, so the keys appear exactly in this order.
      ObjectNode fields = YAML.createObjectNode();
      fields.put("title", task.description());
      fields.put("date", date);
      fields.put("task_uuid", task.uuid());
      fields.put("project", project);
      fields.put("issue", task.issue().orElse(""));
      return "---\n" + YAML.writeValueAsString(fields) + "---\n";
   }
}
