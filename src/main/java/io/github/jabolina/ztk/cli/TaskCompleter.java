package io.github.jabolina.ztk.cli;

/**
 * Completes an argument naming a task with the IDs of the tasks that have one.
 *
 * <p>
 * The IDs come from Taskwarrior as configured for ztk, so completion and the command read the same tasks.
 * </p>
 *
 * @since 1.0
 * @author José Bolina
 */
public class TaskCompleter implements ZtkCompleter {

   @Override
   public void complete(ZtkCompleterInvocation invocation) {
      invocation.offer(invocation.context().store().ids());
   }
}
