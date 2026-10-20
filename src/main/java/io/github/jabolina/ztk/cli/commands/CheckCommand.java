package io.github.jabolina.ztk.cli.commands;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Option;
import org.aesh.command.option.OptionList;

import io.github.jabolina.ztk.ZtkException;
import io.github.jabolina.ztk.cli.ZtkInvocation;
import io.github.jabolina.ztk.cli.completers.ProjectCompleter;
import io.github.jabolina.ztk.cli.renderer.Table;
import io.github.jabolina.ztk.graph.Check;
import io.github.jabolina.ztk.graph.Problem;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code ztk check}: reports what is broken in the knowledge base, and how to fix it.
 *
 * <p>
 * Read-only: it never changes a note or a task. It fails when it finds errors, so it can guard a script or a commit.
 * </p>
 *
 * <pre>
 * ztk check
 * ztk check --json | jq -r '.problems[] | "\(.path):\(.line) \(.hint)"'
 * ztk check --code dangling-link,missing-note
 * ztk check --project Alpha
 * </pre>
 *
 * @since 1.0
 * @author José Bolina
 */
@CommandDefinition(name = "check", description = "Report broken links and tasks in the knowledge base", generateHelp = true)
public final class CheckCommand implements Command<ZtkInvocation> {

   // One row per problem; the path with its line, as editors open it.
   private static final Table<Problem> TABLE_RENDERER = Table.<Problem>builder()
         .column("SEVERITY", problem -> problem.severity().name())
         .column("CODE", Problem::code)
         // The path, with the line when there is one; empty for a problem in no file.
         .column("PATH", problem -> problem.path()
               .map(path -> path + problem.line().map(line -> ":" + line).orElse(""))
               .orElse(""))
         .column("MESSAGE", problem -> problem.message() + " " + problem.hint())
         .build();

   @OptionList(name = "code", allowedValues = { "dangling-link", "missing-note", "task-without-project", "ambiguous-link",
         "orphan-task-note", "unreadable-note" }, description = "Only report problems with these codes, comma-separated")
   List<String> codes;

   @Option(description = "Only report problems of this project's notes and tasks, e.g., 'Alpha'", completer = ProjectCompleter.class)
   String project;

   @Override
   public CommandResult execute(ZtkInvocation invocation) {
      ObjectNode json = invocation.jsonResult();
      ArrayNode array = json.putArray("problems");

      Set<String> wantedCodes = codes == null
            ? Set.of()
            : codes.stream()
               .map(c -> c.toLowerCase(Locale.ROOT))
               .collect(Collectors.toSet());
      Check.Filter filter = new Check.Filter(wantedCodes, Optional.ofNullable(project));

      Check checker = new Check(invocation.context().graph());
      List<Problem> problems;
      try (Stream<Problem> stream = checker.problems(filter)) {
         problems = stream.toList();
      }
      problems.forEach(problem -> problem.writeJson(array.addObject()));
      // Rendered only in human mode
      invocation.result(() -> TABLE_RENDERER.render(problems), json);

      long errors = problems.stream()
            .filter(problem -> problem.severity() == Problem.Severity.ERROR)
            .count();
      if (errors > 0) {
         // After the result, so the problems are printed before the run fails.
         throw ZtkException.validation(errors + (errors == 1 ? " error" : " errors")
               + " found. Fix them, then run ztk check again.");
      }
      return CommandResult.SUCCESS;
   }
}
