package io.github.jabolina.ztk.cli.completers;

import java.util.stream.Stream;

import io.github.jabolina.ztk.planning.Effort;

/**
 * Completes an argument naming an effort with the slugs of every effort, e.g. {@code infinijepsen-demo}.
 *
 * <p>
 * Closed efforts are offered too, as they can still be shown and reopened. {@link Open} offers only the efforts a new
 * pending task can join.
 * </p>
 *
 * @since 1.0
 * @author José Bolina
 */
public class EffortCompleter implements ZtkCompleter {

   @Override
   public void complete(ZtkCompleterInvocation invocation) {
      try (Stream<Effort> efforts = invocation.context().plan().efforts()) {
         invocation.offer(efforts.filter(this::offered).map(Effort::slug).toList());
      }
   }

   /**
    * @param effort an effort of the knowledge base.
    * @return whether it is offered; every effort is.
    */
   protected boolean offered(Effort effort) {
      return true;
   }

   /**
    * Completes the effort a task joins: the open efforts, planned or active.
    *
    * @since 1.0
    * @author José Bolina
    */
   public static final class Open extends EffortCompleter {

      @Override
      protected boolean offered(Effort effort) {
         // A missing or invalid status is not open.
         return effort.status().filter(status -> !status.closed()).isPresent();
      }
   }
}
