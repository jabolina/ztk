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
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.github.jabolina.ztk.ZtkException;
import io.github.jabolina.ztk.configuration.Configuration;
import io.github.jabolina.ztk.journal.Journal;
import io.github.jabolina.ztk.journal.Session;
import io.github.jabolina.ztk.planning.Effort;
import io.github.jabolina.ztk.planning.Goal;
import io.github.jabolina.ztk.planning.Period;
import io.github.jabolina.ztk.planning.Plan;
import io.github.jabolina.ztk.planning.PlanProblem;
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
   // A week is told apart from a quarter or a year by its shape; each is then checked by its own rules.
   private static final Pattern WEEKLY = Pattern.compile("\\d{4}-W\\d{2}");
   private static final Pattern PLANNED = Pattern.compile("\\d{4}(?:-Q[1-4])?");
   // By name, the tasks without a project last.
   private static final Comparator<Optional<String>> PROJECTS = Comparator
         .comparing((Optional<String> project) -> project.isEmpty())
         .thenComparing(project -> project.orElse(""));
   // Oldest first, and by UUID for tasks entered at the same second, so the same data gives the same summary.
   private static final Comparator<Task> OLDEST = Comparator.comparing(Task::entry).thenComparing(Task::uuid);
   private static final Comparator<Effort> BY_SLUG = Comparator.comparing(Effort::slug);

   private final Configuration configuration;
   private final Clock clock;
   private final TaskStore store;
   private final Journal journal;
   private final Plan plan;

   /**
    * @param configuration the configuration giving the knowledge base, where task notes are.
    * @param clock the clock telling the day a summary is taken, and the zone periods start in.
    * @param store the Taskwarrior store.
    * @param journal the journal of sessions.
    * @param plan the goals and efforts.
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
    * Gathers the facts of a period, by the rules of its kind.
    *
    * @param period the period as written on the command line: an ISO week, e.g. {@code 2026-W42}, a quarter, e.g.
    *               {@code 2026-Q4}, or a year, e.g. {@code 2026}; it may not have ended.
    * @return a week's facts, grouped by project, or a quarter's or a year's, grouped by plan.
    * @throws ZtkException with {@link ZtkException.Kind#USAGE} when the period is none of them, and with
    *                      {@link ZtkException.Kind#TASKWARRIOR} when Taskwarrior fails.
    */
   public Summary of(String period) {
      if (WEEKLY.matcher(period).matches()) {
         return week(Week.parse(period));
      }
      if (PLANNED.matcher(period).matches()) {
         return plan(Period.parse(period));
      }
      throw ZtkException.usage("The period '" + period + "' must be an ISO week, a quarter or a year,"
            + " e.g. 2026-W42, 2026-Q4 or 2026.");
   }

   /**
    * Writes a summary to the summaries folder, once and for good.
    *
    * <p>
    * The file is never overwritten: it is the record of the period, and agents and people read it later as it was
    * written.
    * </p>
    *
    * @param summary the period's facts.
    * @param narrative the narrative written after the facts; empty to write the facts alone.
    * @return the path of the written file.
    * @throws ZtkException with {@link ZtkException.Kind#VALIDATION} when the period has not ended, a goal of a quarter's
    *                      or a year's summary needs closing, or its summary is already written.
    * @throws UncheckedIOException when the file cannot be written.
    */
   public Path write(Summary summary, String narrative) {
      String name = summary.name();
      LocalDate last = summary.last();
      // Written before its end, the file would miss the rest of the period for good.
      if (!LocalDate.now(clock).isAfter(last)) {
         throw ZtkException.validation(name + " ends on " + last + ", and only an ended period is written."
               + System.lineSeparator() + "Write it from " + last.plusDays(1) + " on; run 'ztk summary " + name
               + "' to read it now.");
      }
      if (summary instanceof PlanSummary planned) {
         // As check reports them, each with the command closing it.
         String open = planned.needsClosing()
               .map(PlanProblem.NeedsClosing::new)
               .map(problem -> System.lineSeparator() + "  " + problem.id().map(id -> id + ": ").orElse("")
                     + problem.message() + " " + problem.hint())
               .collect(Collectors.joining());
         // Closing them is the review the file records: written before, it would show them open for good.
         if (!open.isEmpty()) {
            throw ZtkException.validation(name + " is not written while its goals need closing, as closing them is"
                  + " the review it records. Close each first:" + open);
         }
      }
      Path file = configuration.folders().summaries().resolve(name + MARKDOWN);
      if (Files.exists(file)) {
         throw ZtkException.validation(written(file, name));
      }
      String text = summary.writeMarkdown(new StringBuilder()).toString()
            + (narrative.isEmpty() ? "" : "\n---\n\n" + narrative + "\n");
      try {
         Files.createDirectories(file.getParent());
         // Refuses a file written since the check above, rather than overwriting it.
         Files.writeString(file, text, StandardOpenOption.CREATE_NEW);
         return file;
      } catch (FileAlreadyExistsException e) {
         throw ZtkException.validation(written(file, name));
      } catch (IOException e) {
         throw new UncheckedIOException(e);
      }
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
   private WeeklySummary week(Week week) {
      Activity activity = activity(week.first(), week.last());
      Map<Optional<String>, WeeklySummary.Project> projects = tasks(activity)
            .filter(task -> activity.alive(task)
                  || task.open() && (task.tags().contains(REVIEW) || task.tags().contains(WEEK)))
            .sorted(OLDEST)
            .map(task -> item(task, activity, Optional.of(activity.sessionsOf(task))))
            // Each project is built as its tasks are grouped, kept in order by the sorted map.
            .collect(Collectors.groupingBy(item -> item.task().project(), () -> new TreeMap<>(PROJECTS),
                  Collectors.collectingAndThen(Collectors.toList(), Summaries::project)));
      return new WeeklySummary(week, LocalDate.now(clock), List.copyOf(projects.values()));
   }

   /**
    * Gathers the facts of a quarter or a year: its goals, their efforts and their tasks, then the goals of other
    * periods holding its efforts, then the work without an effort.
    *
    * <p>
    * A task is in when it shows a sign of life in the period, or is open in a listed effort: what is left of it. Tasks
    * completed earlier and deleted ones are left out.
    * </p>
    *
    * @param period the quarter or year, which may not have ended.
    * @return the facts, grouped by plan.
    * @throws ZtkException with {@link ZtkException.Kind#TASKWARRIOR} when Taskwarrior fails.
    */
   private PlanSummary plan(Period period) {
      Activity activity = activity(period.start(), period.end());
      // Without an effort and a goal to hang under, a task is general work; check reports the missing ones.
      Predicate<Task> placed = task -> task.effort()
            .flatMap(plan::effort)
            .flatMap(Effort::goal)
            .flatMap(plan::goal)
            .isPresent();
      // One pass: the tasks of efforts by effort, open ones too, as whether an effort is listed may hang on another of
      // its tasks; and the others by project, without sessions to keep the facts small, as the narrative tells general
      // work in a line or two.
      return tasks(activity)
            .filter(task -> activity.alive(task) || task.open() && placed.test(task))
            .sorted(OLDEST)
            .collect(Collectors.teeing(
                  Collectors.filtering(placed, Collectors.groupingBy(task -> task.effort().orElseThrow(),
                        Collectors.mapping(task -> item(task, activity, Optional.of(activity.sessionsOf(task))),
                              Collectors.toList()))),
                  Collectors.filtering(placed.negate(), Collectors.groupingBy(Task::project,
                        () -> new TreeMap<>(PROJECTS),
                        Collectors.mapping(task -> item(task, activity, Optional.empty()),
                              Collectors.collectingAndThen(Collectors.toList(),
                                    items -> new PlanSummary.ProjectFacts(items.getFirst().task().project(), items))))),
                  (efforts, general) -> {
                     Tree tree = new Tree(period, plan, efforts);
                     return new PlanSummary(period, LocalDate.now(clock), tree.goals(), tree.otherGoals(),
                           List.copyOf(general.values()));
                  }));
   }

   /**
    * @param first the first day of a period.
    * @param last the last day of the period.
    * @return what happened in the period, its days taken in the clock's zone.
    */
   private Activity activity(LocalDate first, LocalDate last) {
      ZoneId zone = clock.getZone();
      return new Activity(first.atStartOfDay(zone).toInstant(), last.plusDays(1).atStartOfDay(zone).toInstant(),
            journal.sessions(first, last).collect(Collectors.groupingBy(Session::task)));
   }

   /**
    * @param activity the period.
    * @return the open tasks, and every task changed since the period started: completing a task modifies it, so those
    *         completed in the period are among them even when changed again later.
    * @throws ZtkException with {@link ZtkException.Kind#TASKWARRIOR} when Taskwarrior fails.
    */
   private Stream<Task> tasks(Activity activity) {
      return store.query("(", "status:pending", "or", "status:waiting", "or", "modified.after:" + activity.from(), ")")
            .stream();
   }

   /**
    * @param file the summary's file.
    * @param name the period it covers.
    * @return the refusal for a summary already written, with what to do instead.
    */
   private static String written(Path file, String name) {
      return file + " is already written, and a summary is never overwritten." + System.lineSeparator()
            + "Edit it, or delete it and run 'ztk summary " + name + " --write' again.";
   }

   /**
    * @param task a task in the summary.
    * @param activity the period.
    * @param sessions its sessions in the period; empty when not gathered.
    * @return the task with its note, goal and sessions, and when it was last changed if it shows no sign of life in
    *         the period.
    */
   private Item item(Task task, Activity activity, Optional<List<Session>> sessions) {
      // Only an open task is in without a sign of life: a completed one is in for having ended in the period.
      Optional<Item.Untouched> untouched = activity.alive(task)
            ? Optional.empty()
            : Optional.of(new Item.Untouched(LocalDate.ofInstant(task.modified(), clock.getZone()),
                  !task.modified().isBefore(activity.to())));
      return new Item(task,
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
   private static WeeklySummary.Project project(List<Item> items) {
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
   private static List<Item> group(List<Item> items, Predicate<Task> belongs) {
      return items.stream().filter(item -> belongs.test(item.task())).toList();
   }

   /**
    * What happened in a period: its bounds, and the sessions written in it.
    *
    * @param from     The period's first instant.
    * @param to       The first instant after it.
    * @param sessions The sessions written in it, by the UUID of their task.
    */
   private record Activity(Instant from, Instant to, Map<String, List<Session>> sessions) {

      /**
       * @param instant an instant.
       * @return whether it is in the period.
       */
      boolean within(Instant instant) {
         return !instant.isBefore(from) && instant.isBefore(to);
      }

      /**
       * Tells whether a task shows a sign of life in the period.
       *
       * @param task a task.
       * @return whether it was completed in the period, or is open and was modified or given a session in it; never
       *         for deleted tasks, nor for those completed earlier, whatever happened to them since.
       */
      boolean alive(Task task) {
         return task.status() == Status.COMPLETED
               ? task.end().filter(this::within).isPresent()
               : task.open() && (within(task.modified()) || sessions.containsKey(task.uuid()));
      }

      /**
       * @param task a task.
       * @return its sessions in the period, in the order they were written.
       */
      List<Session> sessionsOf(Task task) {
         return sessions.getOrDefault(task.uuid(), List.of());
      }
   }

   /**
    * The goals and efforts of a quarter's or a year's summary, with the rules telling which are listed and where.
    *
    * @param period The quarter or year.
    * @param plan   The goals and efforts.
    * @param tasks  The tasks of each effort, by slug, oldest first, open ones whether listed or not; an effort without
    *               any is missing.
    */
   private record Tree(Period period, Plan plan, Map<String, List<Item>> tasks) {

      /**
       * @return the goals of the period at its top, by ID: a quarter's goals, or a year's yearly goals and the
       *         quarterly goals pointing to none of them.
       */
      List<PlanSummary.GoalFacts> goals() {
         return plan.goals()
               .filter(this::inPeriod)
               .filter(goal -> goal.parent().flatMap(plan::goal).filter(this::inPeriod).isEmpty())
               .sorted(Goal.ORDER)
               .map(this::facts)
               .toList();
      }

      /**
       * @return the goals of other periods holding a listed effort, by ID: earlier ones holding an effort carried over,
       *         and any holding one closed or worked on in the period, as when the review moves it on before writing.
       */
      List<PlanSummary.GoalFacts> otherGoals() {
         return plan.goals()
               .filter(goal -> !inPeriod(goal))
               .filter(goal -> efforts(goal).findAny().isPresent())
               .sorted(Goal.ORDER)
               .map(this::facts)
               .toList();
      }

      /**
       * @param goal a goal in the summary.
       * @return the goal with its listed efforts and their tasks, and the goals of the period under it.
       */
      private PlanSummary.GoalFacts facts(Goal goal) {
         return new PlanSummary.GoalFacts(goal,
               efforts(goal)
                     .map(effort -> new PlanSummary.EffortFacts(effort, carriedOver(effort),
                           tasks.getOrDefault(effort.slug(), List.of())))
                     .toList(),
               plan.goals()
                     .filter(this::inPeriod)
                     .filter(child -> child.parent().filter(goal.id()::equals).isPresent())
                     .sorted(Goal.ORDER)
                     .map(this::facts)
                     .toList());
      }

      /**
       * @param goal a goal.
       * @return its listed efforts, by slug.
       */
      private Stream<Effort> efforts(Goal goal) {
         return plan.efforts()
               .filter(effort -> effort.goal().filter(goal.id()::equals).isPresent())
               .filter(this::listed)
               .sorted(BY_SLUG);
      }

      /**
       * @param goal a goal.
       * @return whether it is a goal of the period: a quarterly goal of the quarter, or of a quarter of the year, or a
       *         yearly goal of the year.
       */
      private boolean inPeriod(Goal goal) {
         return goal.period() instanceof Period.OfQuarter quarter
               ? period.contains(quarter)
               : goal.period().equals(period);
      }

      /**
       * Tells whether an effort is listed in the summary.
       *
       * @param effort an effort.
       * @return whether its goal is of the period, it is carried over from an earlier quarter, it was closed in the
       *         period, or one of its tasks shows a sign of life in it.
       */
      private boolean listed(Effort effort) {
         return effort.quarter().filter(period::contains).isPresent()
               || effort.open() && effort.quarter().filter(quarter -> quarter.end().isBefore(period.start())).isPresent()
               || effort.closed().filter(this::within).isPresent()
               // In for a sign of life is in without being untouched.
               || tasks.getOrDefault(effort.slug(), List.of()).stream().anyMatch(item -> item.untouched().isEmpty());
      }

      /**
       * @param effort an effort.
       * @return whether it was carried over as of the period's end: carried over now, and its goal's quarter ended by
       *         then; one carried over only later, from a quarter after the period, was not yet.
       */
      private boolean carriedOver(Effort effort) {
         return effort.carriedOver() && effort.quarter().filter(quarter -> !quarter.end().isAfter(period.end())).isPresent();
      }

      /**
       * @param day a day as written in a note, e.g. {@code 2026-09-30}.
       * @return whether it is a day of the period; a value that is no day tells nothing about it.
       */
      private boolean within(String day) {
         try {
            LocalDate date = LocalDate.parse(day);
            return !date.isBefore(period.start()) && !date.isAfter(period.end());
         } catch (DateTimeParseException e) {
            return false;
         }
      }
   }
}
