package io.github.jabolina.ztk.cli.completers;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.github.jabolina.ztk.Context;

/**
 * Completes a graph node with the notes, tasks and projects it can name.
 *
 * <p>
 * Notes are offered by file name, or by path when notes in several folders share it; tasks by their ID; and projects
 * as {@code project:<name>}, all as {@code ztk graph} takes them. Notes are listed, not read, so completion stays fast
 * on a large knowledge base.
 * </p>
 *
 * @since 1.0
 * @author José Bolina
 */
public class NodeCompleter implements ZtkCompleter {

   private static final String PROJECT = "project:";

   @Override
   public void complete(ZtkCompleterInvocation invocation) {
      Context context = invocation.context();
      List<String> names = context.knowledgeBase().names();
      // A file name shared by notes in several folders names none of them alone, so those notes are offered by path.
      Map<String, Long> notesPerFileName = names.stream()
            .collect(Collectors.groupingBy(NodeCompleter::fileName, Collectors.counting()));
      Stream<String> notes = names.stream()
            .map(name -> notesPerFileName.get(fileName(name)) == 1 ? fileName(name) : name);
      Stream<String> tasks = context.store().ids().stream();
      Stream<String> projects = context.store().projects().stream()
            .map(project -> PROJECT + project);
      List<String> all = Stream.of(notes, tasks, projects)
            .flatMap(Function.identity())
            .toList();
      invocation.offer(all);
   }

   /**
    * @param name a note name, e.g. {@code Alpha/fix-bug}.
    * @return the name of its file, e.g. {@code fix-bug}.
    */
   private static String fileName(String name) {
      return name.substring(name.lastIndexOf('/') + 1);
   }
}
