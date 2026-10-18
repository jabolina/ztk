package io.github.jabolina.ztk.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.Year;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.jabolina.ztk.TestTaskwarrior;
import io.github.jabolina.ztk.ZtkException;
import io.github.jabolina.ztk.configuration.ConfigurationLoader;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Filing a paper: its PDF and its note, from the built-in template.
 */
class PapersTest {

   // A fixed instant, so date_read and the default year are predictable.
   private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-18T10:00:00-03:00");
   private static final byte[] PDF = "%PDF-1.4 not a real paper".getBytes();

   @TempDir
   Path root;

   TestTaskwarrior taskwarrior;
   Papers papers;

   @BeforeEach
   void setUp() {
      taskwarrior = TestTaskwarrior.create(root);
      papers = new Papers(ConfigurationLoader.load(taskwarrior.environment()),
            Clock.fixed(NOW.toInstant(), NOW.getOffset()));
   }

   @Test
   void filesACopyOfThePdfNextToANoteFromTheTemplate() throws IOException {
      // Anywhere outside the knowledge base, e.g. the downloads folder.
      Path download = Files.write(root.resolve("raft.pdf"), PDF);

      Papers.Filed filed = papers.create(download, new Papers.Details(
            "In Search of an Understandable Consensus Algorithm",
            Optional.of("ATC"),
            Optional.of(Year.of(2014)),
            Optional.of("Diego Ongaro, John Ousterhout"),
            Optional.of("https://www.usenix.org/conference/atc14/technical-sessions/presentation/ongaro")));

      // Filed by venue and year, named after the title; the original stays where it was.
      Path folder = taskwarrior.dataLocation().resolve("papers/ATC/2014");
      assertThat(filed.pdf()).isEqualTo(folder.resolve("in-search-of-an-understandable-consensus-algorithm.pdf"));
      assertThat(filed.note()).isEqualTo(folder.resolve("in-search-of-an-understandable-consensus-algorithm.md"));
      assertThat(filed.pdf()).hasBinaryContent(PDF);
      assertThat(download).hasBinaryContent(PDF);

      List<String> lines = Files.readAllLines(filed.note());
      int closing = lines.subList(1, lines.size()).indexOf("---") + 1;
      JsonNode frontmatter = YAMLMapper.shared().readTree(String.join("\n", lines.subList(1, closing)));
      // In this order, so every paper note reads the same.
      assertThat(frontmatter.propertyNames())
            .containsExactly("title", "authors", "venue", "link", "date_read", "pdf");
      assertThat(frontmatter.path("title").asString()).isEqualTo("In Search of an Understandable Consensus Algorithm");
      assertThat(frontmatter.path("authors").asString()).isEqualTo("Diego Ongaro, John Ousterhout");
      assertThat(frontmatter.path("venue").asString()).isEqualTo("ATC");
      assertThat(frontmatter.path("link").asString())
            .isEqualTo("https://www.usenix.org/conference/atc14/technical-sessions/presentation/ongaro");
      assertThat(frontmatter.path("date_read").asString()).isEqualTo("2026-10-18");
      // Relative to the note, so the pair can move together.
      assertThat(frontmatter.path("pdf").asString()).isEqualTo("in-search-of-an-understandable-consensus-algorithm.pdf");

      // The template's sections, in order, each with its guidance for whoever writes the summary.
      assertThat(lines.subList(closing + 1, lines.size()))
            .containsSubsequence(
                  "## 1. The TL;DR",
                  "## 2. The Gap (Problem Statement)",
                  "## 3. Key Definitions / Vocabulary",
                  "## 4. The Big Idea (Proposal)",
                  "## 5. Systems & Implementation",
                  "## 6. Benchmarks & Results",
                  "## 7. Limitations & Blind Spots",
                  "## 8. Project Relevance")
            .anyMatch(line -> line.startsWith("*Write this last."));
   }

   @Test
   void aPaperWithoutVenueOrYearIsFiledUnderOthersThisYearWithEmptyFields() throws IOException {
      Path download = Files.write(root.resolve("preprint.pdf"), PDF);

      Papers.Filed filed = papers.create(download, new Papers.Details(
            "A Preprint", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));

      // The year comes from the clock: 2026.
      assertThat(filed.note()).isEqualTo(taskwarrior.dataLocation().resolve("papers/others/2026/a-preprint.md"));
      JsonNode frontmatter = frontmatter(filed.note());
      // Every field is still there, empty, ready to be filled in.
      assertThat(frontmatter.propertyNames())
            .containsExactly("title", "authors", "venue", "link", "date_read", "pdf");
      assertThat(frontmatter.path("authors").asString()).isEmpty();
      assertThat(frontmatter.path("venue").asString()).isEmpty();
      assertThat(frontmatter.path("link").asString()).isEmpty();
   }

   @ParameterizedTest
   @ValueSource(strings = { "a-preprint.pdf", "a-preprint.md" })
   void anExistingPdfOrNoteAtTheTargetIsRejectedBeforeAnythingIsWritten(String existing) throws IOException {
      Path download = Files.write(root.resolve("preprint.pdf"), PDF);
      Path folder = Files.createDirectories(taskwarrior.dataLocation().resolve("papers/others/2026"));
      Path target = Files.writeString(folder.resolve(existing), "already filed");

      assertThatThrownBy(() -> papers.create(download, new Papers.Details(
            "A Preprint", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())))
            .isInstanceOfSatisfying(ZtkException.class,
                  e -> assertThat(e.exitCode()).isEqualTo(ZtkException.Kind.VALIDATION.exitCode()))
            .hasMessageContaining(target.toString())
            .hasMessageContaining("another title");

      // Neither half of the pair is written, and what was there is untouched.
      assertThat(folder.toFile().list()).containsExactly(existing);
      assertThat(target).hasContent("already filed");
   }

   @Test
   void aMissingPdfIsNotFoundAndNothingIsWritten() {
      Path missing = root.resolve("downloads/raft.pdf");

      assertThatThrownBy(() -> papers.create(missing, new Papers.Details(
            "A Preprint", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())))
            .isInstanceOfSatisfying(ZtkException.class,
                  e -> assertThat(e.exitCode()).isEqualTo(ZtkException.Kind.NOT_FOUND.exitCode()))
            .hasMessageContaining(missing.toString())
            .hasMessageContaining("Check the path");

      // Not even the venue and year folders.
      assertThat(taskwarrior.dataLocation().resolve("papers")).doesNotExist();
   }

   @ParameterizedTest
   @ValueSource(strings = { "ATC/2014", "..", "", ".drafts" })
   void aVenueThatIsNotASingleVisibleFolderNameIsRejected(String venue) throws IOException {
      Path download = Files.write(root.resolve("preprint.pdf"), PDF);

      // The venue is a folder under papers: it must not escape it, nest, or hide the paper from search.
      assertThatThrownBy(() -> papers.create(download, new Papers.Details(
            "A Preprint", Optional.of(venue), Optional.empty(), Optional.empty(), Optional.empty())))
            .isInstanceOfSatisfying(ZtkException.class,
                  e -> assertThat(e.exitCode()).isEqualTo(ZtkException.Kind.USAGE.exitCode()))
            .hasMessageContaining("'" + venue + "'")
            .hasMessageContaining("single folder name");

      assertThat(taskwarrior.dataLocation().resolve("papers")).doesNotExist();
      // ".." would have filed it straight into the knowledge base.
      assertThat(taskwarrior.dataLocation().resolve("2026")).doesNotExist();
   }

   @ParameterizedTest
   @ValueSource(strings = { "!!!", "   ", "分布式共识" })
   void aTitleWithNoLettersOrDigitsToNameTheFilesAfterIsRejected(String title) throws IOException {
      Path download = Files.write(root.resolve("preprint.pdf"), PDF);

      // The files are named after the title; without a name they would be the hidden ".pdf" and ".md".
      assertThatThrownBy(() -> papers.create(download, new Papers.Details(
            title, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())))
            .isInstanceOfSatisfying(ZtkException.class,
                  e -> assertThat(e.exitCode()).isEqualTo(ZtkException.Kind.USAGE.exitCode()))
            .hasMessageContaining("'" + title + "'")
            .hasMessageContaining("letters or digits");

      assertThat(taskwarrior.dataLocation().resolve("papers")).doesNotExist();
   }

   private static JsonNode frontmatter(Path note) throws IOException {
      List<String> lines = Files.readAllLines(note);
      int closing = lines.subList(1, lines.size()).indexOf("---") + 1;
      return YAMLMapper.shared().readTree(String.join("\n", lines.subList(1, closing)));
   }
}
