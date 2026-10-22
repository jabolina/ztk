package io.github.jabolina.ztk.reports;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import io.github.jabolina.ztk.ZtkException;
import io.github.jabolina.ztk.configuration.Configuration;
import io.github.jabolina.ztk.journal.Journal;
import io.github.jabolina.ztk.journal.Session;
import io.github.jabolina.ztk.planning.Effort;
import io.github.jabolina.ztk.planning.Plan;
import io.github.jabolina.ztk.taskwarrior.Status;
import io.github.jabolina.ztk.taskwarrior.Task;
import io.github.jabolina.ztk.taskwarrior.TaskStore;

/**
 * Gathers the facts of a period from Taskwarrior, the journal and the plan, by fixed rules.
 *
 * @since 1.0
 * @author José Bolina
 */
public final class Summaries {

   private static final String MARKDOWN = ".md";
   private static final String REVIEW = "review";
   private static final String WEEK = "week";
   // By name, the tasks without a project last.
   private static final Comparator<Optional<String>> PROJECTS = Comparator
         .comparing((Optional<String> project) -> project.isEmpty())
         .thenComparing(project -> project.orElse(""));
   // Oldest first, and by UUID for tasks entered at the same second, so the same data gives the same summary.
   private static final Comparator<Task> OLDEST = Comparator.comparing(Task::entry).thenComparing(Task::uuid);

   private final Configuration configuration;
   private final Clock clock;
   private final TaskStore store;
   private final Journal journal;
   private final Plan plan;

   /**
    * @param configuration the configuration giving the knowledge base, where task notes are.
    * @param clock the clock telling the day a summary is taken, and the zone weeks start in.
    * @param store the Taskwarrior store.
    * @param journal the journal of sessions.
    * @param plan the goals and efforts, giving each task's goal.
    */
   public Summaries(Configuration configuration, Clock clock, TaskStore store, Journal journal, Plan plan) {
      this.configuration = configuration;
      this.clock = clock;
      this.store = store;
      this.journal = journal;
      this.plan = plan;
   }

   /**
    * @return the week the weekly review covers: the one before the current week, whatever day it is.
    */
   public Week lastWeek() {
      return Week.previous(clock);
   }

   /**
    * Gathers the facts of a week: the tasks with a sign of life in it, and those on the plate now.
    *
    * <p>
    * A task is in when it was completed in the week, given a session or modified in it, or is pending now under
    * review or planned for the week. Tasks completed earlier and deleted ones are left out, whatever happened to them
    * in the week.
    * </p>
    *
    * @param week the week, which may not have ended.
    * @return the facts, grouped by project.
    * @throws ZtkException with {@link ZtkException.Kind#TASKWARRIOR} when Taskwarrior fails.
    */
   public WeeklySummary week(Week week) {
      ZoneId zone = clock.getZone();
      Instant from = week.first().atStartOfDay(zone).toInstant();
      Instant to = week.last().plusDays(1).atStartOfDay(zone).toInstant();
      Predicate<Instant> within = instant -> !instant.isBefore(from) && instant.isBefore(to);
      Map<String, List<Session>> sessions = journal.sessions(week.first(), week.last())
            .collect(Collectors.groupingBy(Session::task));

      // Open tasks, and every task changed since the week started: completing a task modifies it, so those completed
      // in the week are among them even when changed again later.
      Map<Optional<String>, WeeklySummary.Project> projects = store
            .query("(", "status:pending", "or", "status:waiting", "or", "modified.after:" + from, ")").stream()
            .filter(task -> task.status() == Status.COMPLETED
                  ? task.end().filter(within).isPresent()
                  : task.open() && (within.test(task.modified()) || sessions.containsKey(task.uuid())
                        || task.tags().contains(REVIEW) || task.tags().contains(WEEK)))
            .sorted(OLDEST)
            .map(task -> item(task, sessions.getOrDefault(task.uuid(), List.of()), within))
            // Each project is built as its tasks are grouped, kept in order by the sorted map.
            .collect(Collectors.groupingBy(item -> item.task().project(), () -> new TreeMap<>(PROJECTS),
                  Collectors.collectingAndThen(Collectors.toList(), Summaries::project)));
      return new WeeklySummary(week, LocalDate.now(clock), List.copyOf(projects.values()));
   }

   /**
    * Writes a week's summary to the summaries folder, once and for good.
    *
    * <p>
    * The file is never overwritten: it is the record of the week, and agents and people read it later as it was
    * written.
    * </p>
    *
    * @param summary the week's facts.
    * @param narrative the narrative written after the facts; empty to write the facts alone.
    * @return the path of the written file.
    * @throws ZtkException with {@link ZtkException.Kind#VALIDATION} when the week has not ended, or its summary is
    *                      already written.
    * @throws UncheckedIOException when the file cannot be written.
    */
   public Path write(WeeklySummary summary, String narrative) {
      Week week = summary.week();
      // Written before its end, the file would miss the rest of the week for good.
      if (!LocalDate.now(clock).isAfter(week.last())) {
         throw ZtkException.validation(week + " ends on " + week.last() + ", and only an ended week is written."
               + System.lineSeparator() + "Write it from " + week.last().plusDays(1) + " on; run 'ztk summary " + week
               + "' to read it now.");
      }
      Path file = configuration.folders().summaries().resolve(week + MARKDOWN);
      if (Files.exists(file)) {
         throw ZtkException.validation(written(file, week));
      }
      String text = summary.writeMarkdown(new StringBuilder()).toString()
            + (narrative.isEmpty() ? "" : "\n---\n\n" + narrative + "\n");
      try {
         Files.createDirectories(file.getParent());
         // Refuses a file written since the check above, rather than overwriting it.
         Files.writeString(file, text, StandardOpenOption.CREATE_NEW);
         return file;
      } catch (FileAlreadyExistsException e) {
         throw ZtkException.validation(written(file, week));
      } catch (IOException e) {
         throw new UncheckedIOException(e);
      }
   }

   /**
    * @param file the summary's file.
    * @param week the week it covers.
    * @return the refusal for a summary already written, with what to do instead.
    */
   private static String written(Path file, Week week) {
      return file + " is already written, and a summary is never overwritten." + System.lineSeparator()
            + "Edit it, or delete it and run 'ztk summary " + week + " --write' again.";
   }

   /**
    * @param task a task in the summary.
    * @param sessions its sessions in the period.
    * @param within tells whether an instant is in the period.
    * @return the task with its note, goal and sessions, and the day it was last changed when nothing happened to it
    *         in the period.
    */
   private WeeklySummary.Item item(Task task, List<Session> sessions, Predicate<Instant> within) {
      // A completed task was in the period, as it ended there: only open ones can be in without a sign of life.
      Optional<LocalDate> untouched = task.open() && sessions.isEmpty() && !within.test(task.modified())
            ? Optional.of(LocalDate.ofInstant(task.modified(), clock.getZone()))
            : Optional.empty();
      return new WeeklySummary.Item(task,
            task.note().map(configuration.dataLocation()::resolve),
            task.effort().flatMap(plan::effort).flatMap(Effort::goal),
            sessions,
            untouched);
   }

   /**
    * Splits the tasks of one project into its groups.
    *
    * @param items the tasks of the project, in order; at least one.
    * @return the project, each group in the same order.
    */
   private static WeeklySummary.Project project(List<WeeklySummary.Item> items) {
      // Grouped by project: every task of the list has the same one.
      return new WeeklySummary.Project(items.getFirst().task().project(),
            group(items, task -> task.status() == Status.COMPLETED),
            group(items, task -> task.open() && task.tags().contains(REVIEW)),
            group(items, task -> task.open() && !task.tags().contains(REVIEW)));
   }

   /**
    * @param items the tasks of a project, in order.
    * @param belongs tells whether a task is in the group.
    * @return the tasks of the group, in the same order.
    */
   private static List<WeeklySummary.Item> group(List<WeeklySummary.Item> items, Predicate<Task> belongs) {
      return items.stream().filter(item -> belongs.test(item.task())).toList();
   }
}
