package io.github.jabolina.ztk.graph;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import io.github.jabolina.ztk.ZtkException;
import io.github.jabolina.ztk.notes.Note;
import io.github.jabolina.ztk.notes.NoteType;

/**
 * Walks the graph of the knowledge base from a node, nearest nodes first.
 *
 * @since 1.0
 * @author José Bolina
 */
public final class Graph {

   private final KnowledgeGraph graph;

   /**
    * @param graph the graph to walk.
    */
   public Graph(KnowledgeGraph graph) {
      this.graph = graph;
   }

   /**
    * Where a walk starts, and how far and through what it goes.
    *
    * @param start The node ID to start from, as typed: e.g. {@code note:zettel/20261015-causality}, a note's name,
    *              {@code task:<uuid|short uuid|number>}, a task number, {@code project:<name>}, {@code goal:<id>} or
    *              a bare goal ID, or {@code effort:<slug>}.
    * @param depth How many edges away from the start node to go, 0 or more; 0 for the start node alone.
    * @param kinds The kinds of nodes the walk goes through, e.g. {@code note}; empty for every kind.
    * @param types The types of notes the walk goes through, e.g. {@code zettel}; empty for every type. Nodes other
    *              than notes have no type, and are kept.
    * @param edges The kinds of edges the walk follows: {@code link}, {@code note}, {@code project}, {@code effort} or
    *              {@code goal}; empty for every kind.
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
         boolean edge = edges.isEmpty() || reached.via().map(via -> edges.contains(via.kind())).orElse(true);
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
    * @return the start node, then every node reached, each once at its shortest depth. It holds no open files, so it
    *         needs no closing.
    * @throws ZtkException with {@link ZtkException.Kind#NOT_FOUND} when no node has the start ID, and with
    *                      {@link ZtkException.Kind#VALIDATION} when it names several nodes.
    */
   public Stream<Reached> walk(Query query) {
      Node first = graph.find(query.start());

      // Shared by every depth, so a node is only reached at the first, and shortest, depth it appears.
      Set<String> seen = new HashSet<>(Set.of(first.id()));
      // One list per depth, each computed from the previous one only when the consumer gets that far.
      return Stream.iterate(List.of(new Reached(first, 0, Optional.empty())),
            d -> !d.isEmpty(),
            d -> d.stream()
                  .flatMap(this::around)
                  .filter(query::admits)
                  .filter(reached -> seen.add(reached.node().id()))
                  .toList())
            .limit(query.depth() + 1L)
            .flatMap(List::stream);
   }

   /**
    * Lists the nodes the walk reaches from a reached node.
    *
    * @param current a reached node.
    * @return its neighbors, one edge further from the start; some may have been reached already. None for a project,
    *         unless it is the start node, and none for a dangling target.
    */
   private Stream<Reached> around(Reached current) {
      return switch (current.node()) {
         // A hub anywhere at the start.
         // Every task of the project would be one edge away, burying the notes related to the start.
         case Node.OfProject project when current.depth() > 0 -> Stream.empty();

         // A note still to write, not a place to walk through.
         // The notes linking to it are not related to each other.
         case Node.OfDangling dangling -> Stream.empty();

         default -> graph.edges(current.node())
               .map(edge -> new Reached(edge.to(), current.depth() + 1, Optional.of(edge)));
      };
   }
}
