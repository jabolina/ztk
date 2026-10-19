package io.github.jabolina.ztk.graph;

import io.github.jabolina.ztk.util.JsonWritable;
import tools.jackson.databind.node.ObjectNode;

/**
 * An edge of the graph, seen from one of its nodes.
 *
 * <p>
 * Each edge can be seen from either end: {@code out} from the node that holds it, {@code in} from the other.
 * </p>
 *
 * @param from      The node the edge is seen from.
 * @param to        The node at the other end.
 * @param kind      The kind of edge: {@code link}, {@code note} or {@code project}.
 * @param direction {@code out} when {@code from} points to {@code to}, {@code in} when it is pointed to.
 * @since 1.0
 * @author José Bolina
 */
public record Edge(Node from, Node to, String kind, String direction) implements JsonWritable {

   /**
    * Writes the edge as the way to {@code to}: the node ID of {@code from}, the kind as {@code edge}, and the
    * direction.
    *
    * @param target the JSON object to write into.
    * @return {@code target}, for chaining.
    */
   @Override
   public ObjectNode writeJson(ObjectNode target) {
      // The node at the other end is the one reached, written next to it, so only the way back is written here.
      target.put("from", from.id());
      target.put("edge", kind);
      target.put("direction", direction);
      return target;
   }
}
