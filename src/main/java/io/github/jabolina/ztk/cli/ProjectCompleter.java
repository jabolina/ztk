package io.github.jabolina.ztk.cli;

/**
 * Completes an argument naming a project with the projects that have pending tasks.
 *
 * <p>
 * Sub-projects are offered by their full name, e.g. {@code Alpha.Docs}.
 * </p>
 *
 * @since 1.0
 * @author José Bolina
 */
public class ProjectCompleter implements ZtkCompleter {

   @Override
   public void complete(ZtkCompleterInvocation invocation) {
      invocation.offer(invocation.context().store().projects());
   }
}
