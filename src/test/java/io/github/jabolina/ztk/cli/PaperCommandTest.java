package io.github.jabolina.ztk.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jabolina.ztk.CliHarness;
import io.github.jabolina.ztk.CliHarness.Result;
import io.github.jabolina.ztk.TestTaskwarrior;
import io.github.jabolina.ztk.ZtkException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Filing papers from the command line.
 */
class PaperCommandTest {

   private static final JsonMapper JSON = JsonMapper.shared();
   private static final byte[] PDF = "%PDF-1.4 not a real paper".getBytes();

   @TempDir
   Path root;

   TestTaskwarrior taskwarrior;
   Path download;

   @BeforeEach
   void setUp() throws IOException {
      taskwarrior = TestTaskwarrior.create(root);
      // Anywhere outside the knowledge base, e.g. the downloads folder.
      download = Files.write(Files.createDirectories(root.resolve("downloads")).resolve("raft.pdf"), PDF);
   }

   @Test
   void paperNewPrintsTheNoteIdPathAndPdfAsJsonWhenPiped() throws IOException {
      Result result = CliHarness.run(taskwarrior.environment(), false, "paper", "new", download.toString(),
            "--title", "In Search of an Understandable Consensus Algorithm",
            "--venue", "ATC",
            "--year", "2014",
            "--authors", "Diego Ongaro, John Ousterhout",
            "--link", "https://www.usenix.org/conference/atc14/technical-sessions/presentation/ongaro");

      assertThat(result.exitCode()).isZero();
      JsonNode json = JSON.readTree(result.out());
      assertThat(json.path("schema").asInt()).isEqualTo(1);
      // The same node ID search and graph print, so the result can be fed straight back to them.
      assertThat(json.path("id").asString())
            .isEqualTo("note:papers/ATC/2014/in-search-of-an-understandable-consensus-algorithm");
      Path folder = taskwarrior.dataLocation().resolve("papers/ATC/2014");
      Path note = folder.resolve("in-search-of-an-understandable-consensus-algorithm.md");
      Path pdf = folder.resolve("in-search-of-an-understandable-consensus-algorithm.pdf");
      assertThat(json.path("path").asString()).isEqualTo(note.toString());
      assertThat(json.path("pdf").asString()).isEqualTo(pdf.toString());
      assertThat(pdf).hasBinaryContent(PDF);
      // Every option reached the note.
      String content = Files.readString(note);
      JsonNode frontmatter = YAMLMapper.shared().readTree(content.substring(4, content.indexOf("\n---\n", 4)));
      assertThat(frontmatter.path("title").asString()).isEqualTo("In Search of an Understandable Consensus Algorithm");
      assertThat(frontmatter.path("authors").asString()).isEqualTo("Diego Ongaro, John Ousterhout");
      assertThat(frontmatter.path("venue").asString()).isEqualTo("ATC");
      assertThat(frontmatter.path("link").asString())
            .isEqualTo("https://www.usenix.org/conference/atc14/technical-sessions/presentation/ongaro");
   }

   @Test
   void paperNewWithEditorOpensTheNoteInTheConfiguredEditor() {
      Path editorArguments = taskwarrior.recordingEditor();

      Result result = CliHarness.run(taskwarrior.environment(), true, "paper", "new", download.toString(),
            "--title", "Raft", "-e");

      assertThat(result.exitCode()).isZero();
      // The note, not the PDF: the note is where the summary gets written.
      Path note = Path.of(result.out().strip());
      assertThat(note.getFileName().toString()).isEqualTo("raft.md");
      assertThat(editorArguments).content().isEqualTo(note + "\n");
   }

   @Test
   void paperNewWithEditorOutsideAnInteractiveSessionFailsBeforeWriting() {
      Result result = CliHarness.run(taskwarrior.environment(), false, "paper", "new", download.toString(),
            "--title", "Raft", "--editor");

      assertThat(result.exitCode()).isEqualTo(ZtkException.Kind.USAGE.exitCode());
      assertThat(result.out()).isEmpty();
      // Points to the same command without the flag.
      assertThat(result.err()).contains("without --editor");
      assertThat(taskwarrior.dataLocation().resolve("papers")).doesNotExist();
   }
}
