package io.github.jabolina.ztk.cli.commands;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandException;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.CompletionFallback;
import org.aesh.command.option.Option;
import org.aesh.command.option.OptionList;

import io.github.jabolina.ztk.ZtkException;
import io.github.jabolina.ztk.cli.ZtkInvocation;
import io.github.jabolina.ztk.cli.completers.NodeCompleter;
import io.github.jabolina.ztk.cli.completers.NoteTypeCompleter;
import io.github.jabolina.ztk.cli.converters.NoteTypeConverter;
import io.github.jabolina.ztk.cli.renderer.Table;
import io.github.jabolina.ztk.graph.Graph;
import io.github.jabolina.ztk.graph.KnowledgeGraph;
import io.github.jabolina.ztk.graph.Node;
import io.github.jabolina.ztk.graph.Reached;
import io.github.jabolina.ztk.notes.Note;
import io.github.jabolina.ztk.notes.NoteType;
import io.github.jabolina.ztk.notes.NoteWarning;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code ztk graph}: lists the nodes around a node, nearest first.
 *
 * <p>
 * Meant for agents first: the JSON is a flat list in which every node names the edge it was reached through, so the
 * way back to the start node can be followed.
 * </p>
 *
 * <pre>
 * ztk graph note:zettel/20261015-causality
 * ztk graph note:zettel/20261015-causality --json | jq -r '.nodes[].path'
 * ztk graph note:zettel/20261015-causality --depth 1
 * ztk graph task:3 --kind note --type zettel
 * ztk graph project:Alpha --edges project
 * ztk graph 20261015-causality --edges link --limit 100
 * </pre>
 *
 * @since 1.0
 * @author José Bolina
 */
@CommandDefinition(name = "graph", description = "List the nodes around a node, nearest first", generateHelp = true)
public final class GraphCommand implements Command<ZtkInvocation> {

   // One row per node, nearest first, ready to open by path.
   private static final Table<Reached> TABLE_RENDERER = Table.<Reached>builder()
         .column("DEPTH", reached -> String.valueOf(reached.depth()))
         .column("KIND", reached -> reached.node().kind())
         // As search shows it; only notes have a type.
         .column("TYPE", reached -> reached.node() instanceof Node.OfNote(Note note) ? note.type().label() : "")
         .column("TITLE", reached -> reached.node().title())
         .column("PATH", reached -> reached.node().path().map(Path::toString).orElse(""))
         .build();

   @Argument(description = "The node to start from: note:<path|name> or a name, task:<uuid|number> or a number, "
         + "or project:<name>", required = true, completer = NodeCompleter.class, completeFallback = CompletionFallback.NONE)
   String node;

   @Option(defaultValue = "2", description = "How many edges away from the node to go, e.g., 1 for its neighbors only")
   int depth;

   @OptionList(name = "kind", allowedValues = { "note", "task", "project", "dangling" },
         description = "Only walk through nodes of these kinds, comma-separated, e.g., 'note,task'")
   List<String> kinds;

   @OptionList(name = "type", converter = NoteTypeConverter.class, completer = NoteTypeCompleter.class,
         description = "Only walk through notes of these types, comma-separated, e.g., 'zettel,paper'")
   List<NoteType> types;

   @OptionList(name = "edges", allowedValues = { "link", "note", "project" },
         description = "Only follow edges of these kinds, comma-separated, e.g., 'link'")
   List<String> edges;

   // Enough for a neighborhood, few enough to read, and to fit an agent's context.
   @Option(defaultValue = "50", description = "List at most this many nodes, nearest first")
   int limit;

   @Override
   public CommandResult execute(ZtkInvocation invocation) throws CommandException, InterruptedException {
      if (depth < 0) {
         throw ZtkException.usage("The depth must be 0 or more, not " + depth
               + ". Use --depth 0 for the node alone, or --depth 1 for its neighbors.");
      }
      if (limit < 1) {
         throw ZtkException.usage("The limit must be 1 or more, not " + limit
               + ". To list fewer nodes, narrow the walk instead, e.g. with --depth 1.");
      }

      ObjectNode json = invocation.jsonResult();
      ArrayNode nodes = json.putArray("nodes");
      Consumer<NoteWarning> warnings = invocation.warnings(json);

      EnumSet<NoteType> wantedTypes = types == null ? EnumSet.noneOf(NoteType.class) : EnumSet.copyOf(types);
      Graph.Query query = new Graph.Query(node, depth, lowercase(kinds), wantedTypes, lowercase(edges));

      List<Reached> walked;
      // One more than the limit, to tell a cut list from one that fits exactly; the walk stops there.
      KnowledgeGraph knowledgeGraph = invocation.context().graph();
      knowledgeGraph.skipped().forEach(warnings);
      Graph graph = new Graph(knowledgeGraph);
      try (Stream<Reached> stream = graph.walk(query)) {
         walked = stream.limit(limit + 1L).toList();
      }
      boolean truncated = walked.size() > limit;
      List<Reached> reached = truncated ? walked.subList(0, limit) : walked;
      reached.forEach(each -> each.writeJson(nodes.addObject()));

      json.put("truncated", truncated);
      String hint = "Listed the nearest " + limit + " nodes only. Narrow the walk with --depth, --kind, --type or "
            + "--edges, or list more with --limit.";
      // Null rather than left out, so agents can rely on the key.
      json.put("hint", truncated ? hint : null);
      if (truncated && !invocation.context().json()) {
         invocation.printErr(hint);
      }
      // Rendered only in human mode.
      invocation.result(() -> TABLE_RENDERER.render(reached), json);
      return CommandResult.SUCCESS;
   }

   /**
    * Reads the values of a list option checked against its allowed values.
    *
    * @param values the values as typed, or null when the option is not given.
    * @return the values in lowercase, as Æsh accepts them in any case; empty when not given.
    */
   private static Set<String> lowercase(List<String> values) {
      return values == null
            ? Set.of()
            : values.stream().map(value -> value.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
   }
}
