package io.github.jabolina.ztk.cli.commands;

import java.nio.file.Path;
import java.time.Year;
import java.util.Optional;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

import io.github.jabolina.ztk.ZtkException;
import io.github.jabolina.ztk.cli.Editor;
import io.github.jabolina.ztk.cli.ZtkInvocation;
import io.github.jabolina.ztk.notes.Papers;

/**
 * {@code ztk paper}: commands for paper notes.
 *
 * @since 1.0
 * @author José Bolina
 */
@CommandDefinition(name = "paper", description = "File and summarize papers", generateHelp = true,
      groupCommands = { PaperCommand.New.class })
public class PaperCommand implements Command<ZtkInvocation> {

   @Override
   public CommandResult execute(ZtkInvocation invocation) {
      invocation.println(invocation.getHelpInfo());
      return CommandResult.SUCCESS;
   }

   /**
    * {@code ztk paper new}: files a copy of a PDF in the papers folder, next to a new note for summarizing it, and
    * prints the note's path.
    *
    * <p>
    * The paper is filed by venue and year, both named after its title. The original PDF is left where it is. Only the
    * path goes to stdout, which makes the command usable in shell substitutions.
    * </p>
    *
    * <pre>
    * ztk paper new ~/Downloads/raft.pdf --title "In Search of an Understandable Consensus Algorithm" --venue ATC --year 2014
    * ztk paper new ~/Downloads/preprint.pdf --title "A Preprint" --authors "Ada Lovelace" --link https://arxiv.org/abs/1234
    * ztk paper new ~/Downloads/raft.pdf --title "In Search of an Understandable Consensus Algorithm" -e
    * </pre>
    *
    * @since 1.0
    * @author José Bolina
    */
   @CommandDefinition(name = "new", description = "File a PDF next to a new paper note and print the note's path",
         generateHelp = true)
   public static final class New implements Command<ZtkInvocation> {

      @Argument(description = "The PDF to file, e.g., /path/to/paper.pdf", required = true)
      String pdf;

      @Option(description = "The paper's title, which also names the files", required = true)
      String title;

      @Option(description = "Where it was published, e.g., ATC; papers without one are filed under others")
      String venue;

      @Option(description = "When it was published, e.g., 2014; The current year when not given")
      Integer year;

      @Option(description = "The authors, e.g., 'Diego Ongaro, John Ousterhout'")
      String authors;

      @Option(description = "Where to find the paper online, e.g., DOI")
      String link;

      @Option(name = "editor", shortName = 'e', hasValue = false, description = "Also open the note in the configured editor")
      boolean editor;

      @Override
      public CommandResult execute(ZtkInvocation invocation) {
         // Checked before filing: failing afterwards would leave the paper filed without its note being opened.
         if (editor && !invocation.context().interactive()) {
            throw ZtkException.usage("Cannot open the editor: ztk is not running in an interactive session."
                  + System.lineSeparator()
                  + "Run the same 'ztk paper new' without --editor to file the paper and print its note's path.");
         }

         Papers.Details details = new Papers.Details(title, Optional.ofNullable(venue), Optional.ofNullable(year).map(Year::of),
               Optional.ofNullable(authors), Optional.ofNullable(link));

         // Relative to where ztk runs, as any path typed in a shell.
         Papers.Filed filed = invocation.context().papers().create(Path.of(pdf).toAbsolutePath(), details);

         // Human messages redirected to stderr, the result will go stdout.
         invocation.printErr("Filed paper '" + title + "' with its PDF at " + filed.pdf() + ".");
         invocation.result(filed.note() + System.lineSeparator(), filed.writeJson(invocation.jsonResult()));

         // The path is printed first, so it is visible even if the editor fails.
         if (editor) {
            Editor.open(invocation.context().configuration().editor(), filed.note());
         }
         return CommandResult.SUCCESS;
      }
   }
}
