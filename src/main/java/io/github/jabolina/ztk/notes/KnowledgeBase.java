package io.github.jabolina.ztk.notes;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.github.jabolina.ztk.configuration.Configuration;
import io.github.jabolina.ztk.configuration.Folders;
import io.github.jabolina.ztk.util.Frontmatter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;

/**
 * The notes under {@code data.location}, read from disk on every call.
 *
 * <p>
 * Every Markdown file is a note. Its type comes from the folder it lives in, never from its name or content, so
 * existing notes need no changes to be read.
 * </p>
 *
 * <p>
 * Note bodies are never held in memory: each note is read line by line, and callers keep only what they need from
 * it, such as a search match or the note's links.
 * </p>
 *
 * <p>
 * Every Markdown file is a note, except instructions for agents ({@code AGENTS.md}, {@code CLAUDE.md}) and files in
 * hidden folders. Its type comes from the folder it lives in, never from its name or content, so existing notes need
 * no changes to be read.
 * </p>
 *
 * @since 1.0
 * @author José Bolina
 */
public final class KnowledgeBase {

   private static final String EXTENSION = ".md";

   // Instructions for AI agents, not knowledge.
   private static final Set<String> NOT_NOTES = Set.of("AGENTS.md", "CLAUDE.md");

   private final Configuration configuration;
   private final PlanningNotes planning;

   // The first segment of every configured folder, e.g. "notes" for notes/zettel: never a project folder.
   private final Set<Path> reserved;

   /**
    * @param configuration the configuration giving the knowledge base location and its folders.
    * @param planning recognizes the notes that are planning items, such as goals and efforts.
    */
   public KnowledgeBase(Configuration configuration, PlanningNotes planning) {
      this.configuration = configuration;
      this.planning = planning;
      // Folders are always inside data.location.
      this.reserved = configuration.folders().all().stream()
            .map(folder -> configuration.dataLocation().relativize(folder).getName(0))
            .collect(Collectors.toUnmodifiableSet());;
   }

   /**
    * Reads the notes, extracting a value from each as the stream is consumed.
    *
    * <p>
    * The reader gets the note and a stream of its body lines, read from disk as the line stream is consumed. That
    * line stream is closed when the reader returns, so the reader must consume it before returning; it may also stop
    * early.
    * </p>
    *
    * @param reader extracts a value from a note and its body lines, or returns empty to keep nothing.
    * @param warnings receives the notes skipped because they cannot be read.
    * @param <R> the type of the extracted values.
    * @return the extracted values, in no particular order. Notes are only read as it is consumed. It must be closed,
    *         e.g. with try-with-resources, to release the directory walk.
    * @throws UncheckedIOException UncheckedIOException when a folder of the knowledge base cannot be listed.
    */
   public <R> Stream<R> read(BiFunction<Note, Stream<Line>, Optional<R>> reader, Consumer<NoteWarning> warnings) {
      return walk(configuration.dataLocation(), reader, warnings);
   }

   /**
    * Reads the notes of one project, like {@link #read(BiFunction, Consumer)}.
    *
    * <p>
    * A project's notes live in the folder named after it, at the root of the knowledge base, so no other folder is
    * read.
    * </p>
    *
    * @param project the Taskwarrior project, e.g. {@code Alpha}.
    * @param reader extracts a value from a note and its body lines, or returns empty to keep nothing.
    * @param warnings receives the notes skipped because they cannot be read.
    * @param <R> the type of the extracted values.
    * @return the extracted values; empty when the project has no folder, or its name is a configured folder. It must
    *         be closed, e.g. with try-with-resources.
    * @throws UncheckedIOException when a folder of the knowledge base cannot be listed.
    */
   public <R> Stream<R> read(String project, BiFunction<Note, Stream<Line>, Optional<R>> reader, Consumer<NoteWarning> warnings) {
      return projectFolder(project)
            .map(folder -> walk(folder, reader, warnings))
            .orElseGet(Stream::empty);
   }

   /**
    * Lists the names of every note, without reading the notes.
    *
    * <p>
    * Cheap enough to run on every keystroke, e.g. to complete a note name.
    * </p>
    *
    * @return each note's path relative to {@code data.location} without {@code .md}, e.g. {@code Alpha/fix-bug}, in no
    *         particular order.
    * @throws UncheckedIOException when a folder of the knowledge base cannot be listed.
    */
   public List<String> names() {
      try (Stream<Path> notes = notes(configuration.dataLocation())) {
         return notes.map(this::name).toList();
      }
   }

   /**
    * Finds the folder holding a project's notes.
    *
    * <p>
    * It is the folder named after the project at the root of the knowledge base. A configured folder, such as
    * {@code zettel}, is never a project's, even when a project has its name.
    * </p>
    *
    * @param project the Taskwarrior project, e.g. {@code Alpha}.
    * @return the folder; empty when there is none, or the name is not a single folder name.
    */
   public Optional<Path> projectFolder(String project) {
      Path folder = configuration.dataLocation().resolve(project).normalize();
      // Only a direct child of the knowledge base is a project folder: "../x" or "a/b" never name one, and neither does
      // a configured folder such as "goals".
      if (!configuration.dataLocation().equals(folder.getParent())
            || reserved.contains(folder.getFileName())
            || !Files.isDirectory(folder)) {
         return Optional.empty();
      }
      return Optional.of(folder);
   }

   /**
    * Walks a folder of the knowledge base, reading every note under it.
    *
    * @param root the folder to walk, inside {@code data.location}.
    * @param reader extracts a value from a note and its body lines.
    * @param warnings receives the notes skipped because they cannot be read.
    * @param <R> the type of the extracted values.
    * @return the extracted values, read as the stream is consumed. It must be closed.
    * @throws UncheckedIOException when the folder cannot be read.
    */
   private <R> Stream<R> walk(Path root, BiFunction<Note, Stream<Line>, Optional<R>> reader, Consumer<NoteWarning> warnings) {
      // The walk's close handler carries over to the returned pipeline: closing it closes the walk.
      return notes(root)
            .map(file -> read(file, reader, warnings))
            .flatMap(Optional::stream);
   }

   /**
    * Lists the note files under a folder, without reading them.
    *
    * @param root the folder to list, inside {@code data.location}.
    * @return the files that are notes, listed as the stream is consumed. It must be closed.
    * @throws UncheckedIOException when the folder cannot be listed.
    */
   private static Stream<Path> notes(Path root) {
      return files(root)
            .filter(file -> file.getFileName().toString().endsWith(EXTENSION))
            .filter(file -> !NOT_NOTES.contains(file.getFileName().toString()))
            .filter(Files::isRegularFile);
   }

   /**
    * Lists the files under a folder, depth first and lazily, without entering hidden folders.
    *
    * <p>
    * Hidden folders hold tool state, such as {@code .git} or {@code .obsidian}, never notes. They are skipped
    * before being listed, so a large repository inside the knowledge base costs nothing to search.
    * </p>
    *
    * @param folder the folder to list.
    * @return every non-folder entry under it, listed as the stream is consumed.
    * @throws UncheckedIOException when the folder cannot be listed.
    */
   private static Stream<Path> files(Path folder) {
      Stream<Path> entries;
      try {
         entries = Files.list(folder);
      } catch (IOException e) {
         throw new UncheckedIOException("Failed reading folder: " + folder, e);
      }
      return entries.flatMap(entry -> {
         // Links to folders are not followed, like Files.walk.
         if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS))
            return Stream.of(entry);

         return entry.getFileName().toString().startsWith(".")
               ? Stream.empty()
               : files(entry);
      });
   }

   /**
    * Reads one note: the frontmatter at once, the body lazily through the reader.
    *
    * @param file the note's file.
    * @param reader extracts a value from the note and its body lines.
    * @param warnings receives the notes skipped because they cannot be read.
    * @param <R> the type of the extracted value.
    * @return what the reader extracted; empty when it kept nothing or the note was skipped.
    */
   private <R> Optional<R> read(Path file, BiFunction<Note, Stream<Line>, Optional<R>> reader, Consumer<NoteWarning> warnings) {
      try (BufferedReader content = Files.newBufferedReader(file)) {
         String first = content.readLine();
         JsonNode frontmatter = MissingNode.getInstance();
         Stream<String> body;

         // Lines read before the body starts, so body line numbers match the file's.
         int consumed;
         if (Frontmatter.DELIMITER.equals(first)) {
            List<String> fields = new ArrayList<>();
            for (String line = content.readLine(); line != null && !line.equals(Frontmatter.DELIMITER); line = content.readLine()) {
               fields.add(line);
            }
            // The opening and closing delimiters.
            // A frontmatter never closed takes the whole file.
            consumed = fields.size() + 2;
            frontmatter = Frontmatter.parse(fields);
            body = content.lines();
         } else {
            // No frontmatter defined.
            // Consider the file as the whole content.
            consumed = 0;
            body = first == null
                  ? Stream.empty()
                  : Stream.concat(Stream.of(first), content.lines());
         }

         String fileName = file.getFileName().toString();
         String title = frontmatter.path("title").asStringOpt()
               .orElse(fileName.substring(0, fileName.length() - EXTENSION.length()));

         // Read from the note itself, so search never needs Taskwarrior.
         Optional<String> task = frontmatter.path("task_uuid").asStringOpt();
         Optional<PlanningNotes.Planned> planned = planning.planned(file);
         Note.Header header = new Note.Header(name(file), file, title, Files.getLastModifiedTime(file).toInstant());
         NoteType type = planned.map(PlanningNotes.Planned::type).orElseGet(() -> type(file, task.isPresent()));
         Note note = switch (type) {
            case TASK -> new Note.Task(header, task.orElseThrow());
            case PAPER -> new Note.Paper(header, frontmatter.path("pdf").asStringOpt().map(file::resolveSibling));
            case GOAL, EFFORT -> new Note.Planning(header, type, planned.orElseThrow().id());
            default -> new Note.Plain(header, type);
         };

         AtomicInteger number = new AtomicInteger(consumed);
         return reader.apply(note, body.map(text -> new Line(number.incrementAndGet(), text)));
      } catch (IOException e) {
         warnings.accept(unreadable(file, e));
         return Optional.empty();
      } catch (UncheckedIOException e) {
         // Body lines are decoded as the reader consumes them, so a bad byte past the first buffer surfaces here.
         warnings.accept(unreadable(file, e.getCause()));
         return Optional.empty();
      }
   }

   /**
    * Describes why a note cannot be read, and how to fix it.
    *
    * @param file the note's file.
    * @param failure what reading it failed with.
    * @return the warning for the skipped note.
    */
   private static NoteWarning unreadable(Path file, IOException failure) {
      // The decoder's own message ("Input length = 1") says nothing useful, so name the problem instead.
      if (failure instanceof CharacterCodingException) {
         return new NoteWarning(file, "Not valid UTF-8, note skipped.",
               "Convert it to UTF-8, e.g. with iconv -f latin1 -t utf-8.");
      }
      return new NoteWarning(file, "Cannot be read (" + failure.getMessage() + "), note skipped.",
            "Check that the file exists and its permissions allow reading it.");
   }

   /**
    * Names a note by its place in the knowledge base.
    *
    * @param file the note's file.
    * @return the path relative to {@code data.location} without {@code .md}, e.g. {@code zettel/20261015-causality}.
    */
   private String name(Path file) {
      String relative = configuration.dataLocation().relativize(file).toString();
      return relative.substring(0, relative.length() - EXTENSION.length());
   }

   /**
    * Gives the location of a file named relative to the knowledge base, as a task's {@code note} attribute names it.
    *
    * @param relative the path relative to {@code data.location}, e.g. {@code Alpha/fix-bug.md}.
    * @return its absolute path; the file may not exist.
    */
   public Path resolve(String relative) {
      return configuration.dataLocation().resolve(relative).normalize();
   }

   /**
    * Gives the type of a note that is no planning item from the folder it lives in.
    *
    * @param file the note's file.
    * @param taskNote whether the note names a task in its frontmatter.
    * @return the note type.
    */
   private NoteType type(Path file, boolean taskNote) {
      // Material under the goals and efforts folders is not a planning item: the folders are reserved, so it reads as
      // other below.
      Folders folders = configuration.folders();
      if (file.startsWith(folders.zettel())) {
         return NoteType.ZETTEL;
      }
      if (file.startsWith(folders.papers())) {
         return NoteType.PAPER;
      }

      Path relative = configuration.dataLocation().relativize(file);
      // Files at the root belong to no folder; any other folder at the root is a project folder.
      if (relative.getNameCount() == 1 || reserved.contains(relative.getName(0))) {
         return NoteType.OTHER;
      }
      return taskNote ? NoteType.TASK : NoteType.PROJECT;
   }
}
