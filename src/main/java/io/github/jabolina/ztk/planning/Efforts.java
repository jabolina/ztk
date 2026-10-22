package io.github.jabolina.ztk.planning;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.github.jabolina.ztk.ZtkException;
import io.github.jabolina.ztk.configuration.Configuration;
import io.github.jabolina.ztk.notes.KnowledgeBase;
import io.github.jabolina.ztk.notes.NoteFiles;
import io.github.jabolina.ztk.tasks.TaskNote;
import io.github.jabolina.ztk.tasks.Tasks;
import io.github.jabolina.ztk.taskwarrior.Status;
import io.github.jabolina.ztk.taskwarrior.Task;
import io.github.jabolina.ztk.taskwarrior.TaskStore;
import io.github.jabolina.ztk.util.Frontmatter;
import io.github.jabolina.ztk.util.JsonWritable;
import io.github.jabolina.ztk.util.Slugs;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The efforts of the knowledge base: groups of tasks that together move one quarterly goal forward.
 *
 * <p>
 * An effort is a note directly in the efforts folder, named after its slug, e.g. {@code efforts/infinijepsen-demo.md}.
 * The slug is its ID and never changes; no other note has it as its name, so a wikilink to an effort always leads to
 * it.
 * </p>
 *
 * @since 1.0
 * @author José Bolina
 */
public final class Efforts {

   // By the quarter of their goal, then goal; goal IDs of one quarter differ only in their number, so the shorter is
   // the smaller: G2 before G10. Efforts whose goal names no quarter come last.
   private static final Comparator<Effort> ORDER = Comparator
         .comparing((Effort effort) -> effort.quarter().map(Period.OfQuarter::toString).orElse(null),
               Comparator.nullsLast(Comparator.naturalOrder()))
         .thenComparing(effort -> effort.goal().map(String::length).orElse(0))
         .thenComparing(effort -> effort.goal().orElse(""))
         .thenComparing(Effort::slug);

   // The working document agents read most, each section with guidance in italics for whoever writes it.
   private static final String TEMPLATE = """
         ## Context
         _Why this effort exists, and what its quarterly goal needs from it._

         ## Done when
         _A checklist, as for quarterly goals._

         ## Approach
         _How the work gets there; rewritten when the plan changes, with the reason in Decisions._

         ## Decisions
         _A dated log: "2026-11-02: chose X over Y, because …". Each session's agent reads it before changing course._

         ## Risks and open questions
         _What could block it, and what is still unclear._

         ## Outcome
         _When closed: what was delivered, its impact, links to the evidence. This feeds the quarterly retrospective._
         """;

   // A task not created yet has no UUID; it never shows, as only the effort it would join is named.
   private static final String NEW_TASK = "new-task";

   /**
    * What is known about an effort when creating it.
    *
    * @param slug  The effort's slug, its file name and ID, e.g. {@code infinijepsen-demo}.
    * @param title The effort's title.
    * @param goal  The quarterly goal it moves forward, bare or as a node ID, e.g. {@code 2026-Q4-G1}.
    */
   public record Details(String slug, String title, String goal) { }

   /**
    * What changes in an effort; anything empty stays as it is.
    *
    * @param status The new status.
    * @param title  The new title.
    * @param goal   The new quarterly goal, bare or as a node ID; one of a later quarter carries the effort over.
    */
   public record Changes(Optional<EffortStatus> status, Optional<String> title, Optional<String> goal) { }

   /**
    * Which efforts to list; anything empty does not narrow the listing.
    *
    * <p>
    * With no period, statuses or goal, only the open efforts are listed: planned or active, in any quarter. A period
    * or a goal lists its efforts in any status.
    * </p>
    *
    * @param period   The year or quarter whose goals' efforts to list.
    * @param statuses The statuses to list.
    * @param goal     The quarterly goal whose efforts to list, bare or as a node ID.
    */
   public record Selection(Optional<Period> period, Set<EffortStatus> statuses, Optional<String> goal) { }

   /**
    * An effort in a listing, with its tasks counted.
    *
    * @param effort The effort.
    * @param tasks  Its tasks, by status.
    */
   public record Listed(Effort effort, TaskCounts tasks) implements JsonWritable {

      /**
       * Writes the effort's fields, then its task counts in a {@code tasks} object.
       *
       * @param target the JSON object to write into.
       * @return {@code target}, for chaining.
       */
      @Override
      public ObjectNode writeJson(ObjectNode target) {
         effort.writeJson(target);
         tasks.writeJson(target.putObject("tasks"));
         return target;
      }
   }

   /**
    * The efforts listed, by the quarter of their goal, then goal, then slug.
    *
    * @param efforts The efforts, in that order.
    */
   public record Listing(List<Listed> efforts) implements JsonWritable {

      /**
       * Writes the efforts in an {@code efforts} array.
       *
       * @param target the JSON object to write into.
       * @return {@code target}, for chaining.
       */
      @Override
      public ObjectNode writeJson(ObjectNode target) {
         ArrayNode array = target.putArray("efforts");
         efforts.forEach(listed -> listed.writeJson(array.addObject()));
         return target;
      }
   }

   /**
    * The tasks of one effort, by status.
    *
    * @param pending   The tasks still to do, waiting ones included.
    * @param completed The tasks done.
    */
   public record TasksByStatus(List<Task> pending, List<Task> completed) { }

   /**
    * One effort with the goals it moves forward and its tasks.
    *
    * @param effort The effort.
    * @param goal   Its quarterly goal; empty when the effort names none, or one that does not exist.
    * @param parent The yearly goal its quarterly goal moves forward; empty when there is none.
    * @param tasks  Its tasks, by status.
    */
   public record Info(Effort effort, Optional<Goal> goal, Optional<Goal> parent, TasksByStatus tasks)
         implements JsonWritable {

      /**
       * Writes the effort's fields, its {@code goal} holding the goal chain: the quarterly goal's fields, its
       * {@code parent} holding the yearly goal's. A goal that does not exist is {@code null}. The tasks are not written:
       * each needs its note's path, which only the caller knows how to show.
       *
       * @param target the JSON object to write into.
       * @return {@code target}, for chaining.
       */
      @Override
      public ObjectNode writeJson(ObjectNode target) {
         effort.writeJson(target);
         // Replacing the goal's ID keeps its place among the fields.
         goal.ifPresentOrElse(quarterly -> {
            ObjectNode chain = quarterly.writeJson(target.putObject("goal"));
            parent.ifPresentOrElse(yearly -> yearly.writeJson(chain.putObject("parent")), () -> chain.putNull("parent"));
         }, () -> target.putNull("goal"));
         return target;
      }
   }

   private final Configuration configuration;
   private final Clock clock;
   private final GoalNotes goals;
   private final EffortNotes notes;
   private final KnowledgeBase knowledgeBase;
   private final Supplier<TaskStore> store;
   private final Supplier<Tasks> tasks;
   private final EffortTasks effortTasks;

   /**
    * @param configuration the configuration giving the efforts and goals folders.
    * @param clock the clock for the {@code date} of new efforts and {@code closed}.
    * @param knowledgeBase the notes whose names an effort's slug must not take.
    * @param store the tasks joining and leaving efforts; only reached by the commands changing tasks, so the others
    *              never start Taskwarrior.
    * @param tasks the task use cases, adding a task straight into an effort.
    */
   public Efforts(Configuration configuration, Clock clock, KnowledgeBase knowledgeBase, Supplier<TaskStore> store,
                  Supplier<Tasks> tasks) {
      this.configuration = configuration;
      this.clock = clock;
      this.goals = new GoalNotes(configuration, clock);
      this.notes = new EffortNotes(configuration, clock);
      this.knowledgeBase = knowledgeBase;
      this.store = store;
      this.tasks = tasks;
      this.effortTasks = new EffortTasks(store);
   }

   /**
    * Creates an effort, planned, from its template.
    *
    * @param details what is known about the effort.
    * @return the new effort's node ID and path.
    * @throws ZtkException with {@link ZtkException.Kind#USAGE} when the slug is not lowercase letters, digits and
    *                      dashes. Nothing is written.
    * @throws ZtkException with {@link ZtkException.Kind#VALIDATION} when a note already has the slug as its name, or
    *                      the goal is not a quarterly goal, or is closed. Nothing is written.
    * @throws UncheckedIOException when the knowledge base cannot be read or the note cannot be written.
    */
   public Saved create(Details details) {
      String slug = checked(details.slug());
      unclaimed(slug);
      String goal = bare(details.goal());
      Path file = configuration.folders().efforts().resolve(slug + ".md");
      Effort created = new Effort(slug, details.title(), Optional.of(EffortStatus.PLANNED), Optional.empty(),
            Optional.of(goal), Optional.empty(), false, file);
      refuseCreating(Plan.read(configuration, clock).added(created, List.of(), List.of()));

      SequencedMap<String, String> frontmatter = new LinkedHashMap<>();
      frontmatter.put("title", details.title());
      frontmatter.put("status", EffortStatus.PLANNED.label());
      frontmatter.put("goal", goal);
      frontmatter.put("date", Frontmatter.timestamp(OffsetDateTime.now(clock)));
      NoteFiles.write(file, frontmatter, TEMPLATE);
      return new Saved(Effort.PREFIX + slug, file);
   }

   /**
    * Changes an effort: its status, title or goal.
    *
    * <p>
    * Only the frontmatter changes. A status moving to a closed one writes {@code closed} as today, and one moving back
    * to an open one removes it. The file keeps its name, so the slug never changes; pointing the effort at a goal of a
    * later quarter carries it over.
    * </p>
    *
    * @param slug the effort's slug, bare or as a node ID.
    * @param changes what changes; anything empty stays as it is.
    * @return the effort's node ID and path.
    * @throws ZtkException with {@link ZtkException.Kind#NOT_FOUND} when no effort has the slug.
    * @throws ZtkException with {@link ZtkException.Kind#VALIDATION} when the goal is not a quarterly goal, or is
    *                      closed, or closing the effort would leave pending tasks in it. Nothing is written.
    * @throws UncheckedIOException when the effort or goals cannot be read, or the note cannot be written.
    */
   public Saved update(String slug, Changes changes) {
      Effort effort = notes.find(slug).orElseThrow(() -> ZtkException.notFound(unknown(slug)));
      Optional<String> goal = changes.goal().map(Efforts::bare);
      Effort after = new Effort(effort.slug(), changes.title().orElse(effort.title()),
            changes.status().or(effort::status), effort.closed(), goal.or(effort::goal), effort.quarter(),
            effort.carriedOver(), effort.path());
      // Only a new status can leave the effort's tasks under it closed.
      List<Task> affected = changes.status().isPresent()
            ? store.get().query(TaskStore.EFFORT_UDA + ".is:" + effort.slug())
            : List.of();
      // Checked before writing: a refused change leaves the note as it was. Its closed date, quarter and carrying
      // over only matter to warnings and listings.
      Plan.refuse(Plan.read(configuration, clock).added(after, affected, affected));

      Frontmatter.update(effort.path(), fields -> {
         changes.title().ifPresent(value -> fields.put("title", value));
         goal.ifPresent(value -> fields.put("goal", value));
         changes.status().ifPresent(value -> value.write(fields, effort.status(), LocalDate.now(clock)));
      });
      return new Saved(Effort.PREFIX + effort.slug(), effort.path());
   }

   /**
    * Lists efforts with their tasks counted.
    *
    * @param selection which efforts to list.
    * @return the efforts, by the quarter of their goal, then goal, then slug.
    * @throws UncheckedIOException when the efforts cannot be read.
    * @throws ZtkException with {@link ZtkException.Kind#TASKWARRIOR} when Taskwarrior fails.
    */
   public Listing list(Selection selection) {
      // Open work by default: what is planned or under way, wherever it started. Naming a period or a goal asks for
      // all of its efforts, closed ones included.
      boolean openOnly = selection.period().isEmpty() && selection.statuses().isEmpty() && selection.goal().isEmpty();
      Predicate<Effort> status = openOnly
            ? effort -> effort.status().filter(value -> !value.closed()).isPresent()
            : effort -> selection.statuses().isEmpty() || effort.status().filter(selection.statuses()::contains).isPresent();
      Predicate<Effort> period = effort -> selection.period()
            .map(value -> effort.quarter().filter(value::contains).isPresent())
            .orElse(true);
      Optional<String> goal = selection.goal().map(value -> value.startsWith(Goal.PREFIX) ? value.substring(Goal.PREFIX.length()) : value);
      Predicate<Effort> ofGoal = effort -> goal.isEmpty() || effort.goal().equals(goal);

      // One export for every effort's tasks, before the efforts are read.
      Map<String, TaskCounts> counts = effortTasks.counts();
      try (Stream<Effort> efforts = notes.all()) {
         return new Listing(efforts.filter(status.and(period).and(ofGoal))
               .sorted(ORDER)
               .map(effort -> new Listed(effort, counts.getOrDefault(effort.slug(), TaskCounts.NONE)))
               .toList());
      }
   }

   /**
    * Shows one effort with the goals it moves forward and its tasks.
    *
    * @param slug the effort's slug, bare or as a node ID.
    * @return the effort, its goal chain and its tasks by status.
    * @throws ZtkException with {@link ZtkException.Kind#NOT_FOUND} when no effort has the slug.
    * @throws ZtkException with {@link ZtkException.Kind#TASKWARRIOR} when Taskwarrior fails.
    * @throws UncheckedIOException when the effort or its goals cannot be read.
    */
   public Info info(String slug) {
      Effort effort = notes.find(slug).orElseThrow(() -> ZtkException.notFound(unknown(slug)));
      Optional<Goal> goal = effort.goal().flatMap(goals::find);
      Optional<Goal> parent = goal.flatMap(Goal::parent).flatMap(goals::find);
      return new Info(effort, goal, parent, effortTasks.of(effort.slug()));
   }

   /**
    * Adds a task straight into an effort, creating its note as {@code ztk add} does.
    *
    * @param slug the effort's slug, bare or as a node ID.
    * @param words the description words and Taskwarrior attributes, e.g. {@code "fix", "bug", "project:Alpha"}.
    * @return the created task, in the effort and linked to its new note.
    * @throws ZtkException with {@link ZtkException.Kind#VALIDATION} when no effort has the slug, the effort is closed,
    *                      or the words assign no project. Nothing is written.
    */
   public TaskNote add(String slug, List<String> words) {
      Effort effort = notes.find(slug).orElseThrow(() -> ZtkException.validation(unknown(slug)));
      // The task as it would be: pending, in the effort. It has no number or UUID yet, and the refusal names neither.
      Task planned = new Task(0, NEW_TASK, String.join(" ", words), Status.PENDING, Optional.empty(), List.of(),
            Optional.empty(), Optional.empty(), Optional.of(effort.slug()), clock.instant(), clock.instant(),
            Optional.empty(), Optional.empty());
      refuseCreating(Plan.read(configuration, clock).added(effort, List.of(), List.of(planned)));
      List<String> joined = new ArrayList<>(words);
      joined.add(TaskStore.EFFORT_UDA + ":" + effort.slug());
      return tasks.get().add(joined);
   }

   /**
    * Joins tasks to an effort, taking them from any effort they were in.
    *
    * @param slug the effort's slug, bare or as a node ID.
    * @param references the task IDs or UUIDs.
    * @return the tasks, as stored by Taskwarrior after joining.
    * @throws ZtkException with {@link ZtkException.Kind#NOT_FOUND} when no effort has the slug, or no task has one of
    *                      the references.
    * @throws ZtkException with {@link ZtkException.Kind#VALIDATION} when the effort is closed and one of the tasks is
    *                      still open. No task changes.
    */
   public List<Task> assign(String slug, List<String> references) {
      Effort effort = notes.find(slug).orElseThrow(() -> ZtkException.notFound(unknown(slug)));
      TaskStore taskStore = store.get();
      // Every task is read and checked before the first one changes: all join, or none does.
      List<Task> found = references.stream().map(taskStore::get).toList();
      List<Task> joined = found.stream().map(task -> task.withEffort(effort.slug())).toList();
      Plan.refuse(Plan.read(configuration, clock).added(effort, found, joined));
      return change(found, TaskStore.EFFORT_UDA + ":" + effort.slug());
   }

   /**
    * Takes tasks out of their effort, making them general work.
    *
    * @param references the task IDs or UUIDs.
    * @return the tasks, as stored by Taskwarrior after leaving.
    * @throws ZtkException with {@link ZtkException.Kind#NOT_FOUND} when no task has one of the references. No task
    *                      changes.
    */
   public List<Task> unassign(List<String> references) {
      TaskStore taskStore = store.get();
      // An empty value clears the attribute.
      return change(references.stream().map(taskStore::get).toList(), TaskStore.EFFORT_UDA + ":");
   }

   /**
    * Applies one modification to each task.
    *
    * @param found the tasks, as read before changing.
    * @param modification the Taskwarrior modification, e.g. {@code effort:infinijepsen-demo}.
    * @return the tasks, re-read after the change.
    */
   private List<Task> change(List<Task> found, String modification) {
      TaskStore taskStore = store.get();
      List<Task> changed = new ArrayList<>();
      for (Task task : found) {
         taskStore.modify(task, List.of(modification));
         // Re-read: the modification changed the task.
         changed.add(taskStore.get(task.uuid()));
      }
      return changed;
   }

   /**
    * Refuses to create a new effort, or a new task in an effort, that would break the rules of the plan.
    *
    * @param added the errors it would add.
    * @throws ZtkException with {@link ZtkException.Kind#VALIDATION} when there is any, naming what to give instead.
    */
   private static void refuseCreating(List<PlanProblem> added) {
      // The plan's own hints change what exists; what is not created yet is given another goal or effort instead.
      if (!added.isEmpty()) {
         throw ZtkException.validation(added.stream().map(problem -> switch (problem) {
            case PlanProblem.UnknownGoal unknown -> "No quarterly goal " + unknown.named() + ". An effort moves one "
                  + "quarterly goal forward, e.g. 2026-Q4-G1; list the goals with: ztk goal list";
            case PlanProblem.OpenUnderClosed closed -> "The quarterly goal " + closed.parent().id() + " is "
                  + closed.parent().status().orElseThrow().label() + ", so it takes no new efforts. Reopen it first "
                  + "with: ztk goal set " + closed.parent().id() + " --status " + GoalStatus.OPEN.label();
            case PlanProblem.OpenTaskUnderClosed closed -> "The effort " + closed.effort().slug() + " is "
                  + closed.effort().status().orElseThrow().label() + ", so no pending task joins it. Reopen it first "
                  + "with: ztk effort set " + closed.effort().slug() + " --status " + EffortStatus.ACTIVE.label();
            default -> problem.message() + " " + problem.hint();
         }).collect(Collectors.joining(" ")));
      }
   }

   /**
    * @param slug the effort's slug as given.
    * @return what to say about an effort that does not exist, pointing to where efforts are listed.
    */
   private static String unknown(String slug) {
      return "No effort " + slug + ". List them with: ztk effort list";
   }

   /**
    * @param goal a goal ID, bare or as a node ID, e.g. {@code 2026-Q4-G1} or {@code goal:2026-Q4-G1}.
    * @return the bare goal ID, as an effort's {@code goal} holds it.
    */
   private static String bare(String goal) {
      // Accepted as JSON results print it, so an agent can feed it straight back.
      return goal.startsWith(Goal.PREFIX) ? goal.substring(Goal.PREFIX.length()) : goal;
   }

   /**
    * Ensures no note has the slug as its name, so wikilinks to the effort are never ambiguous.
    *
    * @param slug the effort's slug.
    * @throws ZtkException with {@link ZtkException.Kind#VALIDATION} when a note, an effort included, has that name.
    * @throws UncheckedIOException when the knowledge base cannot be read.
    */
   private void unclaimed(String slug) {
      // Names are relative paths without .md; a wikilink matches the last part only.
      knowledgeBase.names().stream()
            .filter(name -> name.substring(name.lastIndexOf('/') + 1).equals(slug))
            .findFirst()
            .ifPresent(name -> {
               throw ZtkException.validation("The note " + name + " already has the name " + slug
                     + ", so wikilinks to the effort would be ambiguous. Choose another slug.");
            });
   }

   /**
    * Checks a slug given by the user.
    *
    * @param slug the slug, as given.
    * @return {@code slug}, unchanged.
    * @throws ZtkException with {@link ZtkException.Kind#USAGE} when it is not lowercase letters, digits and dashes.
    */
   private static String checked(String slug) {
      if (!Slugs.valid(slug)) {
         throw ZtkException.usage("The slug '" + slug + "' must be lowercase letters and digits joined by single "
               + "dashes, e.g. " + Slugs.of(slug) + ".");
      }
      return slug;
   }
}
