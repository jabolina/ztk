package io.github.jabolina.ztk.journal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import io.github.jabolina.ztk.ZtkException;
import io.github.jabolina.ztk.configuration.Configuration;
import io.github.jabolina.ztk.taskwarrior.Status;
import io.github.jabolina.ztk.taskwarrior.Task;

/**
 * The journal: one Markdown file per day, holding the sessions of that day.
 *
 * <p>
 * ztk owns the shape of a session, writing its heading and labels; callers give only the content of each part. A
 * day's file is named after the day, e.g. {@code journal/2026-10-22.md}, and starts with the day as its heading.
 * </p>
 *
 * @since 1.0
 * @author José Bolina
 */
public final class Journal {

   private static final String MARKDOWN = ".md";
   // Every session starts with a heading linking its task: "## [[<uuid>]] · <title>".
   private static final String SESSION = "## [[";
   // A day's file, e.g. 2026-10-22.md; anything else in the folder is not part of the journal.
   private static final Pattern DAY = Pattern.compile("\\d{4}-\\d{2}-\\d{2}\\.md");
   // Splits a day's file before each session heading.
   private static final Pattern BLOCKS = Pattern.compile("^(?=## \\[\\[)", Pattern.MULTILINE);
   // One session as ztk writes it: the task and title, then each part up to the next label. A part may span lines.
   private static final Pattern BLOCK = Pattern.compile(
         "## \\[\\[([^\\]]+)]] · ([^\\n]*)\\n\\*\\*Done:\\*\\* (.*?)\\n\\*\\*Now:\\*\\* (.*?)\\n\\*\\*Next:\\*\\* (.*?)\\s*",
         Pattern.DOTALL);

   private final Configuration configuration;
   private final Clock clock;

   /**
    * @param configuration the configuration giving the journal folder.
    * @param clock the clock telling which day a session is written on.
    */
   public Journal(Configuration configuration, Clock clock) {
      this.configuration = configuration;
      this.clock = clock;
   }

   /**
    * Writes a session at the end of today's journal file, creating the file and its folder if needed.
    *
    * @param task the task worked on.
    * @param title what the session was about, in one line.
    * @param done what was done.
    * @param now where things are.
    * @param next what comes next.
    * @return the session as written, numbered after the sessions already in the day's file.
    * @throws UncheckedIOException when the journal cannot be read or written.
    * @throws ZtkException with {@link ZtkException.Kind#NOT_FOUND} when the task is deleted.
    */
   public Session add(Task task, String title, String done, String now, String next) {
      // A deleted task is gone from the user's work: a session on it would be one nobody reads.
      if (task.status() == Status.DELETED) {
         throw ZtkException.notFound(task.label() + " is deleted." + System.lineSeparator()
               + "Write the session on a pending or completed task; run 'ztk list' to see the pending ones.");
      }
      LocalDate today = LocalDate.now(clock);
      Path file = configuration.folders().journal().resolve(today + MARKDOWN);
      try {
         Files.createDirectories(file.getParent());
         boolean started = Files.exists(file);
         int position = started ? sessions(file) + 1 : 1;
         // Appended, never rewritten: earlier sessions of the day stay as they are. A blank line before each session
         // keeps the Markdown readable.
         String text = (started ? "" : "# " + today + "\n")
               + "\n" + SESSION + task.uuid() + "]] · " + title + "\n"
               + "**Done:** " + done + "\n"
               + "**Now:** " + now + "\n"
               + "**Next:** " + next + "\n";
         Files.writeString(file, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
         return new Session(today + "#" + position, file, task.uuid(), title, done, now, next);
      } catch (IOException e) {
         throw new UncheckedIOException(e);
      }
   }

   /**
    * Gives the last sessions written on a task.
    *
    * @param task the task.
    * @param count how many sessions to give at most.
    * @return the task's sessions, newest first; empty when it has none.
    * @throws UncheckedIOException when the journal cannot be read.
    */
   public List<Session> last(Task task, int count) {
      Path folder = configuration.folders().journal();
      if (!Files.isDirectory(folder)) {
         return List.of();
      }
      // Sessions are written while the task exists: the days before it was created hold none of them.
      String entered = LocalDate.ofInstant(task.entry(), clock.getZone()) + MARKDOWN;
      try (Stream<Path> files = Files.list(folder)) {
         return files
               .filter(file -> DAY.matcher(file.getFileName().toString()).matches())
               // Named after ISO days, which sort as text.
               .filter(file -> file.getFileName().toString().compareTo(entered) >= 0)
               .sorted(Comparator.comparing(Path::getFileName, Comparator.reverseOrder()))
               // Lazy: the walk stops at the day giving the last session needed.
               .flatMap(file -> {
                  String text = text(file);
                  // Most days hold no session of the task: only those naming it are parsed.
                  return text.contains(task.uuid())
                        ? read(file, text, true).filter(session -> session.task().equals(task.uuid()))
                        : Stream.empty();
               })
               .limit(count)
               .toList();
      } catch (IOException e) {
         throw new UncheckedIOException(e);
      }
   }

   /**
    * Gives every session written in a range of days.
    *
    * @param from the first day.
    * @param to the last day, included.
    * @return the sessions, in the order they were written.
    * @throws UncheckedIOException when the journal cannot be read.
    */
   public Stream<Session> sessions(LocalDate from, LocalDate to) {
      Path folder = configuration.folders().journal();
      // By name: a day without sessions has no file, and nothing else in the folder is read.
      return from.datesUntil(to.plusDays(1))
            .map(day -> folder.resolve(day + MARKDOWN))
            .filter(Files::isRegularFile)
            .flatMap(file -> read(file, text(file), false));
   }

   /**
    * @param file a day's journal file.
    * @return its whole text.
    * @throws UncheckedIOException when the file cannot be read.
    */
   private static String text(Path file) {
      try {
         return Files.readString(file);
      } catch (IOException e) {
         throw new UncheckedIOException(e);
      }
   }

   /**
    * Reads the sessions of a day's file.
    *
    * @param file the day's journal file.
    * @param text the file's text.
    * @param newestFirst whether the last written comes first, rather than the first written.
    * @return its sessions; a session not in ztk's shape is skipped.
    */
   private static Stream<Session> read(Path file, String text, boolean newestFirst) {
      String day = file.getFileName().toString().replace(MARKDOWN, "");
      // The day's heading, before the first session, is left out.
      List<String> blocks = BLOCKS.splitAsStream(text).filter(block -> block.startsWith(SESSION)).toList();
      // By index: a session's ID is its position among every session of the day, whatever its task.
      return IntStream.range(0, blocks.size())
            .map(index -> newestFirst ? blocks.size() - 1 - index : index)
            .mapToObj(index -> session(day + "#" + (index + 1), file, blocks.get(index)))
            .flatMap(Optional::stream);
   }

   /**
    * Reads one session in ztk's shape.
    *
    * @param id the session's ID, from its day and position.
    * @param file the day's journal file.
    * @param block the session's text, from its heading to the next session.
    * @return the session; empty when the block is not in ztk's shape, e.g. edited by hand.
    */
   private static Optional<Session> session(String id, Path file, String block) {
      Matcher matcher = BLOCK.matcher(block);
      return matcher.matches()
            ? Optional.of(new Session(id, file, matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4),
                  matcher.group(5)))
            : Optional.empty();
   }

   /**
    * Counts the sessions in a day's file.
    *
    * @param file the day's journal file.
    * @return the number of sessions it holds.
    * @throws IOException when the file cannot be read.
    */
   private static int sessions(Path file) throws IOException {
      try (Stream<String> lines = Files.lines(file)) {
         return (int) lines.filter(line -> line.startsWith(SESSION)).count();
      }
   }
}
