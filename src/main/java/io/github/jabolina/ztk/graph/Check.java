package io.github.jabolina.ztk.graph;

import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import io.github.jabolina.ztk.ZtkException;

/**
 * Finds what is broken in the graph of the knowledge base: links leading nowhere, and tasks and notes that lost each
 * other.
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
      return Stream.of(linkProblems(), missingNotes(), tasksWithoutProject(), orphanTaskNotes(), unreadableNotes())
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
