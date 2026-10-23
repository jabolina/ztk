package io.github.jabolina.ztk.cli.completers;

import java.util.List;

/**
 * Completes the Taskwarrior attributes among the words ztk passes to Taskwarrior, such as those of {@code ztk add}.
 *
 * <p>
 * A word starting with {@code project:}, or the start of it, is completed with the projects that have pending tasks.
 * A word starting with {@code +} is completed with the tags in use. Other words are free
 * text, such as the task description, and get no candidates.
 * </p>
 *
 * @since 1.0
 * @author José Bolina
 */
public class AttributeCompleter implements ZtkCompleter {

   private static final String PROJECT = "project:";
   private static final String TAG = "+";

   @Override
   public void complete(ZtkCompleterInvocation invocation) {
      String typed = invocation.getGivenCompleteValue();
      List<String> candidates = switch (typed)  {
         // The start of the attribute too, e.g. proj: no description word is completed into a project by itself.
         case String s when s.startsWith(PROJECT) || !s.isEmpty() && PROJECT.startsWith(s) ->
               prefixed(PROJECT, invocation.context().store().projects());
         case String s when s.startsWith(TAG) -> prefixed(TAG, invocation.context().store().tags());
         default -> List.of();
      };
      invocation.offer(candidates);
   }

   /**
    * Turns values into complete words of the attribute being typed.
    *
    * <p>
    * A candidate replaces the whole word, so it must keep the attribute's own prefix.
    * </p>
    *
    * @param prefix the attribute prefix, e.g. {@code project:} or {@code +}.
    * @param values the attribute values, e.g. the project names.
    * @return each value preceded by the prefix, in the same order.
    */
   private static List<String> prefixed(String prefix, List<String> values) {
      return values.stream()
            .map(value -> prefix + value)
            .toList();
   }
}
