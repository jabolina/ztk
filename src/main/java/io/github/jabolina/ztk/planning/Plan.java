package io.github.jabolina.ztk.planning;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.github.jabolina.ztk.ZtkException;
import io.github.jabolina.ztk.configuration.Configuration;
import io.github.jabolina.ztk.taskwarrior.Task;

/**
 * Every goal and effort of the knowledge base, with the rules they must keep.
 *
 * <p>
 * For readers joining the plan with the rest of the knowledge base, such as the graph and {@code check}. Commands
 * changing the plan go through {@link Goals} and {@link Efforts}.
 * </p>
 *
 * @since 1.0
 * @author José Bolina
 */
public final class Plan {

   private static final String MARKDOWN = ".md";

   // To name the misnamed notes as the knowledge base does.
   private final Path dataLocation;
   // Every goal file, by goal ID, in the order read: the first is the goal, any other a duplicate.
   private final Map<String, List<Goal>> goals;
   // Every effort, by slug; slugs are file names, so they are unique.
   private final Map<String, Effort> efforts;
   // The notes sitting where goals do, but not named after a goal ID of their folder.
   private final List<Path> misnamed;

   private Plan(Path dataLocation, Map<String, List<Goal>> goals, Map<String, Effort> efforts, List<Path> misnamed) {
      this.dataLocation = dataLocation;
      this.goals = goals;
      this.efforts = efforts;
      this.misnamed = misnamed;
   }

   /**
    * Reads every goal and effort from their notes.
    *
    * @param configuration the configuration giving the goals and efforts folders.
    * @param clock the clock telling which goals need closing and which efforts are carried over.
    * @return the plan, as the notes are now.
    * @throws UncheckedIOException when a folder, a goal or an effort cannot be read.
    */
   public static Plan read(Configuration configuration, Clock clock) {
      GoalNotes goalNotes = new GoalNotes(configuration, clock);
      Map<String, List<Goal>> goals;
      try (Stream<Goal> all = goalNotes.all()) {
         goals = all.collect(Collectors.groupingBy(Goal::id));
      }
      Map<String, Effort> efforts;
      try (Stream<Effort> all = new EffortNotes(configuration, clock).all()) {
         efforts = all.collect(Collectors.toMap(Effort::slug, Function.identity()));
      }
      List<Path> misnamed;
      try (Stream<Path> all = goalNotes.misnamed()) {
         misnamed = all.toList();
      }
      return new Plan(configuration.dataLocation(), goals, efforts, misnamed);
   }

   /**
    * @return every yearly and quarterly goal, of every period, in no particular order. Of two files with one goal ID,
    *         only the first read is there; {@link #problems(Stream)} reports both.
    */
   public Stream<Goal> goals() {
      return goals.values().stream().map(List::getFirst);
   }

   /**
    * @param id the bare goal ID, e.g. {@code 2026-Q4-G1}.
    * @return the goal; empty when none has the ID. Of two files with one ID, the first read.
    */
   public Optional<Goal> goal(String id) {
      return Optional.ofNullable(goals.get(id)).map(List::getFirst);
   }

   /**
    * @return every effort, in no particular order.
    */
   public Stream<Effort> efforts() {
      return efforts.values().stream();
   }

   /**
    * @param slug the effort's slug, e.g. {@code infinijepsen-demo}.
    * @return the effort; empty when none has the slug.
    */
   public Optional<Effort> effort(String slug) {
      return Optional.ofNullable(efforts.get(slug));
   }

   /**
    * Finds what breaks the rules of the plan.
    *
    * @param tasks the tasks to check against their efforts, done ones included; tasks without an effort are skipped.
    * @return the problems of every goal file, effort, misnamed goal note and task, in no particular order.
    */
   public Stream<PlanProblem> problems(Stream<Task> tasks) {
      return Stream.of(
                  goals.values().stream().flatMap(List::stream).flatMap(this::goalProblems),
                  efforts.values().stream().flatMap(this::effortProblems),
                  misnamed.stream().map(file -> new PlanProblem.InvalidGoalName(file, noteId(file))),
                  tasks.flatMap(task -> taskProblem(task).stream()))
            .flatMap(Function.identity());
   }

   /**
    * Finds the errors a change would add to the plan, before it is written.
    *
    * <p>
    * Only the errors the change adds count: warnings never stand in the way, and errors already in the plan do not
    * block an unrelated change.
    * </p>
    *
    * @param changed the goal or effort as it would be after the change, or a new one; as it is when only tasks change.
    * @param before the tasks the change can affect, as they are now; empty when it affects none.
    * @param after the same tasks as they would be after the change, a new one included.
    * @return the errors the change would add, in no particular order; empty when it keeps the rules.
    */
   public List<PlanProblem> added(PlanItem changed, List<Task> before, List<Task> after) {
      Set<String> found = problems(before.stream())
            .filter(PlanProblem::error)
            .map(PlanProblem::key)
            .collect(Collectors.toSet());
      return with(changed).problems(after.stream())
            .filter(PlanProblem::error)
            .filter(problem -> !found.contains(problem.key()))
            .toList();
   }

   /**
    * Refuses a change that would break the rules of the plan, naming each error as {@code check} reports it.
    *
    * @param added the errors the change would add, as {@link #added} finds them.
    * @throws ZtkException with {@link ZtkException.Kind#VALIDATION} when there is any, listing each with its fix.
    */
   public static void refuse(List<PlanProblem> added) {
      if (!added.isEmpty()) {
         // As check reports them: where each is, what is wrong, and how to fix it.
         throw ZtkException.validation("Not changed, as it would leave these errors; settle each first:"
               + added.stream()
                     .map(problem -> System.lineSeparator() + "  " + problem.id().map(id -> id + ": ").orElse("")
                           + problem.message() + " " + problem.hint())
                     .collect(Collectors.joining()));
      }
   }

   /**
    * @param changed a goal or effort as it would be after a change, or a new one.
    * @return this plan with it in place of the one with its path, or added to it.
    */
   private Plan with(PlanItem changed) {
      return switch (changed) {
         case Goal goal -> {
            List<Goal> files = goals.getOrDefault(goal.id(), List.of());
            // In place, so a goal sharing its ID with another file stays the one read first.
            List<Goal> replaced = files.stream().anyMatch(file -> file.path().equals(goal.path()))
                  ? files.stream().map(file -> file.path().equals(goal.path()) ? goal : file).toList()
                  : Stream.concat(files.stream(), Stream.of(goal)).toList();
            Map<String, List<Goal>> changedGoals = new HashMap<>(goals);
            changedGoals.put(goal.id(), replaced);
            yield new Plan(dataLocation, changedGoals, efforts, misnamed);
         }
         case Effort effort -> {
            Map<String, Effort> changedEfforts = new HashMap<>(efforts);
            changedEfforts.put(effort.slug(), effort);
            yield new Plan(dataLocation, goals, changedEfforts, misnamed);
         }
      };
   }

   /**
    * Tells what is wrong with a goal.
    *
    * @param goal a goal, one of its files when several share its ID.
    * @return its problems: its status, its ID shared with another file, the goal it points to, and its closing.
    */
   private Stream<PlanProblem> goalProblems(Goal goal) {
      Optional<PlanProblem> status = goal.status().isPresent()
            ? Optional.empty()
            : Optional.of(new PlanProblem.InvalidStatus(goal));
      List<Path> others = goals.get(goal.id()).stream()
            .map(Goal::path)
            .filter(path -> !path.equals(goal.path()))
            .toList();
      Optional<PlanProblem> duplicate = others.isEmpty()
            ? Optional.empty()
            : Optional.of(new PlanProblem.DuplicateGoal(goal, others));
      // Only a quarterly goal points to a goal: a yearly goal of its own year.
      Optional<PlanProblem> parent = goal.period() instanceof Period.OfQuarter quarter
            ? goal.parent()
                  .map(named -> above(goal, named, found -> found.period().equals(quarter.ofYear())))
                  .orElseGet(() -> Optional.of(new PlanProblem.MissingGoal(goal)))
            : Optional.empty();
      Optional<PlanProblem> needsClosing = goal.needsClosing()
            ? Optional.of(new PlanProblem.NeedsClosing(goal))
            : Optional.empty();
      Optional<PlanProblem> mismatch = goal.status()
            .filter(value -> value.closed() != goal.closed().isPresent())
            .map(value -> new PlanProblem.ClosedMismatch(goal, value.label(), goal.closed()));
      return Stream.of(status, duplicate, parent, needsClosing, mismatch).flatMap(Optional::stream);
   }

   /**
    * Tells what is wrong with an effort.
    *
    * @param effort an effort.
    * @return its problems: its status, the goal it points to, and its closing.
    */
   private Stream<PlanProblem> effortProblems(Effort effort) {
      Optional<PlanProblem> status = effort.status().isPresent()
            ? Optional.empty()
            : Optional.of(new PlanProblem.InvalidStatus(effort));
      Optional<PlanProblem> goal = effort.goal()
            .map(named -> above(effort, named, found -> found.period() instanceof Period.OfQuarter))
            .orElseGet(() -> Optional.of(new PlanProblem.MissingGoal(effort)));
      Optional<PlanProblem> mismatch = effort.status()
            .filter(value -> value.closed() != effort.closed().isPresent())
            .map(value -> new PlanProblem.ClosedMismatch(effort, value.label(), effort.closed()));
      return Stream.of(status, goal, mismatch).flatMap(Optional::stream);
   }

   /**
    * Tells whether the goal a quarterly goal or an effort points to is one it may point to, and may be under.
    *
    * @param item the quarterly goal or effort.
    * @param named the goal ID it points to, e.g. {@code 2026-Q4-G1}.
    * @param fits whether a goal is of the level and year it must point to.
    * @return the problem when no goal has the ID, the goal does not fit, or the item is open under it while it is
    *         closed; empty otherwise.
    */
   private Optional<PlanProblem> above(PlanItem item, String named, Predicate<Goal> fits) {
      Optional<Goal> found = goal(named);
      if (found.isEmpty()) {
         return Optional.of(new PlanProblem.UnknownGoal(item, named, false));
      }
      // Files sharing an ID share its level and year, read from it: the first tells.
      if (!fits.test(found.get())) {
         return Optional.of(new PlanProblem.UnknownGoal(item, named, true));
      }
      boolean closed = found.get().status().filter(GoalStatus::closed).isPresent();
      return item.open() && closed
            ? Optional.of(new PlanProblem.OpenUnderClosed(item, found.get()))
            : Optional.empty();
   }

   /**
    * Tells what is wrong with the effort a task names.
    *
    * @param task a task, done ones included.
    * @return the problem when no effort has the slug, or the task is still open in a closed effort; empty otherwise,
    *         and for a task naming no effort.
    */
   private Optional<PlanProblem> taskProblem(Task task) {
      Optional<String> slug = task.effort();
      if (slug.isEmpty()) {
         return Optional.empty();
      }
      Optional<Effort> effort = effort(slug.get());
      if (effort.isEmpty()) {
         return Optional.of(new PlanProblem.UnknownEffort(task));
      }
      // A completed task may join a closed effort: work done for it, recorded after the fact.
      boolean closed = effort.get().status().filter(EffortStatus::closed).isPresent();
      return task.open() && closed
            ? Optional.of(new PlanProblem.OpenTaskUnderClosed(task, effort.get()))
            : Optional.empty();
   }

   /**
    * @param file a note of the knowledge base.
    * @return its node ID as a plain note, e.g. {@code note:goals/2026/notes}.
    */
   private String noteId(Path file) {
      // As the knowledge base names its notes: the path relative to the data location, without .md.
      String relative = dataLocation.relativize(file).toString();
      return "note:" + relative.substring(0, relative.length() - MARKDOWN.length());
   }
}
