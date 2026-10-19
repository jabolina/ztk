package io.github.jabolina.ztk.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.jabolina.ztk.CliHarness;
import io.github.jabolina.ztk.CliHarness.Result;
import io.github.jabolina.ztk.TestTaskwarrior;
import io.github.jabolina.ztk.ZtkException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Walking the graph of notes, tasks and projects from the command line.
 *
 * <p>
 * Notes are written straight into the fixture's knowledge base, as users and agents write them, rather than through
 * ztk: the graph is derived from whatever is there.
 * </p>
 */
class GraphCommandTest {

   private static final JsonMapper JSON = JsonMapper.shared();

   @TempDir
   Path root;

   TestTaskwarrior taskwarrior;

   @BeforeEach
   void setUp() {
      taskwarrior = TestTaskwarrior.create(root);
   }

   @Test
   void graphListsTheStartNoteThenTheNotesItLinksTo() throws IOException {
      Path causality = note("zettel/20261015-causality.md", """
            ---
            title: Causality
            ---

            Ordering events needs [[20261016-lamport-clocks]].
            """);
      Path clocks = note("zettel/20261016-lamport-clocks.md", """
            ---
            title: Lamport Clocks
            ---

            A counter per process.
            """);

      Result result = CliHarness.run(taskwarrior.environment(), false, "graph", "note:zettel/20261015-causality");

      assertThat(result.exitCode()).isZero();
      JsonNode json = JSON.readTree(result.out());
      assertThat(json.path("schema").asInt()).isEqualTo(1);
      JsonNode nodes = json.path("nodes");
      assertThat(nodes).hasSize(2);

      // The start node, reached by no edge.
      JsonNode start = nodes.get(0);
      assertThat(start.path("id").asString()).isEqualTo("note:zettel/20261015-causality");
      assertThat(start.path("type").asString()).isEqualTo("zettel");
      assertThat(start.path("title").asString()).isEqualTo("Causality");
      assertThat(start.path("path").asString()).isEqualTo(causality.toString());
      assertThat(start.path("depth").asInt()).isZero();
      assertThat(start.path("via").isNull()).isTrue();

      // One link away, with the edge that reached it: following "from" leads back to the start.
      JsonNode linked = nodes.get(1);
      assertThat(linked.path("id").asString()).isEqualTo("note:zettel/20261016-lamport-clocks");
      assertThat(linked.path("title").asString()).isEqualTo("Lamport Clocks");
      assertThat(linked.path("path").asString()).isEqualTo(clocks.toString());
      assertThat(linked.path("depth").asInt()).isEqualTo(1);
      assertThat(linked.path("via").path("from").asString()).isEqualTo("note:zettel/20261015-causality");
      assertThat(linked.path("via").path("edge").asString()).isEqualTo("link");
      assertThat(linked.path("via").path("direction").asString()).isEqualTo("out");

      // Always present, like search's.
      assertThat(json.path("warnings").isArray()).isTrue();
   }

   @Test
   void graphListsTheNotesLinkingToTheStartNoteAsIncomingLinks() throws IOException {
      Path causality = note("zettel/20261015-causality.md", """
            ---
            title: Causality
            ---

            Ordering events needs [[20261016-lamport-clocks]].
            """);
      note("zettel/20261016-lamport-clocks.md", """
            ---
            title: Lamport Clocks
            ---

            A counter per process.
            """);

      // The clocks note links nowhere: only the backlink reaches causality.
      Result result = CliHarness.run(taskwarrior.environment(), false, "graph", "note:zettel/20261016-lamport-clocks");

      assertThat(result.exitCode()).isZero();
      JsonNode nodes = JSON.readTree(result.out()).path("nodes");
      assertThat(nodes).hasSize(2);
      assertThat(nodes.get(0).path("id").asString()).isEqualTo("note:zettel/20261016-lamport-clocks");

      JsonNode linking = nodes.get(1);
      assertThat(linking.path("id").asString()).isEqualTo("note:zettel/20261015-causality");
      assertThat(linking.path("path").asString()).isEqualTo(causality.toString());
      assertThat(linking.path("depth").asInt()).isEqualTo(1);
      assertThat(linking.path("via").path("from").asString()).isEqualTo("note:zettel/20261016-lamport-clocks");
      assertThat(linking.path("via").path("edge").asString()).isEqualTo("link");
      // The start node is the one linked to.
      assertThat(linking.path("via").path("direction").asString()).isEqualTo("in");
   }

   @Test
   void aLinkLeadsOnlyToTheNoteOfThatNameInTheLinkingNotesOwnFolder() throws IOException {
      note("alpha/design.md", "Builds on the [[overview]].\n");
      Path alphaOverview = note("alpha/overview.md", "What Alpha is.\n");
      note("beta/overview.md", "What Beta is.\n");

      Result fromDesign = CliHarness.run(taskwarrior.environment(), false, "graph", "note:alpha/design");

      // Beta's overview shares the name, but the link never reaches it.
      assertThat(fromDesign.exitCode()).isZero();
      JsonNode nodes = JSON.readTree(fromDesign.out()).path("nodes");
      assertThat(nodes.valueStream().map(node -> node.path("id").asString()))
            .containsExactly("note:alpha/design", "note:alpha/overview");
      assertThat(nodes.get(1).path("path").asString()).isEqualTo(alphaOverview.toString());

      // Nor does the backlink: the design note links to Alpha's overview, not to Beta's.
      Result fromBeta = CliHarness.run(taskwarrior.environment(), false, "graph", "note:beta/overview");

      assertThat(fromBeta.exitCode()).isZero();
      assertThat(JSON.readTree(fromBeta.out()).path("nodes").valueStream().map(node -> node.path("id").asString()))
            .containsExactly("note:beta/overview");
   }

   @Test
   void depthSetsHowManyEdgesAwayTheWalkGoesTwoByDefault() throws IOException {
      // A chain: each note links to the next.
      note("zettel/a.md", "[[b]]\n");
      note("zettel/b.md", "[[c]]\n");
      note("zettel/c.md", "[[d]]\n");
      note("zettel/d.md", "The end.\n");

      Result byDefault = CliHarness.run(taskwarrior.environment(), false, "graph", "note:zettel/a");
      Result deeper = CliHarness.run(taskwarrior.environment(), false, "graph", "note:zettel/a", "--depth", "3");
      Result startOnly = CliHarness.run(taskwarrior.environment(), false, "graph", "note:zettel/a", "--depth", "0");

      assertThat(ids(byDefault)).containsExactly("note:zettel/a", "note:zettel/b", "note:zettel/c");
      assertThat(ids(deeper)).containsExactly("note:zettel/a", "note:zettel/b", "note:zettel/c", "note:zettel/d");
      assertThat(ids(startOnly)).containsExactly("note:zettel/a");
   }

   @ParameterizedTest
   @ValueSource(strings = { "-1", "-5" })
   void aNegativeDepthIsRejected(String depth) throws IOException {
      note("zettel/a.md", "[[b]]\n");

      Result result = CliHarness.run(taskwarrior.environment(), false, "graph", "note:zettel/a", "--depth", depth);

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.USAGE.exitCode());
      assertThat(result.out()).isEmpty();
      assertThat(result.err()).contains(depth).contains("0 or more");
   }

   @Test
   void aTaskNoteLeadsToItsTask() throws IOException {
      // As ztk start leaves them: the task names its note, and the note names its task.
      taskwarrior.task("rc.uda.note.type=string", "add", "Fix bug", "project:Alpha", "note:Alpha/fix-bug.md");
      String uuid = taskwarrior.task("_get", "1.uuid");
      Path fixBug = note("Alpha/fix-bug.md", """
            ---
            title: Fix bug
            task_uuid: %s
            ---

            # Notes
            """.formatted(uuid));

      // One edge away only: the task's project is a further edge.
      Result result = CliHarness.run(taskwarrior.environment(), false, "graph", "note:Alpha/fix-bug", "--depth", "1");

      assertThat(result.exitCode()).isZero();
      JsonNode nodes = JSON.readTree(result.out()).path("nodes");
      assertThat(nodes).hasSize(2);

      // A note node has its kind, and its type as search prints it.
      JsonNode start = nodes.get(0);
      assertThat(start.path("kind").asString()).isEqualTo("note");
      assertThat(start.path("type").asString()).isEqualTo("task");

      JsonNode task = nodes.get(1);
      assertThat(task.path("id").asString()).isEqualTo("task:" + uuid);
      assertThat(task.path("kind").asString()).isEqualTo("task");
      // Tasks have no note type.
      assertThat(task.has("type")).isFalse();
      assertThat(task.path("title").asString()).isEqualTo("Fix bug");
      // Where to read and write about the task.
      assertThat(task.path("path").asString()).isEqualTo(fixBug.toString());
      assertThat(task.path("status").asString()).isEqualTo("pending");
      assertThat(task.path("depth").asInt()).isEqualTo(1);
      assertThat(task.path("via").path("from").asString()).isEqualTo("note:Alpha/fix-bug");
      assertThat(task.path("via").path("edge").asString()).isEqualTo("note");
      // The task holds the edge, pointing to its note.
      assertThat(task.path("via").path("direction").asString()).isEqualTo("in");
   }

   @Test
   void aTaskNoteNamingItsTaskOnlyInItsFrontmatterLeadsToItsTask() throws IOException {
      // As the bash script left them: the note names its task, but the task has no note attribute.
      taskwarrior.task("add", "Fix bug", "project:Alpha");
      String uuid = taskwarrior.task("_get", "1.uuid");
      Path fixBug = note("Alpha/fix-bug.md", """
            ---
            title: Fix bug
            task_uuid: %s
            ---

            # Notes
            """.formatted(uuid));

      // One edge away only: the task's project is a further edge.
      Result result = CliHarness.run(taskwarrior.environment(), false, "graph", "note:Alpha/fix-bug", "--depth", "1");

      assertThat(result.exitCode()).isZero();
      JsonNode nodes = JSON.readTree(result.out()).path("nodes");
      assertThat(nodes).hasSize(2);
      JsonNode task = nodes.get(1);
      assertThat(task.path("id").asString()).isEqualTo("task:" + uuid);
      assertThat(task.path("path").asString()).isEqualTo(fixBug.toString());
      assertThat(task.path("via").path("edge").asString()).isEqualTo("note");
      assertThat(task.path("via").path("direction").asString()).isEqualTo("in");
   }

   @Test
   void aTaskLeadsToItsProjectWhichLeadsNowhereElse() throws IOException {
      taskwarrior.task("rc.uda.note.type=string", "add", "Fix bug", "project:Alpha", "note:Alpha/fix-bug.md");
      String uuid = taskwarrior.task("_get", "1.uuid");
      taskwarrior.task("add", "Write docs", "project:Alpha");
      note("Alpha/fix-bug.md", """
            ---
            title: Fix bug
            task_uuid: %s
            ---

            # Notes
            """.formatted(uuid));

      // Deep enough to go past the project, if it led anywhere.
      Result result = CliHarness.run(taskwarrior.environment(), false, "graph", "note:Alpha/fix-bug", "--depth", "3");

      // A project is a hub: going through it would list every task of the project, which says nothing about this one.
      assertThat(ids(result)).containsExactly("note:Alpha/fix-bug", "task:" + uuid, "project:Alpha");
      JsonNode project = JSON.readTree(result.out()).path("nodes").get(2);
      assertThat(project.path("kind").asString()).isEqualTo("project");
      assertThat(project.path("title").asString()).isEqualTo("Alpha");
      assertThat(project.path("depth").asInt()).isEqualTo(2);
      assertThat(project.path("via").path("from").asString()).isEqualTo("task:" + uuid);
      assertThat(project.path("via").path("edge").asString()).isEqualTo("project");
      // The task holds the edge, pointing to its project.
      assertThat(project.path("via").path("direction").asString()).isEqualTo("out");
   }

   @Test
   void aProjectsPathIsItsFolderWhenItHasOne() throws IOException {
      taskwarrior.task("rc.uda.note.type=string", "add", "Fix bug", "project:Alpha", "note:Alpha/fix-bug.md");
      // Renamed in Taskwarrior after its note was written: no folder is named after it.
      taskwarrior.task("rc.uda.note.type=string", "add", "Ship it", "project:Beta", "note:Alpha/ship-it.md");
      note("Alpha/fix-bug.md", "[[ship-it]]\n");
      note("Alpha/ship-it.md", "Released.\n");

      // Alpha is two edges away, through its task; Beta three, through the linked note and its task.
      Result result = CliHarness.run(taskwarrior.environment(), false, "graph", "note:Alpha/fix-bug", "--depth", "3");

      assertThat(result.exitCode()).isZero();
      JsonNode nodes = JSON.readTree(result.out()).path("nodes");
      JsonNode alpha = node(nodes, "project:Alpha");
      // Where the project's notes live.
      assertThat(alpha.path("path").asString()).isEqualTo(taskwarrior.dataLocation().resolve("Alpha").toString());
      JsonNode beta = node(nodes, "project:Beta");
      assertThat(beta.has("path")).isTrue();
      assertThat(beta.path("path").isNull()).isTrue();
   }

   @Test
   void startingFromAProjectListsItsTasks() {
      taskwarrior.task("add", "Fix bug", "project:Alpha");
      taskwarrior.task("add", "Write docs", "project:Alpha");
      taskwarrior.task("add", "Elsewhere", "project:Beta");
      String fixBug = taskwarrior.task("_get", "1.uuid");
      String writeDocs = taskwarrior.task("_get", "2.uuid");

      Result result = CliHarness.run(taskwarrior.environment(), false, "graph", "project:Alpha", "--depth", "1");

      // Only as the start node is a project expanded, even to tasks without a note.
      assertThat(ids(result)).containsExactlyInAnyOrder("project:Alpha", "task:" + fixBug, "task:" + writeDocs);
      JsonNode task = node(JSON.readTree(result.out()).path("nodes"), "task:" + fixBug);
      assertThat(task.path("via").path("from").asString()).isEqualTo("project:Alpha");
      assertThat(task.path("via").path("edge").asString()).isEqualTo("project");
      // The task holds the edge.
      assertThat(task.path("via").path("direction").asString()).isEqualTo("in");
   }

   @Test
   void aTaskIsFoundByItsUuidItsShortUuidOrItsNumber() {
      taskwarrior.task("add", "Fix bug", "project:Alpha");
      String uuid = taskwarrior.task("_get", "1.uuid");
      // Done tasks lose their number: only the UUIDs find them.
      taskwarrior.task("add", "Shipped", "project:Alpha");
      String done = taskwarrior.task("_get", "2.uuid");
      taskwarrior.task("rc.confirmation=off", "2", "done");

      for (String form : List.of("task:" + uuid, "task:" + uuid.substring(0, 8), "task:1", "1")) {
         Result result = CliHarness.run(taskwarrior.environment(), false, "graph", form, "--depth", "0");
         assertThat(ids(result)).as(form).containsExactly("task:" + uuid);
      }
      Result shortDone = CliHarness.run(taskwarrior.environment(), false, "graph", "task:" + done.substring(0, 8),
            "--depth", "0");
      assertThat(ids(shortDone)).containsExactly("task:" + done);
   }

   @Test
   void aShortUuidOfDigitsOnlyStillFindsItsTask() throws IOException {
      // One UUID in about forty starts with eight digits, which also read as a task number.
      Path imported = Files.writeString(root.resolve("task.json"), """
            [{"uuid": "12345678-aaaa-4bbb-8ccc-1234567890ab", "description": "Fix bug", "status": "pending",
              "project": "Alpha"}]
            """);
      taskwarrior.task("import", imported.toString());

      Result result = CliHarness.run(taskwarrior.environment(), false, "graph", "task:12345678", "--depth", "0");

      assertThat(ids(result)).containsExactly("task:12345678-aaaa-4bbb-8ccc-1234567890ab");
   }

   @Test
   void aTaskNumberNoTaskHasIsNotFound() {
      taskwarrior.task("add", "Fix bug", "project:Alpha");

      Result result = CliHarness.run(taskwarrior.environment(), false, "graph", "task:7");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.NOT_FOUND.exitCode());
      assertThat(result.out()).isEmpty();
      // Where to find the numbers.
      assertThat(result.err()).contains("task:7").contains("task list");
   }

   @Test
   void aNoteIsFoundByItsPathOrByItsName() throws IOException {
      note("zettel/20261016-lamport-clocks.md", "A counter per process.\n");
      note("index.md", "The root.\n");
      note("zettel/index.md", "The zettels.\n");

      for (String form : List.of("note:zettel/20261016-lamport-clocks", "note:20261016-lamport-clocks",
            "20261016-lamport-clocks")) {
         Result result = CliHarness.run(taskwarrior.environment(), false, "graph", form, "--depth", "0");
         assertThat(ids(result)).as(form).containsExactly("note:zettel/20261016-lamport-clocks");
      }
      // As a wikilink written at the root: a note of that name there wins.
      Result index = CliHarness.run(taskwarrior.environment(), false, "graph", "index", "--depth", "0");
      assertThat(ids(index)).containsExactly("note:index");
   }

   @Test
   void aNameSeveralNotesHaveListsTheirIds() throws IOException {
      note("zettel/overview.md", "Concepts.\n");
      note("Alpha/overview.md", "What Alpha is.\n");

      Result result = CliHarness.run(taskwarrior.environment(), false, "graph", "overview");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.VALIDATION.exitCode());
      assertThat(result.out()).isEmpty();
      // Ready to run again with one of them.
      assertThat(result.err()).contains("note:Alpha/overview, note:zettel/overview");
   }

   @Test
   void aNameNoNoteHasIsNotFound() {
      Result result = CliHarness.run(taskwarrior.environment(), false, "graph", "missing");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.NOT_FOUND.exitCode());
      assertThat(result.err()).contains("missing").contains("ztk search");
   }

   @Test
   void aWikilinkToATaskUuidLeadsToTheTask() throws IOException {
      taskwarrior.task("add", "Fix bug", "project:Alpha");
      String uuid = taskwarrior.task("_get", "1.uuid");
      note("zettel/causality.md", "Applied in [[" + uuid + "]].\n");
      note("zettel/clocks.md", "Also in [[" + uuid.substring(0, 8) + "|the fix]].\n");

      Result fromNote = CliHarness.run(taskwarrior.environment(), false, "graph", "causality", "--depth", "1");

      assertThat(ids(fromNote)).containsExactly("note:zettel/causality", "task:" + uuid);
      JsonNode task = node(JSON.readTree(fromNote.out()).path("nodes"), "task:" + uuid);
      assertThat(task.path("via").path("edge").asString()).isEqualTo("link");
      assertThat(task.path("via").path("direction").asString()).isEqualTo("out");

      // And back: from the task, both notes link to it, by its full or its short UUID; its project is there too.
      Result fromTask = CliHarness.run(taskwarrior.environment(), false, "graph", "task:1", "--depth", "1");

      assertThat(ids(fromTask)).containsExactlyInAnyOrder(
            "task:" + uuid, "project:Alpha", "note:zettel/causality", "note:zettel/clocks");
      JsonNode clocks = node(JSON.readTree(fromTask.out()).path("nodes"), "note:zettel/clocks");
      assertThat(clocks.path("via").path("edge").asString()).isEqualTo("link");
      assertThat(clocks.path("via").path("direction").asString()).isEqualTo("in");
   }

   @Test
   void aWikilinkToNothingIsADanglingNodeLeadingNowhere() throws IOException {
      note("zettel/causality.md", "See [[20261020-vector-clocks]] and the task [[deadbeef]].\n");
      // Links to the same missing note: reachable only by walking through it.
      note("zettel/clocks.md", "Also [[20261020-vector-clocks]].\n");

      Result result = CliHarness.run(taskwarrior.environment(), false, "graph", "causality");

      // A target shaped as a task UUID that no task has dangles too.
      assertThat(ids(result)).containsExactlyInAnyOrder(
            "note:zettel/causality", "dangling:20261020-vector-clocks", "dangling:deadbeef");
      JsonNode dangling = node(JSON.readTree(result.out()).path("nodes"), "dangling:20261020-vector-clocks");
      assertThat(dangling.path("kind").asString()).isEqualTo("dangling");
      // The name to give the note when writing it.
      assertThat(dangling.path("title").asString()).isEqualTo("20261020-vector-clocks");
      assertThat(dangling.has("path")).isTrue();
      assertThat(dangling.path("path").isNull()).isTrue();
      assertThat(dangling.path("depth").asInt()).isEqualTo(1);
      assertThat(dangling.path("via").path("from").asString()).isEqualTo("note:zettel/causality");
      assertThat(dangling.path("via").path("edge").asString()).isEqualTo("link");
      assertThat(dangling.path("via").path("direction").asString()).isEqualTo("out");
   }

   @Test
   void kindRestrictsTheWalkToThoseKindsOfNodes() throws IOException {
      taskwarrior.task("rc.uda.note.type=string", "add", "Fix bug", "project:Alpha", "note:Alpha/fix-bug.md");
      note("Alpha/fix-bug.md", "Uses [[clocks]].\n");
      note("zettel/clocks.md", "A counter per process.\n");

      // The project is only reachable through the task, which is left out: the walk does not pass through it.
      Result result = CliHarness.run(taskwarrior.environment(), false, "graph", "note:Alpha/fix-bug",
            "--kind", "note,project");

      assertThat(ids(result)).containsExactly("note:Alpha/fix-bug", "note:zettel/clocks");
   }

   @Test
   void typeRestrictsTheWalkToThoseTypesOfNotes() throws IOException {
      taskwarrior.task("add", "Fix bug", "project:Alpha");
      String uuid = taskwarrior.task("_get", "1.uuid");
      note("zettel/causality.md", "See [[clocks]], [[raft]] and [[" + uuid + "]].\n");
      note("zettel/clocks.md", "A counter per process.\n");
      note("papers/ATC/2014/raft.md", "Consensus.\n");

      Result result = CliHarness.run(taskwarrior.environment(), false, "graph", "causality", "--depth", "1",
            "--type", "zettel");

      // Note types, as search has them: other kinds of nodes are not notes, and stay.
      assertThat(ids(result)).containsExactlyInAnyOrder("note:zettel/causality", "note:zettel/clocks", "task:" + uuid);
   }

   @Test
   void edgesRestrictsTheWalkToThoseKindsOfEdges() throws IOException {
      taskwarrior.task("rc.uda.note.type=string", "add", "Fix bug", "project:Alpha", "note:Alpha/fix-bug.md");
      note("Alpha/fix-bug.md", "Uses [[clocks]].\n");
      note("zettel/clocks.md", "A counter per process.\n");

      Result result = CliHarness.run(taskwarrior.environment(), false, "graph", "note:Alpha/fix-bug",
            "--edges", "link");

      // Through wikilinks only: neither the task, through its note edge, nor its project.
      assertThat(ids(result)).containsExactly("note:Alpha/fix-bug", "note:zettel/clocks");
   }

   @Test
   void anUnknownKindIsRejectedListingTheKnownOnes() throws IOException {
      note("zettel/a.md", "A.\n");

      Result result = CliHarness.run(taskwarrior.environment(), false, "graph", "note:zettel/a", "--kind", "tasks");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.USAGE.exitCode());
      assertThat(result.out()).isEmpty();
      assertThat(result.err()).contains("'tasks'").contains("note, task, project, dangling");
   }

   @Test
   void limitCapsTheNodesListedAndSaysTheListIsCut() throws IOException {
      note("zettel/hub.md", "[[a]] [[b]] [[c]] [[d]]\n");
      for (String name : List.of("a", "b", "c", "d")) {
         note("zettel/" + name + ".md", name + "\n");
      }

      Result cut = CliHarness.run(taskwarrior.environment(), false, "graph", "hub", "--limit", "3");
      Result whole = CliHarness.run(taskwarrior.environment(), false, "graph", "hub", "--limit", "5");

      // The nearest first, so the start node is always kept.
      assertThat(ids(cut)).hasSize(3).first().isEqualTo("note:zettel/hub");
      JsonNode json = JSON.readTree(cut.out());
      assertThat(json.path("truncated").asBoolean()).isTrue();
      assertThat(json.path("hint").asString()).contains("--depth").contains("--limit");

      // Exactly at the limit: nothing is cut.
      assertThat(ids(whole)).hasSize(5);
      JsonNode wholeJson = JSON.readTree(whole.out());
      assertThat(wholeJson.path("truncated").asBoolean()).isFalse();
      assertThat(wholeJson.path("hint").isNull()).isTrue();
   }

   @Test
   void aLimitBelowOneIsRejected() throws IOException {
      note("zettel/a.md", "A.\n");

      Result result = CliHarness.run(taskwarrior.environment(), false, "graph", "note:zettel/a", "--limit", "0");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.USAGE.exitCode());
      assertThat(result.out()).isEmpty();
      assertThat(result.err()).contains("1 or more").contains("--depth");
   }

   @Test
   void peopleSeeATableOfTheNodesNearestFirst() throws IOException {
      taskwarrior.task("rc.uda.note.type=string", "add", "Fix bug", "project:Alpha", "note:Alpha/fix-bug.md");
      String uuid = taskwarrior.task("_get", "1.uuid");
      Path fixBug = note("Alpha/fix-bug.md", """
            ---
            title: Fix bug
            task_uuid: %s
            ---
            """.formatted(uuid));

      Result result = CliHarness.run(taskwarrior.environment(), true, "graph", "note:Alpha/fix-bug");

      assertThat(result.exitCode()).isZero();
      // A note's type next to its kind, as search shows it; nothing for nodes that are not notes.
      assertThat(result.out()).isEqualTo("""
            DEPTH  KIND     TYPE  TITLE    PATH
            0      note     task  Fix bug  %s
            1      task           Fix bug  %s
            2      project        Alpha    %s
            """.formatted(fixBug, fixBug, taskwarrior.dataLocation().resolve("Alpha")));
   }

   @Test
   void peopleAreToldOnStderrWhenTheListIsCut() throws IOException {
      note("zettel/a.md", "[[b]]\n");
      note("zettel/b.md", "B.\n");

      Result result = CliHarness.run(taskwarrior.environment(), true, "graph", "a", "--limit", "1");

      assertThat(result.exitCode()).isZero();
      assertThat(result.err()).contains("nearest 1 nodes").contains("--limit");
   }

   /**
    * Finds a node in a graph run's output.
    *
    * @param nodes the {@code nodes} array.
    * @param id the node ID.
    * @return the node with that ID.
    */
   private static JsonNode node(JsonNode nodes, String id) {
      return nodes.valueStream()
            .filter(node -> node.path("id").asString().equals(id))
            .findFirst()
            .orElseThrow(() -> new AssertionError(id + " not in " + nodes));
   }

   /**
    * Lists the node IDs a graph run printed.
    *
    * @param result a successful graph run.
    * @return the IDs of its nodes, in order.
    */
   private static List<String> ids(Result result) {
      assertThat(result.exitCode()).isZero();
      return JSON.readTree(result.out()).path("nodes").valueStream().map(node -> node.path("id").asString()).toList();
   }

   /**
    * Writes a note into the knowledge base, creating its folders.
    *
    * @param relative the note's path relative to {@code data.location}, e.g. {@code zettel/20261015-causality.md}.
    * @param content the whole file, frontmatter included.
    * @return the absolute path of the note.
    * @throws IOException when the note cannot be written.
    */
   private Path note(String relative, String content) throws IOException {
      Path note = taskwarrior.dataLocation().resolve(relative);
      Files.createDirectories(note.getParent());
      return Files.writeString(note, content);
   }
}
