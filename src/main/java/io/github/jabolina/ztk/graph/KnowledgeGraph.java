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
    * Reads the notes and tasks, and the edges between them.
    *
    * @param knowledgeBase the notes, read once, one at a time.
    * @param store the tasks, exported once.
    * @return the graph, as the notes and tasks are now.
    * @throws ZtkException with {@link ZtkException.Kind#TASKWARRIOR} when Taskwarrior fails.
    */
   public static KnowledgeGraph read(KnowledgeBase knowledgeBase, TaskStore store) {
      // Concurrent stream consuming would require a thread-safe list.
      List<NoteWarning> skipped = new ArrayList<>();
      KnowledgeGraph graph;
      try (Stream<NoteLinks> notes = knowledgeBase.read((note, lines) ->
            Optional.of(new NoteLinks(note, Wikilinks.links(lines).toList())), skipped::add)) {
         graph = notes.collect(() -> new KnowledgeGraph(knowledgeBase, skipped), KnowledgeGraph::add,
               KnowledgeGraph::merge);
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
         case Node.OfNote(Note note) -> Stream.of(
                     linksFrom(note),
                     linksTo(node, List.of(fileName(note))),
                     Stream.ofNullable(taskByNote.get(note.id())).map(task -> new Edge(node, task, "note", "in")))
               .flatMap(Function.identity());

         // The task holds its note and project edges, so both go out; notes link to it by either UUID.
         case Node.OfTask task -> Stream.of(
                     task.note().stream().map(note -> new Edge(node, new Node.OfNote(note), "note", "out")),
                     task.task().project().map(projects::get).stream()
                           .map(project -> new Edge(node, project, "project", "out")),
                     linksTo(node, List.of(task.task().uuid(), task.task().uuid().substring(0, 8))))
               .flatMap(Function.identity());

         case Node.OfProject project -> tasks.values().stream()
               .filter(task -> task.task().project().equals(Optional.of(project.name())))
               .map(task -> new Edge(node, task, "project", "in"));

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
      return Optional.ofNullable(byPath.get(knowledgeBase.resolve(attribute)));
   }

   /**
    * Finds the node an ID names.
    *
    * @param id the node ID as typed: {@code task:<uuid|short uuid|number>} or a bare number for a task,
    *           {@code project:<name>} for a project, and {@code note:<path|name>} or a bare name for a note.
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
      String name = id.startsWith(NOTE) ? id.substring(NOTE.length()) : id;
      // Its path first, which is also the name of a note at the root, then its name anywhere: as a wikilink
      // written at the root leads.
      NoteLinks exact = byId.get(NOTE + name);
      Stream<Note> matching = exact != null
            ? Stream.of(exact.note())
            : byName.getOrDefault(name, List.of()).stream();
      return one(id, matching.map(Node.OfNote::new), "Find a note's ID with: ztk search <words>");
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
      return notes.stream().<Node>map(Node.OfNote::new).toList();
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
    * Lists the edges of a note's wikilinks.
    *
    * @param note a note.
    * @return an edge to each node its wikilinks lead to.
    */
   private Stream<Edge> linksFrom(Note note) {
      return byId.get(note.id()).wikilinks().stream()
            .map(Wikilink::target)
            // A target linked twice is still one edge.
            .distinct()
            .flatMap(target -> resolve(note, target).stream())
            .map(to -> new Edge(new Node.OfNote(note), to, "link", "out"));
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
            .map(from -> new Edge(node, new Node.OfNote(from), "link", "in")));
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
