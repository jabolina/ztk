package io.github.jabolina.ztk.planning;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.github.jabolina.ztk.ZtkException;
import io.github.jabolina.ztk.configuration.Configuration;
import io.github.jabolina.ztk.notes.NoteFiles;
import io.github.jabolina.ztk.taskwarrior.TaskStore;
import io.github.jabolina.ztk.util.Frontmatter;
import io.github.jabolina.ztk.util.JsonWritable;
import io.github.jabolina.ztk.util.Slugs;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The goals of the knowledge base: yearly goals, each an outcome for one calendar year, and quarterly goals, each an
 * achievable step toward one yearly goal.
 *
 * <p>
 * A goal is a note in the goals folder, filed by year and quarter, e.g. {@code goals/2026/Q4/2026-Q4-G1-fix-bugs.md}.
 * Its ID is the start of its file name, so the file is the only place it lives. Goal numbers are never reused: a new
 * goal takes the highest number of its year or quarter plus one.
 * </p>
 *
 * @since 1.0
 * @author José Bolina
 */
public final class Goals {

   // Longer slugs derived from a title are cut, so file names stay readable in listings and completion.
   private static final int MAX_SLUG = 60;

   // Yearly goals first, then quarterly goals by quarter; within one period IDs differ only in their number, so the
   // shorter is the smaller: G2 before G10.
   private static final Comparator<Goal> ORDER = Comparator.comparing((Goal goal) -> goal.period().toString())
         .thenComparing(goal -> goal.id().length())
         .thenComparing(Goal::id);

   // Sections for a big, open-ended goal agreed with a manager, each with guidance in italics for whoever writes it.
   private static final String YEARLY_TEMPLATE = """
         ## Summary
         _One paragraph a newcomer understands: what this goal is about._

         ## Why it matters
         _For the team, the product and its users; and for you: the growth or visibility it brings._

         ## Agreement
         _Who agreed (your manager), when, and what exactly was agreed, in their words where possible._

         ## What success looks like
         _Signals that the goal was met, not hard metrics: it is open-ended on purpose._

         ## Out of scope
         _What this goal deliberately leaves out, so nobody expects it later._

         ## Open questions
         _What is still unclear, and who can answer it._

         ## Retrospective
         _At the end of the year: the outcome, the evidence (efforts, summaries, links), what you learned, what you would do differently._
         """;

   // Sections for a smaller, achievable step toward a yearly goal, checkable at the end of the quarter.
   private static final String QUARTERLY_TEMPLATE = """
         ## Summary
         _One paragraph a newcomer understands: what this goal delivers this quarter._

         ## Contribution
         _How reaching this moves the yearly goal forward._

         ## Done when
         _A checklist of concrete, verifiable criteria; reached means every item holds._

         ## Out of scope
         _What this goal deliberately leaves out, so nobody expects it this quarter._

         ## Risks and dependencies
         _What could block it, and whom it depends on._

         ## Retrospective
         _At the end of the quarter: each criterion met or not, the evidence, what you learned._
         """;

   /**
    * What is known about a goal when creating it.
    *
    * @param period The year of a yearly goal, or the quarter of a quarterly goal.
    * @param title  The goal's title.
    * @param parent The yearly goal a quarterly goal moves forward, e.g. {@code 2026-G1}; empty for a yearly goal.
    * @param slug   The end of the file name; derived from the title when empty.
    */
   public record Details(Period period, String title, Optional<String> parent, Optional<String> slug) { }

   /**
    * What changes in a goal; anything empty stays as it is.
    *
    * @param status The new status.
    * @param title  The new title.
    * @param parent The new yearly goal of a quarterly goal.
    */
   public record Changes(Optional<GoalStatus> status, Optional<String> title, Optional<String> parent) { }

   /**
    * A goal in a listing, with the efforts under it counted.
    *
    * @param goal    The goal.
    * @param efforts The efforts under it, in any status: a quarterly goal's own, or those of a yearly goal's quarterly
    *                goals listed with it.
    */
   public record Listed(Goal goal, long efforts) implements JsonWritable {

      /**
       * Writes the goal's fields, then its {@code efforts} count.
       *
       * @param target the JSON object to write into.
       * @return {@code target}, for chaining.
       */
      @Override
      public ObjectNode writeJson(ObjectNode target) {
         goal.writeJson(target);
         target.put("efforts", efforts);
         return target;
      }
   }

   /**
    * The goals of a period, each yearly goal followed by its quarterly goals.
    *
    * @param period The year or quarter listed.
    * @param goals  The goals, in that order.
    */
   public record Listing(Period period, List<Listed> goals) implements JsonWritable {

      /**
       * Writes the goals, flat, in a {@code goals} array.
       *
       * @param target the JSON object to write into.
       * @return {@code target}, for chaining.
       */
      @Override
      public ObjectNode writeJson(ObjectNode target) {
         ArrayNode array = target.putArray("goals");
         goals.forEach(listed -> listed.writeJson(array.addObject()));
         return target;
      }
   }

   /**
    * One goal and what moves it forward: its efforts, and a yearly goal's quarterly goals with theirs.
    *
    * @param goal    The goal.
    * @param efforts Its efforts by slug, with their tasks counted; empty for a yearly goal, as efforts name quarterly
    *                goals.
    * @param goals   A yearly goal's quarterly goals, by quarter; empty for a quarterly goal.
    */
   public record Info(Goal goal, List<Efforts.Listed> efforts, List<Info> goals) implements JsonWritable {

      /**
       * @return this goal, then its quarterly goals.
       */
      public Stream<Info> nodes() {
         return Stream.concat(Stream.of(this), goals.stream());
      }

      /**
       * @return the goal as {@code goal list} shows it, counting its efforts and those of its quarterly goals.
       */
      public Listed listed() {
         return new Listed(goal, efforts.size() + goals.stream().mapToLong(child -> child.efforts().size()).sum());
      }

      /**
       * Writes the goal's fields, then its {@code efforts} array, then its quarterly goals in a {@code goals} array.
       *
       * @param target the JSON object to write into.
       * @return {@code target}, for chaining.
       */
      @Override
      public ObjectNode writeJson(ObjectNode target) {
         goal.writeJson(target);
         ArrayNode array = target.putArray("efforts");
         efforts.forEach(listed -> listed.writeJson(array.addObject()));
         ArrayNode children = target.putArray("goals");
         goals.forEach(child -> child.writeJson(children.addObject()));
         return target;
      }
   }

   private final Configuration configuration;
   private final Clock clock;
   private final GoalNotes notes;
   private final EffortNotes efforts;
   private final EffortTasks effortTasks;

   /**
    * @param configuration the configuration giving the goals and efforts folders.
    * @param clock the clock for the {@code date} of new goals, {@code closed}, and which goals need closing.
    * @param store the tasks of efforts, counted in {@code goal info}; only reached there, so the other goal commands
    *              never start Taskwarrior.
    */
   public Goals(Configuration configuration, Clock clock, Supplier<TaskStore> store) {
      this.configuration = configuration;
      this.clock = clock;
      this.notes = new GoalNotes(configuration, clock);
      this.efforts = new EffortNotes(configuration, clock);
      this.effortTasks = new EffortTasks(store);
   }

   /**
    * Creates a goal, open, from the template of its level.
    *
    * @param details what is known about the goal.
    * @return the new goal's node ID and path.
    * @throws ZtkException with {@link ZtkException.Kind#USAGE} when the slug is not lowercase letters, digits and
    *                      dashes, none is given and the title has no letters or digits, a quarterly goal has no
    *                      parent, or a yearly goal has one. Nothing is written.
    * @throws ZtkException with {@link ZtkException.Kind#VALIDATION} when the parent is not a yearly goal of the same
    *                      year, or is closed. Nothing is written.
    * @throws UncheckedIOException when the goals cannot be read or the note cannot be written.
    */
   public Saved create(Details details) {
      Period period = details.period();
      String name = details.slug().map(Goals::checked).orElseGet(() -> derived(details.title()));
      details.parent().ifPresent(given -> requireQuarter(period));
      if (period instanceof Period.OfQuarter quarter && details.parent().isEmpty()) {
         throw ZtkException.usage("A quarterly goal moves a yearly goal forward: name it with --parent. " + choices(quarter.ofYear()));
      }
      String prefix = period + "-G";
      String id = prefix + next(period, prefix);
      Path file = period.folder(configuration.folders().goals()).resolve(id + "-" + name + ".md");
      Goal created = new Goal(id, period, details.title(), Optional.of(GoalStatus.OPEN), Optional.empty(),
            details.parent(), file, false);
      // Only a quarterly goal's parent can break a rule; the refusal names the goals it could have instead.
      List<PlanProblem> added = Plan.read(configuration, clock).added(created, List.of(), List.of());
      if (!added.isEmpty()) {
         throw ZtkException.validation("Not created: " + added.stream()
               .map(problem -> refusal(problem, period.ofYear()))
               .collect(Collectors.joining(" ")));
      }

      SequencedMap<String, String> frontmatter = new LinkedHashMap<>();
      frontmatter.put("title", details.title());
      frontmatter.put("status", GoalStatus.OPEN.label());
      details.parent().ifPresent(value -> frontmatter.put("parent", value));
      frontmatter.put("date", Frontmatter.timestamp(OffsetDateTime.now(clock)));
      String template = switch (period) {
         case Period.OfYear _ -> YEARLY_TEMPLATE;
         case Period.OfQuarter _ -> QUARTERLY_TEMPLATE;
      };
      NoteFiles.write(file, frontmatter, template);
      return new Saved(Goal.PREFIX + id, file);
   }

   /**
    * Changes a goal: its status, title or parent.
    *
    * <p>
    * Only the frontmatter changes. A status moving to a closed one writes {@code closed} as today, and one moving back
    * to {@code open} removes it. The file keeps its name, so the ID never changes.
    * </p>
    *
    * @param id the goal ID, bare or as a node ID, e.g. {@code 2026-G1} or {@code goal:2026-G1}.
    * @param changes what changes; anything empty stays as it is.
    * @return the goal's node ID and path.
    * @throws ZtkException with {@link ZtkException.Kind#NOT_FOUND} when no goal has the ID.
    * @throws ZtkException with {@link ZtkException.Kind#USAGE} when a parent is given for a yearly goal.
    * @throws ZtkException with {@link ZtkException.Kind#VALIDATION} when the parent is not a yearly goal of the same
    *                      year, or is closed, or closing the goal would leave open goals or efforts under it. Nothing
    *                      is written.
    * @throws UncheckedIOException when the goals cannot be read or the note cannot be written.
    */
   public Saved update(String id, Changes changes) {
      Goal goal = find(id);
      changes.parent().ifPresent(given -> requireQuarter(goal.period()));
      Goal after = new Goal(goal.id(), goal.period(), changes.title().orElse(goal.title()),
            changes.status().or(goal::status), goal.closed(), changes.parent().or(goal::parent), goal.path(),
            goal.needsClosing());
      // Checked before writing: a refused change leaves the note as it was. Its closed date and whether it needs
      // closing only matter to warnings, which never stand in the way. A goal has no tasks of its own: its open work
      // is goals and efforts.
      Plan.refuse(Plan.read(configuration, clock).added(after, List.of(), List.of()));

      Frontmatter.update(goal.path(), fields -> {
         changes.title().ifPresent(value -> fields.put("title", value));
         changes.parent().ifPresent(value -> fields.put("parent", value));
         changes.status().ifPresent(value -> value.write(fields, goal.status(), LocalDate.now(clock)));
      });
      return new Saved(Goal.PREFIX + goal.id(), goal.path());
   }

   /**
    * Lists the goals of a year or quarter.
    *
    * <p>
    * A year lists its yearly goals and the quarterly goals of its four quarters; a quarter lists its quarterly goals.
    * The yearly goals the listed quarterly goals move forward are always listed too, whatever their status, so every
    * goal shows where it leads.
    * </p>
    *
    * @param period the year or quarter.
    * @param statuses the statuses to list; empty for any.
    * @return the goals, each yearly goal followed by its quarterly goals.
    * @throws UncheckedIOException when the goals cannot be read.
    */
   public Listing list(Period period, Set<GoalStatus> statuses) {
      Stream<Period.OfQuarter> quarters = switch (period) {
         case Period.OfYear year -> year.quarters();
         case Period.OfQuarter quarter -> Stream.of(quarter);
      };
      // The one collection: deciding which goals to list takes a first pass for the parents of the listed quarterly
      // goals, which are listed whatever their status. The year's yearly goals are always read, as possible parents.
      List<Goal> read;
      try (Stream<Goal> goals = Stream.concat(notes.of(period.ofYear()), quarters.flatMap(notes::of))) {
         read = goals.toList();
      }

      Predicate<Goal> wanted = goal -> statuses.isEmpty() || goal.status().filter(statuses::contains).isPresent();
      Set<String> parents = read.stream()
            .filter(goal -> goal.period() instanceof Period.OfQuarter && wanted.test(goal))
            .flatMap(goal -> goal.parent().stream())
            .collect(Collectors.toSet());
      Set<String> yearly = read.stream()
            .filter(goal -> goal.period() instanceof Period.OfYear)
            .map(Goal::id)
            .collect(Collectors.toSet());

      // Tree order: each yearly goal, then its quarterly goals; a quarterly goal is placed by its parent, and one whose
      // parent does not exist comes last. Yearly IDs share their prefix, so the shorter is the smaller: G2 before G10.
      Comparator<String> byNumber = Comparator.comparing(String::length).thenComparing(Comparator.naturalOrder());
      Function<Goal, String> anchor = goal -> goal.period() instanceof Period.OfYear
            ? goal.id()
            : goal.parent().filter(yearly::contains).orElse(null);
      Comparator<Goal> tree = Comparator.comparing(anchor, Comparator.nullsLast(byNumber)).thenComparing(ORDER);

      // Efforts name quarterly goals: a yearly goal counts those of its quarterly goals read for this period.
      Map<String, Long> byGoal;
      try (Stream<Effort> all = efforts.all()) {
         byGoal = all.flatMap(effort -> effort.goal().stream())
               .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
      }
      Map<String, Long> byYearly = read.stream()
            .filter(goal -> goal.period() instanceof Period.OfQuarter && goal.parent().isPresent())
            .collect(Collectors.groupingBy(goal -> goal.parent().orElseThrow(),
                  Collectors.summingLong(goal -> byGoal.getOrDefault(goal.id(), 0L))));
      Function<Goal, Long> count = goal -> (goal.period() instanceof Period.OfYear ? byYearly : byGoal)
            .getOrDefault(goal.id(), 0L);

      // A year lists its yearly goals on their own merit; a quarter lists yearly goals only as parents.
      boolean listsYearly = period instanceof Period.OfYear;
      return new Listing(period, read.stream()
            .filter(goal -> parents.contains(goal.id())
                  || (wanted.test(goal) && (listsYearly || goal.period() instanceof Period.OfQuarter)))
            .sorted(tree)
            .map(goal -> new Listed(goal, count.apply(goal)))
            .toList());
   }

   /**
    * Shows one goal with what moves it forward: its efforts, and a yearly goal's quarterly goals with theirs.
    *
    * @param id the goal ID, bare or as a node ID, e.g. {@code 2026-G1} or {@code goal:2026-G1}.
    * @return the goal, its efforts with their tasks counted, and a yearly goal's quarterly goals with theirs.
    * @throws ZtkException with {@link ZtkException.Kind#NOT_FOUND} when no goal has the ID.
    * @throws ZtkException with {@link ZtkException.Kind#TASKWARRIOR} when Taskwarrior fails.
    * @throws UncheckedIOException when the goals or efforts cannot be read.
    */
   public Info info(String id) {
      Goal goal = find(id);
      // One export for every effort's tasks, and one read of the efforts, grouped by the goal they name.
      Map<String, TaskCounts> counts = effortTasks.counts();
      Map<String, List<Efforts.Listed>> byGoal;
      try (Stream<Effort> all = efforts.all()) {
         byGoal = all.filter(effort -> effort.goal().isPresent())
               .sorted(Comparator.comparing(Effort::slug))
               .collect(Collectors.groupingBy(effort -> effort.goal().orElseThrow(), Collectors.mapping(
                     effort -> new Efforts.Listed(effort, counts.getOrDefault(effort.slug(), TaskCounts.NONE)),
                     Collectors.toList())));
      }
      Function<Goal, List<Efforts.Listed>> effortsOf = found -> byGoal.getOrDefault(found.id(), List.of());

      List<Info> quarterly = switch (goal.period()) {
         case Period.OfYear year -> {
            try (Stream<Goal> goals = year.quarters().flatMap(notes::of)) {
               yield goals.filter(child -> child.parent().equals(Optional.of(goal.id())))
                     .sorted(ORDER)
                     .map(child -> new Info(child, effortsOf.apply(child), List.of()))
                     .toList();
            }
         }
         case Period.OfQuarter _ -> List.of();
      };
      return new Info(goal, effortsOf.apply(goal), quarterly);
   }

   /**
    * Finds a goal by its ID.
    *
    * @param id the goal ID, bare or as a node ID, e.g. {@code 2026-G1} or {@code goal:2026-G1}.
    * @return the goal.
    * @throws ZtkException with {@link ZtkException.Kind#NOT_FOUND} when no goal has the ID.
    * @throws UncheckedIOException when the goals cannot be read.
    */
   private Goal find(String id) {
      return notes.find(id).orElseThrow(() -> unknown(id));
   }

   /**
    * @param id the goal ID as given.
    * @return the error for a goal that does not exist, pointing to where goals are listed.
    */
   private static ZtkException unknown(String id) {
      return ZtkException.notFound("No goal " + id + ". Goal IDs look like 2026-G1 or 2026-Q4-G1; list them with: ztk goal list");
   }

   /**
    * Ensures a goal of a period may name a parent, as the command line gives it.
    *
    * @param period the period of the goal.
    * @throws ZtkException with {@link ZtkException.Kind#USAGE} when the goal is a yearly goal, which has no parent.
    */
   private static void requireQuarter(Period period) {
      if (period instanceof Period.OfYear) {
         throw ZtkException.usage("A yearly goal has no parent. Run the same command without --parent.");
      }
   }

   /**
    * Words a rule a new goal would break, as a reason not to create it.
    *
    * @param problem the error the new goal would add.
    * @param year the year of the new goal.
    * @return why the goal is not created, and what to give instead.
    * @throws UncheckedIOException when the goals cannot be read.
    */
   private String refusal(PlanProblem problem, Period.OfYear year) {
      // The plan's own hints change an existing goal; a goal not created yet is given another parent instead.
      return switch (problem) {
         case PlanProblem.UnknownGoal unknown -> "No yearly goal " + unknown.named() + " in " + year + ". " + choices(year);
         case PlanProblem.OpenUnderClosed closed -> "The yearly goal " + closed.parent().id() + " is "
               + closed.parent().status().orElseThrow().label() + ", so it takes no new goals. Reopen it first with: "
               + "ztk goal set " + closed.parent().id() + " --status " + GoalStatus.OPEN.label();
         default -> problem.message() + " " + problem.hint();
      };
   }

   /**
    * Describes the yearly goals a quarterly goal can have as its parent.
    *
    * @param year the year of the quarterly goal.
    * @return a sentence listing them, or saying how to create the first one.
    * @throws UncheckedIOException when the goals cannot be read.
    */
   private String choices(Period.OfYear year) {
      List<Goal> candidates;
      try (Stream<Goal> goals = notes.of(year)) {
         candidates = goals.sorted(ORDER).toList();
      }
      if (candidates.isEmpty()) {
         return year + " has no yearly goal yet. Create one first with: ztk goal new " + year + " --title \"…\"";
      }
      return "The yearly goals of " + year + ":" + candidates.stream()
            .map(goal -> System.lineSeparator() + "  " + goal.id() + "  " + goal.title())
            .reduce("", String::concat);
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
               + "dashes. Give one like --slug " + Slugs.of(slug) + ".");
      }
      return slug;
   }

   /**
    * Derives a slug from a title, cut to {@value #MAX_SLUG} characters.
    *
    * @param title the goal's title.
    * @return the slug, e.g. {@code provably-correct}.
    * @throws ZtkException with {@link ZtkException.Kind#USAGE} when the title has no letters or digits.
    */
   private static String derived(String title) {
      String slug = Slugs.of(title, MAX_SLUG);
      if (slug.isEmpty()) {
         throw ZtkException.usage("The title '" + title + "' has no letters or digits to name the file after. "
               + "Name it with --slug, e.g. --slug kickoff.");
      }
      return slug;
   }

   /**
    * Gives the next goal number of a period.
    *
    * @param period the period of the new goal.
    * @param prefix what every goal ID of the period starts with before its number, e.g. {@code 2026-Q4-G}.
    * @return the highest number in use plus one; 1 when there is no goal yet.
    * @throws UncheckedIOException when the goals cannot be read.
    */
   private int next(Period period, String prefix) {
      try (Stream<Goal> goals = notes.of(period)) {
         return goals.mapToInt(goal -> Integer.parseInt(goal.id().substring(prefix.length())))
               .max()
               .orElse(0) + 1;
      }
   }
}
