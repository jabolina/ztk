package io.github.jabolina.ztk.cli.completers;

import io.github.jabolina.ztk.planning.Goal;
import io.github.jabolina.ztk.planning.Period;

/**
 * Completes an argument naming a goal with the IDs of every yearly and quarterly goal, e.g. {@code 2026-Q4-G1}.
 *
 * <p>
 * Closed goals are offered too, as they can still be shown and reopened. The nested completers offer only the goals
 * an option accepts: the open goals that new work can go under.
 * </p>
 *
 * @since 1.0
 * @author José Bolina
 */
public class GoalCompleter implements ZtkCompleter {

   @Override
   public void complete(ZtkCompleterInvocation invocation) {
      invocation.offer(invocation.context().plan().goals().filter(this::offered).map(Goal::id).toList());
   }

   /**
    * @param goal a goal of the knowledge base.
    * @return whether it is offered; every goal is.
    */
   protected boolean offered(Goal goal) {
      return true;
   }

   /**
    * @param goal a goal of the knowledge base.
    * @return whether it is open; a missing or invalid status is not.
    */
   private static boolean open(Goal goal) {
      return goal.status().filter(status -> !status.closed()).isPresent();
   }

   /**
    * Completes the yearly goal of a quarterly goal: the open yearly goals.
    *
    * @since 1.0
    * @author José Bolina
    */
   public static final class Yearly extends GoalCompleter {

      @Override
      protected boolean offered(Goal goal) {
         return goal.period() instanceof Period.OfYear && open(goal);
      }
   }

   /**
    * Completes the quarterly goal of an effort: the open quarterly goals.
    *
    * @since 1.0
    * @author José Bolina
    */
   public static final class Quarterly extends GoalCompleter {

      @Override
      protected boolean offered(Goal goal) {
         return goal.period() instanceof Period.OfQuarter && open(goal);
      }
   }
}
