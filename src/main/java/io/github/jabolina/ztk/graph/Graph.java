package io.github.jabolina.ztk.graph;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.github.jabolina.ztk.ZtkException;
import io.github.jabolina.ztk.notes.KnowledgeBase;
import io.github.jabolina.ztk.notes.Note;
import io.github.jabolina.ztk.notes.NoteType;
import io.github.jabolina.ztk.notes.NoteWarning;
import io.github.jabolina.ztk.notes.Wikilinks;
import io.github.jabolina.ztk.taskwarrior.Task;
import io.github.jabolina.ztk.taskwarrior.TaskStore;

/**
 * The graph of the knowledge base: notes connected by the wikilinks they contain, and to the tasks and projects of
 * Taskwarrior.
 *
 * <p>
 * Nothing is stored: the graph is derived from the notes and one task export each time it is walked.
 * </p>
 *
 * @since 1.0
 * @author José Bolina
 */
public final class Graph {

   private static final String PROJECT = "project:";
   private static final String TASK = "task:";
   private static final String NOTE = "note:";

   // Task numbers as Taskwarrior shows them; 0 is no task's.
   private static final Pattern NUMBER = Pattern.compile("[1-9]\\d*");
   // The first eight characters of a task UUID, as Taskwarrior shows done tasks.
   private static final Pattern SHORT_UUID = Pattern.compile("[0-9a-f]{8}");
   private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");

   private final KnowledgeBase knowledgeBase;
   private final TaskStore store;

   /**
    * @param knowledgeBase the notes the graph is derived from.
    * @param store the tasks the graph is derived from.
    */
   public Graph(KnowledgeBase knowledgeBase, TaskStore store) {
      this.knowledgeBase = knowledgeBase;
      this.store = store;
   }

   /**
    * Where a walk starts, and how far and through what it goes.
    *
    * @param start The node ID to start from, as typed: e.g. {@code note:zettel/20261015-causality}, a note's name,
    *              {@code task:<uuid|short uuid|number>}, a task number, or {@code project:<name>}.
    * @param depth How many edges away from the start node to go, 0 or more; 0 for the start node alone.
    * @param kinds The kinds of nodes the walk goes through, e.g. {@code note}; empty for every kind.
    * @param types The types of notes the walk goes through, e.g. {@code zettel}; empty for every type. Nodes other
    *              than notes have no type, and are kept.
    * @param edges The kinds of edges the walk follows: {@code link}, {@code note} or {@code project}; empty for every
    *              kind.
    */
   public record Query(String start, int depth, Set<String> kinds, EnumSet<NoteType> types, Set<String> edges) {

      /**
       * Tells whether the walk may reach a node.
       *
       * @param reached a node one edge further than the nodes reached so far.
       * @return whether it is listed and walked through.
       */
      private boolean admits(Reached reached) {
         Node node = reached.node();
         boolean kind = kinds.isEmpty() || kinds.contains(node.kind());
         boolean type = types.isEmpty() || !(node instanceof Node.OfNote(Note note)) || types.contains(note.type());
         // Only the start node has no edge, and it is never filtered.
         boolean edge = edges.isEmpty() || reached.via().map(via -> edges.contains(via.edge())).orElse(true);
         return kind && type && edge;
      }
   }

   /**
    * Walks the graph from a node, nearest nodes first.
    *
    * <p>
    * The nodes are found as the stream is consumed, one depth at a time, so a caller that stops early stops the walk.
    * A node the query leaves out is neither listed nor walked through. The start node is always listed.
    * </p>
    *
    * @param query where to start, and how far and through what to go.
    * @param warnings receives the notes skipped because they cannot be read.
    * @return the start node, then every node reached, each once at its shortest depth. It holds no open files, so it
    *         needs no closing.
    * @throws ZtkException with {@link ZtkException.Kind#NOT_FOUND} when no node has the start ID, and with
    *                      {@link ZtkException.Kind#VALIDATION} when it names several nodes.
    */
   public Stream<Reached> walk(Query query, Consumer<NoteWarning> warnings) {
      Index index = index(warnings);
      Node first = index.start(query.start());

      // Shared by every depth, so a node is only reached at the first, and shortest, depth it appears.
      Set<String> seen = new HashSet<>(Set.of(first.id()));
      // One list per depth, each computed from the previous one only when the consumer gets that far.
      return Stream.iterate(List.of(new Reached(first, 0, Optional.empty())),
            d -> !d.isEmpty(),
            d -> d.stream()
                  .flatMap(index::around)
                  // Before seen: a node left out by one edge is not taken for reached.
                  .filter(query::admits)
                  .filter(reached -> seen.add(reached.node().id()))
                  .toList())
            .limit(query.depth() + 1L)
            .flatMap(List::stream);
   }

   /**
    * Reads the notes and tasks, and indexes the edges between them.
    *
    * @param warnings receives the notes skipped because they cannot be read.
    * @return the index of every note, task and project.
    * @throws ZtkException with {@link ZtkException.Kind#TASKWARRIOR} when Taskwarrior fails.
    */
   private Index index(Consumer<NoteWarning> warnings) {
      Index index;
      try (Stream<Linked> notes = knowledgeBase.read((note, lines) ->
            Optional.of(new Linked(note, Wikilinks.targets(lines).collect(Collectors.toSet()))), warnings)) {
         index = Index.of(notes);
      }

      // One export for every task, done ones included.
      // The graph shows history, too.
      // Deleted tasks are gone forever.
      for (Task task : store.query("status.not:deleted")) {
         // The note attribute first; task_uuid when the attribute is missing or names a missing file.
         Optional<Note> note = task.note()
               .map(knowledgeBase::resolve)
               .map(index.byPath()::get)
               .or(() -> Optional.ofNullable(index.noteByTask().get(task.uuid())));
         Node.OfTask node = new Node.OfTask(task, note);
         index.add(node);
         // One node per project, however many tasks it has; the folder is looked up once.
         task.project().ifPresent(name -> index.projects()
               .computeIfAbsent(name, ignore -> new Node.OfProject(name, knowledgeBase.projectFolder(name))));
      }

      return index;
   }

   /**
    * @param note a note.
    * @return its file name without {@code .md}, which is what a wikilink names.
    */
   private static String fileName(Note note) {
      String name = note.header().name();
      return name.substring(name.lastIndexOf('/') + 1);
   }

   /**
    * Takes one edge from a reached node.
    *
    * @param current the node the edge is taken from.
    * @param node the node at the other end of the edge.
    * @param edge the kind of edge, e.g. {@code link} or {@code note}.
    * @param direction {@code out} when {@code current} points to {@code node}, {@code in} when it is pointed to.
    * @return {@code node}, one edge further from the start than {@code current}.
    */
   private static Reached step(Reached current, Node node, String edge, String direction) {
      Reached.Via via = new Reached.Via(current.node().id(), edge, direction);
      return new Reached(node, current.depth() + 1, Optional.of(via));
   }

   /**
    * A note and the targets of its wikilinks.
    *
    * @param note    The note.
    * @param targets The wikilink targets in its body.
    */
   private record Linked(Note note, Set<String> targets) { }

   /**
    * The notes, the tasks and the edges between them, as written: links are resolved as the walk reaches them.
    *
    * @param byId       Every note with the distinct targets of its wikilinks, by node ID.
    * @param byPath     Every note, by its absolute path, as a task's note attribute leads to it.
    * @param byName     Every note, by the name wikilinks use; several when notes in different folders share it.
    * @param linkingTo  The notes whose wikilinks name a target, by that target.
    * @param taskByNote The task of each task note, by the note's node ID.
    * @param noteByTask The note naming each task in its frontmatter, by the task's UUID.
    * @param projects   The project of every task, by project name.
    * @param tasks      Every task, by UUID.
    */
   private record Index(Map<String, Linked> byId, Map<Path, Note> byPath, Map<String, List<Note>> byName,
                        Map<String, List<Note>> linkingTo, Map<String, Node.OfTask> taskByNote,
                        Map<String, Note> noteByTask, Map<String, Node.OfProject> projects,
                        Map<String, Node.OfTask> tasks) {

      /**
       * Indexes the notes as they are read.
       *
       * @param notes every note with its wikilink targets, consumed here.
       * @return the index of those notes.
       */
      public static Index of(Stream<Linked> notes) {
         return notes.collect(Index::mutableEmpty, Index::add, Index::merge);
      }

      /**
       * @return an index of no notes, ready to be added to.
       */
      private static Index mutableEmpty() {
         return new Index(new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
               new HashMap<>(), new HashMap<>(), new HashMap<>());
      }

      /**
       * Adds a note and its wikilink targets.
       *
       * @param linked a note with its wikilink targets.
       */
      private void add(Linked linked) {
         Note note = linked.note();
         byId.put(note.id(), linked);
         byPath.put(note.header().path(), note);
         if (note instanceof Note.Task taskNote)
            noteByTask.put(taskNote.uuid(), note);
         byName.computeIfAbsent(fileName(note), ignore -> new ArrayList<>()).add(note);
         linked.targets().forEach(target -> linkingTo.computeIfAbsent(target, ignore -> new ArrayList<>()).add(note));
      }

      /**
       * Adds every note of another index.
       *
       * @param other an index of other notes, as built from another part of the same stream.
       */
      private void merge(Index other) {
         // Node IDs are unique, so these never collide; names and targets are shared, so their lists are joined.
         byId.putAll(other.byId);
         byPath.putAll(other.byPath);
         noteByTask.putAll(other.noteByTask);
         other.byName.forEach((name, notes) -> byName.computeIfAbsent(name, key -> new ArrayList<>()).addAll(notes));
         other.linkingTo.forEach((target, notes) ->
               linkingTo.computeIfAbsent(target, key -> new ArrayList<>()).addAll(notes));
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
       * Lists the nodes one edge away from a reached node.
       *
       * @param current a reached node.
       * @return its neighbors; some may have been reached already. None for a project, unless it is the start node,
       *         and none for a dangling target.
       */
      public Stream<Reached> around(Reached current) {
         return switch (current.node()) {
            case Node.OfNote(Note note) -> Stream.of(
                  linksFrom(current, note),
                  linksTo(current, List.of(fileName(note))),
                  Stream.ofNullable(taskByNote.get(note.id())).map(task -> step(current, task, "note", "in")))
                  .flatMap(Function.identity());

            // The task holds its note and project edges, so both go out; notes link to it by either UUID.
            case Node.OfTask task -> Stream.of(
                  task.note().stream().map(note -> step(current, new Node.OfNote(note), "note", "out")),
                  task.task().project().map(projects::get).stream()
                        .map(project -> step(current, project, "project", "out")),
                  linksTo(current, List.of(task.task().uuid(), task.task().uuid().substring(0, 8))))
                  .flatMap(Function.identity());

            // The start node: what the walk was asked about, and the only time a project's tasks are looked for.
            case Node.OfProject project when current.depth() == 0 -> tasks.values().stream()
                  .filter(task -> task.task().project().equals(Optional.of(project.name())))
                  .map(task -> step(current, task, "project", "in"));

            // A hub anywhere else: every task of the project would be one edge away, burying the nodes related to the
            // start.
            case Node.OfProject project -> Stream.empty();

            // Nothing is there yet to link anywhere.
            case Node.OfDangling dangling -> Stream.empty();
         };
      }

      /**
       * Finds the node a walk starts from.
       *
       * @param id the node ID as typed: {@code task:<uuid|short uuid|number>} or a bare number for a task,
       *           {@code project:<name>} for a project, and {@code note:<path|name>} or a bare name for a note.
       * @return the node with that ID.
       * @throws ZtkException with {@link ZtkException.Kind#NOT_FOUND} when no node has that ID, and with
       *                      {@link ZtkException.Kind#VALIDATION} when it matches several.
       */
      private Node start(String id) {
         if (id.startsWith(PROJECT)) {
            return one(id, Stream.ofNullable(projects.get(id.substring(PROJECT.length()))),
                  "Find the projects with: task projects");
         }
         if (id.startsWith(TASK) || NUMBER.matcher(id).matches()) {
            String reference = id.startsWith(TASK) ? id.substring(TASK.length()) : id;
            return one(id, tasks.values().stream().filter(task -> matches(task.task(), reference)),
                  "Find the task numbers with: task list");
         }
         String name = id.startsWith(NOTE) ? id.substring(NOTE.length()) : id;
         // Its path first, which is also the name of a note at the root, then its name anywhere: as a wikilink
         // written at the root leads.
         Linked exact = byId.get(NOTE + name);
         Stream<Note> matching = exact != null
               ? Stream.of(exact.note())
               : byName.getOrDefault(name, List.of()).stream();
         return one(id, matching.map(Node.OfNote::new), "Find a note's ID with: ztk search <words>");
      }

      /**
       * Tells whether a task is the one a reference names.
       *
       * @param task a task.
       * @param reference its number, its UUID, or the first eight characters of its UUID.
       * @return whether the reference names the task.
       */
      private static boolean matches(Task task, String reference) {
         if (NUMBER.matcher(reference).matches()) {
            return String.valueOf(task.id()).equals(reference);
         }
         return task.uuid().equals(reference)
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
                  ? tasks.values().stream().filter(task -> matches(task.task(), target)).map(Node.class::cast).toList()
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
         return notes.stream().<Node>map(Node.OfNote::new).toList();
      }

      /**
       * Lists the nodes a reached note links to.
       *
       * @param current the reached node.
       * @param note its note.
       * @return the nodes its wikilinks lead to.
       */
      private Stream<Reached> linksFrom(Reached current, Note note) {
         return byId.get(note.id()).targets().stream()
               .flatMap(target -> resolve(note, target).stream())
               .map(to -> step(current, to, "link", "out"));
      }

      /**
       * Lists the notes linking to a reached node.
       *
       * @param current the reached node.
       * @param names the wikilink targets that may name it: a note's name, or a task's full and short UUID.
       * @return the notes whose wikilinks lead to it; a note linking it by two names is listed twice.
       */
      private Stream<Reached> linksTo(Reached current, List<String> names) {
         // Naming the node is not enough: the link must resolve to it from where it is written.
         return names.stream().flatMap(name -> linkingTo.getOrDefault(name, List.of()).stream()
               .filter(from -> resolve(from, name).contains(current.node()))
               .map(from -> step(current, new Node.OfNote(from), "link", "in")));
      }
   }
}
