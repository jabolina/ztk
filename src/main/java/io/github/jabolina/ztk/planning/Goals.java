package io.github.jabolina.ztk.planning;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.github.jabolina.ztk.ZtkException;
import io.github.jabolina.ztk.configuration.Configuration;
import io.github.jabolina.ztk.notes.NoteFiles;
import io.github.jabolina.ztk.notes.PlanningNotes;
import io.github.jabolina.ztk.util.Frontmatter;
import io.github.jabolina.ztk.util.JsonWritable;
import io.github.jabolina.ztk.util.Labeled;
import io.github.jabolina.ztk.util.Slugs;
import tools.jackson.databind.JsonNode;
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
public final class Goals implements PlanningNotes {

   // Longer slugs derived from a title are cut, so file names stay readable in listings and completion.
   private static final int MAX_SLUG = 60;

   // A goal ID: the period it is for, then its number, e.g. 2026-Q4-G1.
   private static final Pattern ID = Pattern.compile("(\\d{4}(?:-Q[1-4])?)-G\\d+");

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
    * A goal just created or changed.
    *
    * @param id   The goal ID, e.g. {@code 2026-G1}.
    * @param path The absolute path of the goal's note.
    */
   public record Saved(String id, Path path) implements JsonWritable {

      /**
       * Writes the goal's {@code id} and {@code path}.
       *
       * @param target the JSON object to write into.
       * @return {@code target}, for chaining.
       */
      @Override
      public ObjectNode writeJson(ObjectNode target) {
         target.put("id", Goal.PREFIX + id);
         target.put("path", path.toString());
         return target;
      }
   }

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
    * The goals of a period, each yearly goal followed by its quarterly goals.
    *
    * @param period The year or quarter listed.
    * @param goals  The goals, in that order.
    */
   public record Listing(Period period, List<Goal> goals) implements JsonWritable {

      /**
       * Writes the goals, flat, in a {@code goals} array.
       *
       * @param target the JSON object to write into.
       * @return {@code target}, for chaining.
       */
      @Override
      public ObjectNode writeJson(ObjectNode target) {
         ArrayNode array = target.putArray("goals");
         goals.forEach(goal -> goal.writeJson(array.addObject()));
         return target;
      }
   }

   /**
    * One goal and the goals that move it forward.
    *
    * @param goal  The goal.
    * @param goals A yearly goal's quarterly goals, by quarter; empty for a quarterly goal.
    */
   public record Info(Goal goal, List<Goal> goals) implements JsonWritable {

      /**
       * Writes the goal's fields, then its quarterly goals in a {@code goals} array.
       *
       * @param target the JSON object to write into.
       * @return {@code target}, for chaining.
       */
      @Override
      public ObjectNode writeJson(ObjectNode target) {
         goal.writeJson(target);
         ArrayNode array = target.putArray("goals");
         goals.forEach(child -> child.writeJson(array.addObject()));
         return target;
      }
   }

   private final Configuration configuration;
   private final Clock clock;

   /**
    * @param configuration the configuration giving the goals folder.
    * @param clock the clock for the {@code date} of new goals, {@code closed}, and which goals need closing.
    */
   public Goals(Configuration configuration, Clock clock) {
      this.configuration = configuration;
      this.clock = clock;
   }

   /**
    * Tells which goal a note is: a note directly in its level of the goals folder, named after a goal ID of that level.
    *
    * @param note the note's absolute path.
    * @return the goal ID, e.g. {@code 2026-Q4-G1}; empty for any other note, including material kept next to a goal.
    */
   @Override
   public Optional<String> goal(Path note) {
      Path goals = configuration.folders().goals();
      return note.startsWith(goals) ? Levels.goal(goals.relativize(note)) : Optional.empty();
   }

   /**
    * Creates a goal, open, from the template of its level.
    *
    * @param details what is known about the goal.
    * @return the new goal's ID and path.
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
      Optional<String> checkedParent = details.parent().map(given -> parent(period, given));
      if (period instanceof Period.OfQuarter quarter && checkedParent.isEmpty()) {
         throw ZtkException.usage("A quarterly goal moves a yearly goal forward: name it with --parent. " + choices(quarter.ofYear()));
      }
      String prefix = period + "-G";
      String id = prefix + next(period, prefix);
      Path file = period.folder(configuration.folders().goals()).resolve(id + "-" + name + ".md");

      SequencedMap<String, String> frontmatter = new LinkedHashMap<>();
      frontmatter.put("title", details.title());
      frontmatter.put("status", GoalStatus.OPEN.label());
      checkedParent.ifPresent(value -> frontmatter.put("parent", value));
      frontmatter.put("date", Frontmatter.timestamp(OffsetDateTime.now(clock)));
      String template = switch (period) {
         case Period.OfYear _ -> YEARLY_TEMPLATE;
         case Period.OfQuarter _ -> QUARTERLY_TEMPLATE;
      };
      NoteFiles.write(file, frontmatter, template);
      return new Saved(id, file);
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
    * @return the goal's ID and path.
    * @throws ZtkException with {@link ZtkException.Kind#NOT_FOUND} when no goal has the ID.
    * @throws ZtkException with {@link ZtkException.Kind#USAGE} when a parent is given for a yearly goal.
    * @throws ZtkException with {@link ZtkException.Kind#VALIDATION} when the parent is not a yearly goal of the same
    *                      year, or is closed. Nothing is written.
    * @throws UncheckedIOException when the goals cannot be read or the note cannot be written.
    */
   public Saved update(String id, Changes changes) {
      Goal goal = find(id);
      // Checked before writing: a refused change leaves the note as it was.
      Optional<String> checkedParent = changes.parent().map(given -> parent(goal.period(), given));

      Frontmatter.update(goal.path(), fields -> {
         changes.title().ifPresent(value -> fields.put("title", value));
         checkedParent.ifPresent(value -> fields.put("parent", value));
         changes.status().ifPresent(value -> {
            fields.put("status", value.label());
            if (!value.closed()) {
               fields.remove("closed");
            } else if (goal.status().filter(value::equals).isEmpty()) {
               // Dated when the status moves to a closed one; setting the current one again keeps its date.
               fields.put("closed", LocalDate.now(clock).toString());
            }
         });
      });
      return new Saved(goal.id(), goal.path());
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
      try (Stream<Goal> goals = Stream.concat(goals(period.ofYear()), quarters.flatMap(this::goals))) {
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

      // A year lists its yearly goals on their own merit; a quarter lists yearly goals only as parents.
      boolean listsYearly = period instanceof Period.OfYear;
      return new Listing(period, read.stream()
            .filter(goal -> parents.contains(goal.id())
                  || (wanted.test(goal) && (listsYearly || goal.period() instanceof Period.OfQuarter)))
            .sorted(tree)
            .toList());
   }

   /**
    * Shows one goal with the goals that move it forward.
    *
    * @param id the goal ID, bare or as a node ID, e.g. {@code 2026-G1} or {@code goal:2026-G1}.
    * @return the goal, with its quarterly goals when it is a yearly goal.
    * @throws ZtkException with {@link ZtkException.Kind#NOT_FOUND} when no goal has the ID.
    * @throws UncheckedIOException when the goals cannot be read.
    */
   public Info info(String id) {
      Goal goal = find(id);
      List<Goal> quarterly = switch (goal.period()) {
         case Period.OfYear year -> {
            try (Stream<Goal> goals = year.quarters().flatMap(this::goals)) {
               yield goals.filter(child -> child.parent().equals(Optional.of(goal.id())))
                     .sorted(ORDER)
                     .toList();
            }
         }
         case Period.OfQuarter _ -> List.of();
      };
      return new Info(goal, quarterly);
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
      // Accepted as JSON results print it, so an agent can feed it straight back.
      String bare = id.startsWith(Goal.PREFIX) ? id.substring(Goal.PREFIX.length()) : id;
      Period period = period(bare).orElseThrow(() -> unknown(id));
      try (Stream<Goal> goals = goals(period)) {
         return goals.filter(goal -> goal.id().equals(bare))
               .findFirst()
               .orElseThrow(() -> unknown(id));
      }
   }

   /**
    * Reads the period a goal ID is for.
    *
    * @param id the bare goal ID, e.g. {@code 2026-Q4-G1}.
    * @return the year or quarter; empty when the text is not a goal ID.
    */
   private static Optional<Period> period(String id) {
      Matcher matcher = ID.matcher(id);
      return matcher.matches() ? Optional.of(Period.parse(matcher.group(1))) : Optional.empty();
   }

   /**
    * @param id the goal ID as given.
    * @return the error for a goal that does not exist, pointing to where goals are listed.
    */
   private static ZtkException unknown(String id) {
      return ZtkException.notFound("No goal " + id + ". Goal IDs look like 2026-G1 or 2026-Q4-G1; list them with: ztk goal list");
   }

   /**
    * Checks the parent given for a goal.
    *
    * @param period the period of the goal.
    * @param id the ID of the yearly goal given as its parent.
    * @return {@code id}, unchanged.
    * @throws ZtkException with {@link ZtkException.Kind#USAGE} when the goal is a yearly goal, which has no parent.
    * @throws ZtkException with {@link ZtkException.Kind#VALIDATION} when the parent is not a yearly goal of the same
    *                      year, or is closed.
    */
   private String parent(Period period, String id) {
      if (!(period instanceof Period.OfQuarter quarter)) {
         throw ZtkException.usage("A yearly goal has no parent. Run the same command without --parent.");
      }
      // Same year only: a quarterly goal is a step toward a goal of its own year.
      Period.OfYear year = quarter.ofYear();
      Goal found;
      try (Stream<Goal> goals = goals(year)) {
         found = goals.filter(goal -> goal.id().equals(id))
               .findFirst()
               .orElseThrow(() -> ZtkException.validation("No yearly goal " + id + " in " + year + ". " + choices(year)));
      }

      // Strict: no open work goes under a closed goal.
      if (found.status().filter(GoalStatus::closed).isPresent()) {
         throw ZtkException.validation("The yearly goal " + id + " is " + found.status().orElseThrow().label()
               + ", so it takes no new goals. Reopen it first with: ztk goal set " + id + " --status open");
      }
      return id;
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
      try (Stream<Goal> goals = goals(year)) {
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
      try (Stream<Goal> goals = goals(period)) {
         return goals.mapToInt(goal -> Integer.parseInt(goal.id().substring(prefix.length())))
               .max()
               .orElse(0) + 1;
      }
   }

   /**
    * Reads the goals of a period: the notes directly in its folder whose names are goal IDs of that period.
    *
    * @param period the year or quarter.
    * @return the goals, read as the stream is consumed; empty when the folder does not exist. It must be closed, e.g.
    *         with try-with-resources, to release the folder.
    * @throws UncheckedIOException when the folder cannot be listed or a goal cannot be read.
    */
   private Stream<Goal> goals(Period period) {
      Path goals = configuration.folders().goals();
      Path folder = period.folder(goals);
      if (!Files.isDirectory(folder)) {
         return Stream.empty();
      }
      Stream<Path> files;
      try {
         files = Files.list(folder);
      } catch (IOException e) {
         throw new UncheckedIOException("Failed reading folder: " + folder, e);
      }
      // Only files directly in the folder: anything in a subfolder is material, or a goal of another level.
      // The listing's close handler carries over to the returned pipeline.
      return files.filter(Files::isRegularFile)
            .flatMap(file -> Levels.goal(goals.relativize(file)).map(id -> read(id, file)).stream());
   }

   /**
    * Reads one goal from its note.
    *
    * @param id the goal ID, from the file name.
    * @param file the goal's note.
    * @return the goal.
    * @throws UncheckedIOException when the note cannot be read.
    */
   private Goal read(String id, Path file) {
      JsonNode frontmatter = Frontmatter.read(file);
      // Only called with the IDs of goal files, which always name a period.
      Period period = period(id).orElseThrow();
      Optional<GoalStatus> status = frontmatter.path("status").asStringOpt()
            .flatMap(label -> Labeled.parse(GoalStatus.class, label));
      // Still open after its period ended: whether it was reached, missed or dropped is for the user to say.
      boolean needsClosing = status.equals(Optional.of(GoalStatus.OPEN)) && LocalDate.now(clock).isAfter(period.end());
      return new Goal(id, period,
            frontmatter.path("title").asStringOpt().orElse(file.getFileName().toString()),
            status,
            frontmatter.path("closed").asStringOpt(),
            frontmatter.path("parent").asStringOpt(),
            file,
            needsClosing);
   }
}
