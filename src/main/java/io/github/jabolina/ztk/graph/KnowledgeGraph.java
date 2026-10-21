package io.github.jabolina.ztk.graph;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.github.jabolina.ztk.ZtkException;
import io.github.jabolina.ztk.notes.KnowledgeBase;
import io.github.jabolina.ztk.notes.Note;
import io.github.jabolina.ztk.notes.NoteWarning;
import io.github.jabolina.ztk.notes.Wikilink;
import io.github.jabolina.ztk.notes.Wikilinks;
import io.github.jabolina.ztk.planning.Effort;
import io.github.jabolina.ztk.planning.Goal;
import io.github.jabolina.ztk.planning.Plan;
import io.github.jabolina.ztk.taskwarrior.Task;
import io.github.jabolina.ztk.taskwarrior.TaskStore;

/**
 * The graph of the knowledge base: notes connected by the wikilinks they contain, and to the tasks and projects of
 * Taskwarrior.
 *
 * <p>
 * Nothing is stored: the graph is derived from the notes and one task export each time it is read. Of each note, it
 * keeps what the edges need, its header and the targets of its wikilinks, never its body.
 * </p>
 *
 * <p>
 * It knows the nodes, the edges between them, and where each wikilink leads. How it is walked or checked is up to
 * its readers.
 * </p>
 *
 * @since 1.0
 * @author José Bolina
 */
public final class KnowledgeGraph {

   private static final String PROJECT = "project:";
   private static final String TASK = "task:";
   private static final String NOTE = "note:";
   private static final String GOAL = "goal:";
   private static final String EFFORT = "effort:";

   // Task numbers as Taskwarrior shows them; 0 is no task's.
   private static final Pattern NUMBER = Pattern.compile("[1-9]\\d*");
   // The first eight characters of a task UUID, as Taskwarrior shows done tasks.
   private static final Pattern SHORT_UUID = Pattern.compile("[0-9a-f]{8}");
   private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");

   // Every note with its wikilinks, by node ID.
   private final Map<String, NoteLinks> byId = new HashMap<>();
   // Every note, by its absolute path, as a task's note attribute leads to it.
   private final Map<Path, Note> byPath = new HashMap<>();
   // Every note, by the name wikilinks use; several when notes in different folders share it.
   private final Map<String, List<Note>> byName = new HashMap<>();
   // The notes whose wikilinks name a target, by that target.
   private final Map<String, List<Note>> linkingTo = new HashMap<>();
   // The task of each task note, by the note's node ID.
   private final Map<String, Node.OfTask> taskByNote = new HashMap<>();
   // The note naming each task in its frontmatter, by the task's UUID.
   private final Map<String, Note> noteByTask = new HashMap<>();
   // The project of every task, by project name.
   private final Map<String, Node.OfProject> projects = new HashMap<>();
   // Every task, by UUID.
   private final Map<String, Node.OfTask> tasks = new HashMap<>();
   // The tasks whose note attribute names a file that is no note of the graph, done ones included.
   private final List<Node.OfTask> missingNotes = new ArrayList<>();
   // Every goal file, by goal ID: the first read is the goal, any other is a duplicate. A goal or effort stands for
   // its own note, whose node it replaces.
   private final Map<String, List<Node.OfGoal>> goals = new HashMap<>();
   // Every effort, by slug.
   private final Map<String, Node.OfEffort> efforts = new HashMap<>();
   // The notes sitting where goals do, but not named after a goal ID of their folder.
   private final List<Note> misnamed = new ArrayList<>();


   // To find the note a task's note attribute names, which is relative to the knowledge base.
   private final KnowledgeBase knowledgeBase;

   // The notes left out because they cannot be read.
   // Shared by every part of the read, which is sequential, a plain list is fine.
   private final List<NoteWarning> skipped;

   private KnowledgeGraph(KnowledgeBase knowledgeBase, List<NoteWarning> skipped) {
      this.knowledgeBase = knowledgeBase;
      this.skipped = skipped;
   }

   /**
    * A wikilink and where it leads.
    *
    * @param from     The note it is written in.
    * @param wikilink The wikilink, with its target and line.
    * @param to       The nodes it leads to: one note or task, several notes for an ambiguous link, or the dangling
    *                 target.
    */
   record Link(Note from, Wikilink wikilink, List<Node> to) { }

   /**
    * A note and its wikilinks.
    *
    * @param note      The note.
    * @param wikilinks The wikilinks in its body, in order; a target linked twice is there twice.
    */
   private record NoteLinks(Note note, List<Wikilink> wikilinks) { }

   /**
    * Reads the notes, tasks, goals and efforts, and the edges between them.
    *
    * @param knowledgeBase the notes, read once, one at a time.
    * @param store the tasks, exported once.
    * @param plan the goals and efforts, read from their notes.
    * @return the graph, as the notes and tasks are now.
    * @throws ZtkException with {@link ZtkException.Kind#TASKWARRIOR} when Taskwarrior fails.
    */
   public static KnowledgeGraph read(KnowledgeBase knowledgeBase, TaskStore store, Plan plan) {
      // Concurrent stream consuming would require a thread-safe list.
      List<NoteWarning> skipped = new ArrayList<>();
      KnowledgeGraph graph;
      try (Stream<NoteLinks> notes = knowledgeBase.read((note, lines) ->
            Optional.of(new NoteLinks(note, Wikilinks.links(lines).toList())), skipped::add)) {
         graph = notes.collect(() -> new KnowledgeGraph(knowledgeBase, skipped), KnowledgeGraph::add,
               KnowledgeGraph::merge);
      }

      // Each read once, straight into the graph's own indexes.
      try (Stream<Goal> goals = plan.goals();
           Stream<Effort> efforts = plan.efforts();
           Stream<Path> misnamed = plan.misnamed()) {
         goals.forEach(goal -> graph.goals.computeIfAbsent(goal.id(), ignore -> new ArrayList<>())
               .add(new Node.OfGoal(goal)));
         efforts.forEach(effort -> graph.efforts.put(effort.slug(), new Node.OfEffort(effort)));
         // Read as plain notes; one the knowledge base skipped is already an unreadable note.
         misnamed.flatMap(path -> graph.note(path).stream()).forEach(graph.misnamed::add);
      }

      // One export for every task, done ones included.
      // The graph shows history, too.
      // Deleted tasks are gone forever.
      for (Task task : store.query("status.not:deleted")) {
         // The note attribute first.
         // task_uuid when the attribute is missing or names a missing file.
         Optional<Note> named = task.note().flatMap(graph::noteAt);
         Optional<Note> note = named.or(() -> Optional.ofNullable(graph.noteByTask.get(task.uuid())));
         Node.OfTask node = new Node.OfTask(task, note);
         graph.add(node);

         // Kept even when task_uuid finds the note.
         // The attribute itself still leads nowhere.
         if (task.note().isPresent() && named.isEmpty())
            graph.missingNotes.add(node);

         // One node per project.
         // However many tasks it has, the folder is looked up once.
         task.project().ifPresent(name -> graph.projects
               .computeIfAbsent(name, ignore -> new Node.OfProject(name, knowledgeBase.projectFolder(name))));
      }

      return graph;
   }

   /**
    * @return the files of the knowledge base left out of the graph because they cannot be read, each with why and
    *         how to fix it.
    */
   public Stream<NoteWarning> skipped() {
      return skipped.stream();
   }

   /**
    * Adds a note and its wikilink targets.
    *
    * @param noteLinks a note with its wikilinks.
    */
   private void add(NoteLinks noteLinks) {
      Note note = noteLinks.note();
      byId.put(note.id(), noteLinks);
      byPath.put(note.header().path(), note);
      if (note instanceof Note.Task taskNote)
         noteByTask.put(taskNote.uuid(), note);
      byName.computeIfAbsent(fileName(note), ignore -> new ArrayList<>()).add(note);
      noteLinks.wikilinks().stream()
            .map(Wikilink::target)
            .distinct()
            .forEach(target -> linkingTo.computeIfAbsent(target, ignore -> new ArrayList<>()).add(note));
   }

   /**
    * Adds every note of another graph.
    *
    * @param other a graph of other notes, as built from another part of the same stream.
    */
   private void merge(KnowledgeGraph other) {
      // Node IDs are unique, so these never collide; names and targets are shared, so their lists are joined.
      byId.putAll(other.byId);
      byPath.putAll(other.byPath);
      noteByTask.putAll(other.noteByTask);
      other.byName.forEach((name, notes) -> byName.computeIfAbsent(name, ignore -> new ArrayList<>()).addAll(notes));
      other.linkingTo.forEach((target, notes) ->
            linkingTo.computeIfAbsent(target, ignore -> new ArrayList<>()).addAll(notes));
   }

   /**
    * Adds a task, linked to its note when it has one.
    *
    * @param task a task as a node.
    */
   private void add(Node.OfTask task) {
      tasks.put(task.task().uuid(), task);
      task.note().ifPresent(note -> taskByNote.put(note.id(), task));
   }

   /**
    * Lists every edge of a node.
    *
    * @param node a node of this graph.
    * @return its edges, each seen from it, in both directions; a note linking it by two names is listed twice.
    */
   Stream<Edge> edges(Node node) {
      return switch (node) {
         case Node.OfNote(Note note) -> Stream.concat(
               links(node),
               Stream.ofNullable(taskByNote.get(note.id())).map(task -> new Edge(node, task, "note", "in")));

         // The task holds its note, project and effort edges, so they go out; notes link to it by either UUID.
         case Node.OfTask task -> Stream.of(
                     task.note().stream().map(note -> new Edge(node, node(note), "note", "out")),
                     task.task().project().map(projects::get).stream()
                           .map(project -> new Edge(node, project, "project", "out")),
                     task.task().effort().flatMap(this::effort).stream()
                           .map(effort -> new Edge(node, effort, "effort", "out")),
                     linksTo(node, List.of(task.task().uuid(), task.task().uuid().substring(0, 8))))
               .flatMap(Function.identity());

         case Node.OfProject project -> tasks.values().stream()
               .filter(task -> task.task().project().equals(Optional.of(project.name())))
               .map(task -> new Edge(node, task, "project", "in"));

         // Up to its yearly goal, down to its quarterly goals or efforts.
         case Node.OfGoal goal -> Stream.concat(links(node), goalEdges(goal));

         // Up to its goal, down to its tasks.
         case Node.OfEffort effort -> Stream.of(
                     links(node),
                     goalEdges(effort),
                     tasks.values().stream()
                           .filter(task -> task.task().effort().equals(Optional.of(effort.effort().slug())))
                           .map(task -> new Edge(node, task, "effort", "in")))
               .flatMap(Function.identity());

         // Nothing is there yet to link anywhere, but notes link to it.
         case Node.OfDangling dangling -> linksTo(node, List.of(dangling.target()));
      };
   }

   /**
    * Lists every wikilink of every note, resolved.
    *
    * @return each note's wikilinks, every occurrence, each with where it leads.
    */
   Stream<Link> links() {
      return byId.values().stream()
            .flatMap(noteLinks -> noteLinks.wikilinks().stream()
                  .map(wikilink -> new Link(noteLinks.note(), wikilink,
                        resolve(noteLinks.note(), wikilink.target()))));
   }

   /**
    * @return every task, done ones included.
    */
   Stream<Node.OfTask> tasks() {
      return tasks.values().stream();
   }

   /**
    * @return the tasks whose note attribute names a file that is not a readable note, done tasks included. A note
    *         found through {@code task_uuid} does not make up for it.
    */
   Stream<Node.OfTask> tasksWithMissingNote() {
      return missingNotes.stream();
   }

   /**
    * @return the task notes whose {@code task_uuid} names no task of the graph, so deleted ones too.
    */
   Stream<Note.Task> orphanTaskNotes() {
      return byId.values().stream()
            .map(NoteLinks::note)
            // Every task note, even two naming the same task.
            .filter(Note.Task.class::isInstance)
            .map(Note.Task.class::cast)
            .filter(note -> !tasks.containsKey(note.uuid()));
   }

   /**
    * Finds a project.
    *
    * @param name the project name, e.g. {@code Alpha}.
    * @return the project of that name with its folder: the project of a task, done ones included, or a project
    *         folder no task names yet; empty when neither.
    */
   Optional<Node.OfProject> project(String name) {
      return Optional.ofNullable(projects.get(name))
            .or(() -> knowledgeBase.projectFolder(name).map(folder -> new Node.OfProject(name, Optional.of(folder))));
   }

   /**
    * Finds the note a task's note attribute names.
    *
    * @param attribute the path relative to the knowledge base, e.g. {@code Alpha/fix-bug.md}.
    * @return the note read from that file; empty when there is no such file, or it could not be read.
    */
   private Optional<Note> noteAt(String attribute) {
      return note(knowledgeBase.resolve(attribute));
   }

   /**
    * Finds the note read from a file.
    *
    * @param path the file's absolute path.
    * @return its note; empty when it is no note of the graph, e.g. because it could not be read.
    */
   Optional<Note> note(Path path) {
      return Optional.ofNullable(byPath.get(path));
   }

   /**
    * @return every goal, one per file: two files with one goal ID are both there.
    */
   Stream<Node.OfGoal> goals() {
      return goals.values().stream().flatMap(List::stream);
   }

   /**
    * Finds the files of a goal ID.
    *
    * @param id the bare goal ID, e.g. {@code 2026-Q4-G1}.
    * @return the goals with that ID, the first read being the graph's node and any other a duplicate; empty when no
    *         goal has it. Not to be changed.
    */
   List<Node.OfGoal> goals(String id) {
      return goals.getOrDefault(id, List.of());
   }

   /**
    * @return every effort.
    */
   Stream<Node.OfEffort> efforts() {
      return efforts.values().stream();
   }

   /**
    * @return the notes sitting where goals do, directly in a year's or a quarter's folder, but not named after a goal
    *         ID of that folder.
    */
   Stream<Note> misnamed() {
      return misnamed.stream();
   }

   /**
    * Finds the node an ID names.
    *
    * @param id the node ID as typed: {@code task:<uuid|short uuid|number>} or a bare number for a task,
    *           {@code project:<name>} for a project, {@code goal:<id>} or a bare goal ID for a goal,
    *           {@code effort:<slug>} for an effort, and {@code note:<path|name>} or a bare name for a note or an
    *           effort.
    * @return the node with that ID.
    * @throws ZtkException with {@link ZtkException.Kind#NOT_FOUND} when no node has that ID, and with
    *                      {@link ZtkException.Kind#VALIDATION} when it matches several.
    */
   Node find(String id) {
      if (id.startsWith(PROJECT)) {
         return one(id, Stream.ofNullable(projects.get(id.substring(PROJECT.length()))),
               "Find the projects with: task projects");
      }
      if (id.startsWith(TASK) || NUMBER.matcher(id).matches()) {
         String reference = id.startsWith(TASK) ? id.substring(TASK.length()) : id;
         return one(id, tasks.values().stream().filter(task -> matches(task.task(), reference)),
               "Find the task numbers with: task list");
      }
      if (id.startsWith(GOAL) || id.startsWith(EFFORT)) {
         return one(id, planned(id).stream(), id.startsWith(GOAL)
               ? "List the goals with: ztk goal list"
               : "List the efforts with: ztk effort list");
      }
      // A bare goal ID, e.g. 2026-Q4-G1. No note has that name: a goal's file name goes on with its slug.
      Optional<Node> goal = goal(id);
      if (goal.isPresent()) {
         return goal.get();
      }
      String name = id.startsWith(NOTE) ? id.substring(NOTE.length()) : id;
      // Its path first, which is also the name of a note at the root, then its name anywhere: as a wikilink
      // written at the root leads.
      NoteLinks exact = byId.get(NOTE + name);
      Stream<Note> matching = exact != null
            ? Stream.of(exact.note())
            : byName.getOrDefault(name, List.of()).stream();
      return one(id, matching.map(this::node), "Find a note's ID with: ztk search <words>");
   }

   /**
    * Finds the nodes a wikilink leads to.
    *
    * @param from the note the wikilink is written in.
    * @param target the wikilink target, e.g. {@code 20261016-lamport-clocks}.
    * @return the note of that name in the folder of {@code from} when there is one; otherwise every note of that
    *         name, several for an ambiguous link; otherwise the task a target shaped as a task UUID names;
    *         otherwise the dangling target itself.
    */
   private List<Node> resolve(Note from, String target) {
      List<Note> named = byName.getOrDefault(target, List.of());
      if (named.isEmpty()) {
         // Numbers are never task links: they change as tasks are done.
         boolean uuid = UUID.matcher(target).matches() || SHORT_UUID.matcher(target).matches();
         List<Node> found = uuid
               ? tasks.values().stream()
                  .filter(task -> matches(task.task(), target))
                  .map(Node.class::cast)
                  .toList()
               : List.of();
         // Kept as a node, so the walk shows what is still to write.
         return found.isEmpty() ? List.of(new Node.OfDangling(target)) : found;
      }
      Path folder = from.header().path().getParent();
      // A folder cannot hold two notes of one name, so at most one is found here.
      List<Note> notes = named.stream()
            .filter(note -> note.header().path().getParent().equals(folder))
            .findFirst()
            .map(List::of)
            .orElse(named);
      return notes.stream().map(this::node).toList();
   }

   /**
    * Tells whether a task is the one a reference names.
    *
    * @param task a task.
    * @param reference its number, its UUID, or the first eight characters of its UUID.
    * @return whether the reference names the task.
    */
   private static boolean matches(Task task, String reference) {
      // Eight digits read both ways: as a number and as the start of a UUID. Either names the task; a number this
      // long is no task's in practice, and if both match, find lists both IDs.
      boolean number = NUMBER.matcher(reference).matches() && String.valueOf(task.id()).equals(reference);
      return number
            || task.uuid().equals(reference)
            || SHORT_UUID.matcher(reference).matches() && task.uuid().startsWith(reference);
   }

   /**
    * Picks the one node an ID names.
    *
    * @param id the ID as typed, for messages.
    * @param matching the nodes it names.
    * @param hint how to find the right ID, when none matches.
    * @return the only node named.
    * @throws ZtkException with {@link ZtkException.Kind#NOT_FOUND} when none is named, and with
    *                      {@link ZtkException.Kind#VALIDATION} when several are.
    */
   private static Node one(String id, Stream<? extends Node> matching, String hint) {
      List<? extends Node> nodes = matching.toList();
      if (nodes.isEmpty()) {
         throw ZtkException.notFound("No node " + id + ". " + hint);
      }
      if (nodes.size() > 1) {
         throw ZtkException.validation(id + " matches several nodes: "
               + nodes.stream().map(Node::id).sorted().collect(Collectors.joining(", "))
               + ". Use one of these IDs.");
      }
      return nodes.getFirst();
   }

   /**
    * Lists the wikilink edges of a node with a note, both ways.
    *
    * @param node a note, goal or effort.
    * @return an edge to each node its note's wikilinks lead to, then from each note whose wikilinks lead to it; none
    *         when its note was not read.
    */
   private Stream<Edge> links(Node node) {
      NoteLinks noteLinks = byId.get(node.id());
      // A goal or effort whose note the knowledge base skipped: no wikilinks to follow.
      if (noteLinks == null) {
         return Stream.empty();
      }
      return Stream.concat(linksFrom(node, noteLinks), linksTo(node, List.of(fileName(noteLinks.note()))));
   }

   /**
    * Lists the edges of a note's wikilinks.
    *
    * @param node the node of the note, the edges are seen from.
    * @param noteLinks the note with its wikilinks.
    * @return an edge to each node its wikilinks lead to.
    */
   private Stream<Edge> linksFrom(Node node, NoteLinks noteLinks) {
      return noteLinks.wikilinks().stream()
            .map(Wikilink::target)
            // A target linked twice is still one edge.
            .distinct()
            .flatMap(target -> resolve(noteLinks.note(), target).stream())
            .map(to -> new Edge(node, to, "link", "out"));
   }

   /**
    * Lists the edges of the wikilinks leading to a node.
    *
    * @param node the node linked to, the edges are seen from.
    * @param names the wikilink targets that may name it: a note's name, a task's full and short UUID, or a dangling
    *              target.
    * @return an edge to each note whose wikilinks lead to it; a note linking it by two names is listed twice.
    */
   private Stream<Edge> linksTo(Node node, List<String> names) {
      // Naming the node is not enough: the link must resolve to it from where it is written.
      return names.stream().flatMap(name -> linkingTo.getOrDefault(name, List.of()).stream()
            .filter(from -> resolve(from, name).contains(node))
            .map(from -> new Edge(node, node(from), "link", "in")));
   }

   /**
    * Lists the {@code goal} edges of a goal or effort.
    *
    * @param node a goal or effort.
    * @return an edge to the goal it points to, then from each goal or effort pointing to it.
    */
   private Stream<Edge> goalEdges(Node.Planned node) {
      return Stream.concat(
            up(node).map(goal -> new Edge(node, goal, "goal", "out")).stream(),
            Stream.<Node.Planned>concat(goals.values().stream().map(List::getFirst), efforts.values().stream())
                  .filter(other -> up(other).equals(Optional.of(node)))
                  .map(other -> new Edge(node, other, "goal", "in")));
   }

   /**
    * Finds the goal a goal or effort points to: a quarterly goal's {@code parent}, an effort's {@code goal}.
    *
    * @param node a goal or effort.
    * @return the goal it names; empty when it names none, or a goal that does not exist.
    */
   private Optional<Node> up(Node.Planned node) {
      Optional<String> goal = switch (node) {
         // Empty for a yearly goal.
         case Node.OfGoal quarterly -> quarterly.goal().parent();
         case Node.OfEffort effort -> effort.effort().goal();
      };
      // Whatever level or year it names: check tells a wrong one apart.
      return goal.flatMap(this::goal);
   }

   /**
    * @param note a note of the graph.
    * @return its node: the goal or effort for a goal's or effort's own note, the note otherwise.
    */
   private Node node(Note note) {
      return planned(note.id()).orElseGet(() -> new Node.OfNote(note));
   }

   /**
    * Finds a goal or effort by its node ID.
    *
    * @param id a node ID, e.g. {@code goal:2026-Q4-G1} or {@code effort:infinijepsen-demo}.
    * @return the goal or effort; empty when none has the ID, or it names neither.
    */
   private Optional<Node> planned(String id) {
      if (id.startsWith(GOAL)) {
         return goal(id.substring(GOAL.length()));
      }
      if (id.startsWith(EFFORT)) {
         return effort(id.substring(EFFORT.length())).map(Node.class::cast);
      }
      return Optional.empty();
   }

   /**
    * @param id the bare goal ID, e.g. {@code 2026-Q4-G1}.
    * @return the goal; empty when none has the ID. Of two files with one ID, the first read; check reports both.
    */
   private Optional<Node> goal(String id) {
      return Optional.ofNullable(goals.get(id)).<Node>map(List::getFirst);
   }

   /**
    * Finds an effort.
    *
    * @param slug the effort's slug, e.g. {@code infinijepsen-demo}.
    * @return the effort; empty when none has the slug.
    */
   Optional<Node.OfEffort> effort(String slug) {
      return Optional.ofNullable(efforts.get(slug));
   }

   /**
    * @param note a note.
    * @return its file name without {@code .md}, which is what a wikilink names.
    */
   private static String fileName(Note note) {
      String name = note.header().name();
      return name.substring(name.lastIndexOf('/') + 1);
   }
}
