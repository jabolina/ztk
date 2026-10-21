package io.github.jabolina.ztk.graph;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import io.github.jabolina.ztk.ZtkException;
import io.github.jabolina.ztk.planning.Effort;
import io.github.jabolina.ztk.planning.EffortStatus;
import io.github.jabolina.ztk.planning.Goal;
import io.github.jabolina.ztk.planning.Period;

/**
 * Finds what is broken in the graph of the knowledge base: links leading nowhere, tasks and notes that lost each
 * other, and goals and efforts that break the rules of the plan.
 *
 * <p>
 * Only reports: users and agents fix the problems themselves.
 * </p>
 *
 * @since 1.0
 * @author José Bolina
 */
public final class Check {

   // Errors first, then by file and line, the order they are fixed in.
   // The rest only keeps the order the same on every run.
   // Problems in no file come after the rest.
   private static final Comparator<Problem> ORDER = Comparator.comparing(Problem::severity)
         .thenComparing(problem -> problem.path().orElse(null), Comparator.nullsLast(Comparator.naturalOrder()))
         .thenComparing(problem -> problem.line().orElse(0))
         .thenComparing(problem -> problem.id().orElse(""))
         .thenComparing(Problem::code);

   private final KnowledgeGraph graph;

   /**
    * @param graph the graph to check.
    */
   public Check(KnowledgeGraph graph) {
      this.graph = graph;
   }

   /**
    * Which problems to report.
    *
    * @param codes   The codes to report, e.g. {@code dangling-link}; empty for every code.
    * @param project The project whose notes and tasks to report on, e.g. {@code Alpha}; empty for the whole
    *                knowledge base.
    */
   public record Filter(Set<String> codes, Optional<String> project) { }

   /**
    * Lists the problems of the knowledge base.
    *
    * <p>
    * The whole knowledge base is checked whatever the filter, as links resolve across folders: the filter only
    * chooses what is reported.
    * </p>
    *
    * @param filter which problems to report.
    * @return the problems found, errors first, then by file and line.
    * @throws ZtkException with {@link ZtkException.Kind#NOT_FOUND} when the filter names a project no task has and
    *                      with no folder.
    */
   public Stream<Problem> problems(Filter filter) {
      Predicate<Problem> inProject = filter.project()
            .map(this::inProject)
            .orElse(ignore -> true);
      return Stream.of(linkProblems(), missingNotes(), tasksWithoutProject(), orphanTaskNotes(), unreadableNotes(),
                  planProblems())
            .flatMap(Function.identity())
            .filter(problem -> filter.codes().isEmpty() || filter.codes().contains(problem.code()))
            .filter(inProject)
            .sorted(ORDER);
   }

   /**
    * Tells the problems of a project apart.
    *
    * @param project a project name, e.g. {@code Alpha}.
    * @return whether a problem is about one of the project's tasks, or in a file of its folder.
    * @throws ZtkException with {@link ZtkException.Kind#NOT_FOUND} when no task has the project and it has no folder.
    */
   private Predicate<Problem> inProject(String project) {
      Node.OfProject found = graph.project(project)
            .orElseThrow(() -> ZtkException.notFound("No project " + project
                  + ". Find the projects with: task projects"));

      // A task's problem belongs to the task's project, wherever its note is.
      return problem -> problem instanceof Problem.InTask inTask
            ? inTask.task().task().project().equals(Optional.of(found.name()))
            : problem.path().flatMap(path -> found.folder().map(path::startsWith)).orElse(false);
   }

   /**
    * @return the wikilinks leading nowhere, or to several notes.
    */
   private Stream<Problem> linkProblems() {
      return graph.links().flatMap(link -> linkProblem(link).stream());
   }

   /**
    * @return the open tasks whose note attribute leads to no note.
    */
   private Stream<Problem> missingNotes() {
      return graph.tasksWithMissingNote()
            // Done tasks are history: only what is still to do must be right.
            .filter(task -> task.task().open())
            .map(Problem.MissingNote::new);
   }

   /**
    * @return the open tasks without a project.
    */
   private Stream<Problem> tasksWithoutProject() {
      return graph.tasks()
            .filter(task -> task.task().open())
            .filter(task -> task.task().project().isEmpty())
            .map(Problem.TaskWithoutProject::new);
   }

   /**
    * @return the task notes whose task is gone.
    */
   private Stream<Problem> orphanTaskNotes() {
      return graph.orphanTaskNotes().map(Problem.OrphanTaskNote::new);
   }

   /**
    * @return the notes that could not be read.
    */
   private Stream<Problem> unreadableNotes() {
      return graph.skipped().map(Problem.UnreadableNote::new);
   }

   /**
    * @return what is wrong with the goals and efforts, as read from their notes.
    */
   private Stream<Problem> planProblems() {
      return Stream.of(
                  graph.goals().flatMap(this::goalProblems),
                  graph.efforts().flatMap(this::effortProblems),
                  graph.misnamed().map(Problem.InvalidGoalName::new),
                  graph.tasks().flatMap(task -> effortProblem(task).stream()))
            .flatMap(Function.identity());
   }

   /**
    * Tells what is wrong with a goal.
    *
    * @param item a goal, one of its files when several share its ID.
    * @return its problems: its status, its ID shared with another file, and the goal it points to.
    */
   private Stream<Problem> goalProblems(Node.OfGoal item) {
      Goal goal = item.goal();
      Optional<Problem> status = goal.status().isPresent()
            ? Optional.empty()
            : Optional.of(new Problem.InvalidStatus(item));
      List<Path> others = graph.goals(goal.id()).stream()
            .map(other -> other.goal().path())
            .filter(path -> !path.equals(goal.path()))
            .toList();
      Optional<Problem> duplicate = others.isEmpty()
            ? Optional.empty()
            : Optional.of(new Problem.DuplicateGoal(item, others));
      // Only a quarterly goal points to a goal: a yearly goal of its own year.
      Optional<Problem> parent = goal.period() instanceof Period.OfQuarter quarter
            ? goal.parent()
                  .map(named -> unknownGoal(item, named, found -> found.period().equals(quarter.ofYear())))
                  .orElseGet(() -> Optional.of(new Problem.MissingGoal(item)))
            : Optional.empty();
      Optional<Problem> needsClosing = goal.needsClosing()
            ? Optional.of(new Problem.NeedsClosing(item))
            : Optional.empty();
      Optional<Problem> mismatch = goal.status()
            .filter(value -> value.closed() != goal.closed().isPresent())
            .map(value -> new Problem.ClosedMismatch(item, value.label(), goal.closed()));
      return Stream.of(status, duplicate, parent, needsClosing, mismatch).flatMap(Optional::stream);
   }

   /**
    * Tells what is wrong with an effort.
    *
    * @param item an effort.
    * @return its problems: its status, and the goal it points to.
    */
   private Stream<Problem> effortProblems(Node.OfEffort item) {
      Effort effort = item.effort();
      Optional<Problem> status = effort.status().isPresent()
            ? Optional.empty()
            : Optional.of(new Problem.InvalidStatus(item));
      Optional<Problem> goal = effort.goal()
            .map(named -> unknownGoal(item, named, found -> found.period() instanceof Period.OfQuarter))
            .orElseGet(() -> Optional.of(new Problem.MissingGoal(item)));
      Optional<Problem> mismatch = effort.status()
            .filter(value -> value.closed() != effort.closed().isPresent())
            .map(value -> new Problem.ClosedMismatch(item, value.label(), effort.closed()));
      return Stream.of(status, goal, mismatch).flatMap(Optional::stream);
   }

   /**
    * Tells what is wrong with the effort a task names.
    *
    * @param task a task, done ones included.
    * @return the problem when no effort has the slug, or the task is still open in a closed effort; empty otherwise,
    *         and for a task naming no effort.
    */
   private Optional<Problem> effortProblem(Node.OfTask task) {
      Optional<String> slug = task.task().effort();
      if (slug.isEmpty()) {
         return Optional.empty();
      }
      Optional<Node.OfEffort> effort = graph.effort(slug.get());
      if (effort.isEmpty()) {
         return Optional.of(new Problem.UnknownEffort(task));
      }
      boolean closed = effort.get().effort().status().map(EffortStatus::closed).orElse(false);
      return task.task().open() && closed
            ? Optional.of(new Problem.OpenTaskInClosedEffort(task, effort.get()))
            : Optional.empty();
   }

   /**
    * Tells whether the goal a quarterly goal or an effort points to is one it may point to.
    *
    * @param item the quarterly goal or effort.
    * @param named the goal ID it points to, e.g. {@code 2026-Q4-G1}.
    * @param fits whether a goal is of the level and year it must point to.
    * @return the problem when no goal has the ID, or the goal does not fit; empty otherwise.
    */
   private Optional<Problem> unknownGoal(Node.Planned item, String named, Predicate<Goal> fits) {
      List<Node.OfGoal> found = graph.goals(named);
      if (found.isEmpty()) {
         return Optional.of(new Problem.UnknownGoal(item, named, false));
      }
      // Files sharing an ID share its level and year, read from it: any of them tells.
      return fits.test(found.getFirst().goal())
            ? Optional.empty()
            : Optional.of(new Problem.UnknownGoal(item, named, true));
   }

   /**
    * Tells what is wrong with a wikilink.
    *
    * @param link a wikilink and where it leads.
    * @return its problem; empty when it leads to one note or task.
    */
   private static Optional<Problem> linkProblem(KnowledgeGraph.Link link) {
      // A dangling link resolves to its own target, alone.
      if (link.to().getFirst() instanceof Node.OfDangling)
         return Optional.of(new Problem.DanglingLink(link.from(), link.wikilink()));

      // Only notes are ever found several at a time, so several nodes are several notes of that name.
      if (link.to().size() > 1)
         return Optional.of(new Problem.AmbiguousLink(link.from(), link.wikilink(), link.to()));

      return Optional.empty();
   }
}
