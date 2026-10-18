package io.github.jabolina.ztk.cli.commands;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Arguments;
import org.aesh.command.option.Option;
import org.aesh.command.option.OptionList;

import io.github.jabolina.ztk.cli.ArgumentGuard;
import io.github.jabolina.ztk.cli.ProjectCompleter;
import io.github.jabolina.ztk.cli.ZtkInvocation;
import io.github.jabolina.ztk.cli.converters.NoteTypeConverter;
import io.github.jabolina.ztk.cli.renderer.Table;
import io.github.jabolina.ztk.notes.NoteType;
import io.github.jabolina.ztk.notes.NoteWarning;
import io.github.jabolina.ztk.search.Match;
import io.github.jabolina.ztk.search.Search;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code ztk search}: lists the notes containing every word.
 *
 * <p>
 * Each argument is one term; quoting words makes them a phrase. Matching ignores case and covers the title and the
 * body of every note.
 * </p>
 *
 * <pre>
 * ztk search lamport
 * ztk search "lamport clocks" paxos
 * ztk search consensus --json | jq -r '.results[].path'
 * ztk search raft --project Alpha
 * </pre>
 *
 * @since 1.0
 * @author José Bolina
 */
@CommandDefinition(name = "search", description = "List the notes containing every word", generateHelp = true)
public class SearchCommand implements Command<ZtkInvocation> {

   // What a person sees per match: one row, then its matching lines, ready for nvim +<line> <path>.
   private static final Table<Match> TABLE_RENDERER = Table.<Match>builder()
         .column("TYPE", match -> match.note().type().label())
         .column("TITLE", match -> match.note().header().title())
         .column("PATH", match -> match.note().header().path().toString())
         .details(match -> match.lines().stream().map(line -> line.number() + ": " + line.text()).toList())
         .build();

   @Arguments(description = "The words to find; quote words to find them as a phrase, e.g., 'lamport clocks'",
         validator = ArgumentGuard.class, required = true)
   List<String> words;

   @OptionList(name = "type", description = "Only search notes of these types, comma-separated, e.g., 'zetel,paper'",
         converter = NoteTypeConverter.class)
   List<NoteType> types;

   @Option(name = "project", completer = ProjectCompleter.class, description = "Only search the notes of this project, e.g., 'Alpha'")
   String project;

   @Override
   public CommandResult execute(ZtkInvocation invocation) {
      // The `type` argument remains null when nothing is provided.
      EnumSet<NoteType> wanted = types == null
            ? EnumSet.noneOf(NoteType.class)
            : EnumSet.copyOf(types);

      Search.Query query = new Search.Query(words, wanted, Optional.ofNullable(project));

      ObjectNode json = invocation.jsonResult();
      ArrayNode results = json.putArray("results");
      // Always present, so agents can rely on the key. People see the warnings on stderr instead.
      ArrayNode warnings = json.putArray("warnings");
      Consumer<NoteWarning> warningConsumer = warning -> {
         warning.writeJson(warnings.addObject());
         if (!invocation.context().json()) {
            invocation.printErr("warning: " + warning.path() + ": " + warning.message());
         }
      };

      List<Match> matches;
      // Must ensure the search stream is closed.
      try (Stream<Match> found = invocation.context().search().find(query, warningConsumer)) {
         // At this point, everything should be already be filtered.
         // We won't be loading the full knowledge base into memory.
         matches = found.toList();
      }
      matches.forEach(match -> match.writeJson(results.addObject()));
      // Rendered only in human mode.
      invocation.result(() -> TABLE_RENDERER.render(matches), json);
      return CommandResult.SUCCESS;
   }
}
