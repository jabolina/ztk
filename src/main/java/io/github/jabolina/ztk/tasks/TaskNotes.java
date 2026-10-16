package io.github.jabolina.ztk.tasks;

import java.io.BufferedReader;
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
import java.util.Optional;
import java.util.stream.Stream;

import io.github.jabolina.ztk.ZtkException;
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
    * @param task the task the note belongs to.
    * @return the absolute path of the task's note.
    * @throws ZtkException with {@link ZtkException.Kind#VALIDATION} when the note must be created and the task has no
    *                      project. Nothing is written in that case.
    */
   public Path create(Task task) {
      if (task.note().isPresent()) {
         // Already linked: the note attribute is relative to the knowledge base.
         return configuration.dataLocation().resolve(task.note().get());
      }

      Optional<Path> existent = findByTaskUuid(task.uuid());
      if (existent.isPresent()) {
         // Link it, so the next lookup does not search the knowledge base again.
         store.setNote(task, configuration.dataLocation().relativize(existent.get()));
         return existent.get();
      }

      String project = task.project().orElseThrow(() -> ZtkException.validation(
            "Task " + task.id() + " ('" + task.description() + "') has no project, which is the folder of its note."
                  + System.lineSeparator()
                  + "Assign one with 'task " + task.id() + " modify project:<name>', then try again."));
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
    * Searches the whole knowledge base for a note whose frontmatter names the task.
    *
    * <p>
    * Notes may sit outside the project folder, e.g. in a folder whose name differs from the project only by case, so
    * every Markdown file is considered.
    * </p>
    *
    * @param uuid the task UUID.
    * @return the first note containing {@code task_uuid: <uuid>}, if any.
    * @throws UncheckedIOException when the knowledge base cannot be traversed.
    */
   private Optional<Path> findByTaskUuid(String uuid) {
      Path root = configuration.dataLocation();
      // A new knowledge base may not exist until the first note is written.
      if (!Files.isDirectory(root)) {
         return Optional.empty();
      }

      String marker = "task_uuid: " + uuid;
      try (Stream<Path> files = Files.walk(root)) {
         return files
               .filter(file -> file.getFileName().toString().endsWith(".md"))
               .filter(Files::isRegularFile)
               .filter(file -> frontmatterContains(file, marker))
               .findFirst();
      } catch (IOException e) {
         throw new UncheckedIOException(e);
      }
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
   private static boolean frontmatterContains(Path file, String line) {
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
