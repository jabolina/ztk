package io.github.jabolina.ztk.tasks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.stream.Stream;

import io.github.jabolina.ztk.ZtkException;
import io.github.jabolina.ztk.configuration.Configuration;
import io.github.jabolina.ztk.notes.NoteFiles;
import io.github.jabolina.ztk.taskwarrior.Task;

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

   private final Configuration configuration;
   private final Clock clock;

   /**
    * Creates the task note service.
    *
    * @param configuration the effective configuration, providing the knowledge base root.
    * @param clock the clock for note names and creation dates.
    */
   public TaskNotes(Configuration configuration, Clock clock) {
      this.configuration = configuration;
      this.clock = clock;
   }

   /**
    * Find or creates the task's note from the template and links it to the task.
    *
    * @param task the task the note belongs to.
    * @return the absolute path of the task's note.
    * @throws ZtkException with {@link ZtkException.Kind#VALIDATION} when the note must be created and the task has no
    *                      project. Nothing is written in that case.
    */
   public Path findOrCreate(Task task) {
      if (task.note().isPresent()) {
         // Already linked: the note attribute is relative to the knowledge base.
         return configuration.dataLocation().resolve(task.note().get());
      }

      Optional<Path> existent = findByTaskUuid(task.uuid());
      if (existent.isPresent()) {
         return existent.get();
      }

      String project = task.project().orElseThrow(() -> ZtkException.validation(
            task.label() + " has no project, which is the folder of its note."
                  + System.lineSeparator()
                  + "Assign one with 'task " + task.id() + " modify project:<name>', then try again."));

      // Kept from the template of the original shell script, which the existing task notes follow.
      SequencedMap<String, String> fields = new LinkedHashMap<>();
      fields.put("task_uuid", task.uuid());
      fields.put("project", project);
      fields.put("issue", task.issue().orElse(""));
      String reference = task.issue().map(value -> "Ref: **" + value + "**\n\n").orElse("");

      return NoteFiles.create(configuration.dataLocation().resolve(project), task.description(),
            OffsetDateTime.now(clock), fields, reference + "# Notes\n\n");
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
               .filter(file -> NoteFiles.frontmatterContains(file, marker))
               .findFirst();
      } catch (IOException e) {
         throw new UncheckedIOException(e);
      }
   }
}
