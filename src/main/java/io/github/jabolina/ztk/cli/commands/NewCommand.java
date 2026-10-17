package io.github.jabolina.ztk.cli.commands;

import java.util.List;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Arguments;
import org.aesh.command.option.Option;

import io.github.jabolina.ztk.ZtkException;
import io.github.jabolina.ztk.cli.ArgumentGuard;
import io.github.jabolina.ztk.cli.Editor;
import io.github.jabolina.ztk.cli.ZtkInvocation;
import io.github.jabolina.ztk.notes.Zettel;

/**
 * {@code ztk new}: creates a zettel and prints its path.
 *
 * <p>
 * The words form the title, so quoting it is optional. Only the path goes to stdout, which makes the command usable in
 * shell substitutions. A title already used on the same day gets a numbered file, e.g. {@code -2.md}, so no zettel
 * is overwritten.
 * </p>
 *
 * <pre>
 * ztk new Causality and Time
 * ztk new Causality and Time -e
 * nvim "$(ztk new "Causality and Time")"
 * </pre>
 *
 * @since 1.0
 * @author José Bolina
 */
@CommandDefinition(name = "new", description = "Creates a Zettelkasten note and print its path", generateHelp = true)
public class NewCommand implements Command<ZtkInvocation> {

   @Arguments(description = "The Zettelkasten title, e.g., 'Causality and Time'", validator = ArgumentGuard.class,
         required = true)
   List<String> words;

   @Option(name = "editor", shortName = 'e', hasValue = false, description = "Also open the zettel in the configuration editor")
   boolean editor;

   @Override
   public CommandResult execute(ZtkInvocation invocation) {
      String title = String.join(" ", words);
      // Checked before writing: failing afterwards would leave the zettel created without it being opened.
      if (editor && !invocation.context().interactive()) {
         throw ZtkException.usage("Cannot open the editor: ztk is not running in an interactive session."
               + System.lineSeparator()
               + "Run 'ztk new " + title + "' without --editor to create the zettel and print its path.");
      }

      Zettel zettel = invocation.context().zettels().create(title);

      // Human messages redirected to stderr, the result will go stdout.
      invocation.printErr("Created zettel '" + title + "'.");
      invocation.result(zettel.path() + System.lineSeparator(), zettel.writeJson(invocation.jsonResult()));

      // The path is printed first, so it is visible even if the editor fails.
      if (editor) {
         Editor.open(invocation.context().configuration().editor(), zettel.path());
      }
      return CommandResult.SUCCESS;
   }
}
