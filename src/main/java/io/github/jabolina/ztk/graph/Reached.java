package io.github.jabolina.ztk.graph;

import java.util.Optional;

import io.github.jabolina.ztk.util.JsonWritable;
import tools.jackson.databind.node.ObjectNode;

/**
 * A node reached by walking the graph from a start node.
 *
 * @param node  The node reached.
 * @param depth How many edges away from the start node, 0 for the start node itself.
 * @param via   The edge that reached it; empty for the start node.
 * @since 1.0
 * @author José Bolina
 */
public record Reached(Node node, int depth, Optional<Via> via) implements JsonWritable {

   /**
    * The edge a node was reached through.
    *
    * @param from      The node ID at the other end, one step closer to the start node.
    * @param edge      The kind of edge, e.g. {@code link}.
    * @param direction {@code out} when {@code from} points to the node reached, {@code in} when it is pointed to.
    */
   public record Via(String from, String edge, String direction) implements JsonWritable {

      @Override
      public ObjectNode writeJson(ObjectNode target) {
         target.put("from", from);
         target.put("edge", edge);
         target.put("direction", direction);
         return target;
      }
   }

   /**
    * Writes the node's fields, then its {@code depth} and {@code via}.
    *
    * @param target the JSON object to write into.
    * @return {@code target}, for chaining.
    */
   @Override
   public ObjectNode writeJson(ObjectNode target) {
      node.writeJson(target);
      target.put("depth", depth);
      // Null rather then left out, ensuring all returns have the same fields.
      via.ifPresentOrElse(edge -> edge.writeJson(target.putObject("via")), () -> target.putNull("via"));
      return target;
   }
}
