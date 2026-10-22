package io.github.jabolina.ztk.cli.commands;

import java.nio.file.Path;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.CompletionFallback;
import org.aesh.command.option.Option;

import io.github.jabolina.ztk.ZtkException;
import io.github.jabolina.ztk.cli.ZtkInvocation;
import io.github.jabolina.ztk.reports.Summaries;
import io.github.jabolina.ztk.reports.Summary;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code ztk summary}: prints the facts of a week, a quarter or a year, for an agent to expand into the weekly review
 * or a retrospective, and writes them once the review is done.
 *
 * <p>
 * With {@code --write}, the facts and the narrative are saved together, so the file is never left half written and
 * nobody edits it afterwards. Only the path goes to stdout then, as for every command creating a file.
 * </p>
 *
 * <pre>
 * ztk summary
 * ztk summary 2026-W42 --json
 * ztk summary 2026-Q3 --json
 * ztk summary 2026-W42 --write --narrative - &lt; narrative.md
 * </pre>
 *
 * @since 1.0
 * @author José Bolina
 */
@CommandDefinition(name = "summary", description = "Print the facts of a week, quarter or year, the previous week by default",
      generateHelp = true)
public class SummaryCommand implements Command<ZtkInvocation> {

   // The conventional marker for "read this value from stdin".
   private static final String STDIN = "-";

   // A period is never a file, so the shell offers none of the working directory's.
   @Argument(description = "The ISO week, quarter or year, e.g. 2026-W42, 2026-Q4 or 2026",
         completeFallback = CompletionFallback.NONE)
   String period;

   @Option(hasValue = false, description = "Save the summary in the summaries folder and print its path")
   boolean write;

   @Option(description = "The narrative written after the facts, or '-' to read it from stdin; needs --write")
   String narrative;

   @Override
   public CommandResult execute(ZtkInvocation invocation) {
      Summaries summaries = invocation.context().summaries();
      // No period means the weekly review's.
      String name = period == null ? summaries.lastWeek().toString() : period;
      // A narrative is only ever saved with its facts: printed alone, it would be lost.
      if (narrative != null && !write) {
         throw ZtkException.usage("--narrative is saved with the facts, so it needs --write."
               + " Run 'ztk summary " + name + " --write --narrative -'.");
      }
      Summary summary = summaries.of(name);
      ObjectNode json = invocation.jsonResult();
      if (!write) {
         invocation.result(() -> summary.writeMarkdown(new StringBuilder()).toString(), summary.writeJson(json));
         return CommandResult.SUCCESS;
      }

      Path path = summaries.write(summary, narrative == null ? "" : narrative(invocation));
      json.put("id", summary.name());
      json.put("path", path.toString());
      // Human messages redirected to stderr, the result goes to stdout.
      invocation.printErr("Summary of " + summary.name() + " written.");
      invocation.result(path + System.lineSeparator(), json);
      return CommandResult.SUCCESS;
   }

   /**
    * Reads the narrative as given, or from stdin when given as {@code -}.
    *
    * @param invocation the running command, which reads stdin.
    * @return the narrative, without surrounding blank lines.
    * @throws ZtkException with {@link ZtkException.Kind#USAGE} when the narrative is blank.
    */
   private String narrative(ZtkInvocation invocation) {
      String text = (narrative.equals(STDIN) ? invocation.stdin() : narrative).strip();
      if (text.isBlank()) {
         throw ZtkException.usage("--narrative is blank. Give the narrative's text,"
               + " or leave --narrative out to write the facts alone.");
      }
      return text;
   }
}
