package io.github.jabolina.ztk.notes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Year;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;

import io.github.jabolina.ztk.ZtkException;
import io.github.jabolina.ztk.configuration.Configuration;
import io.github.jabolina.ztk.util.JsonWritable;
import io.github.jabolina.ztk.util.Slugs;
import tools.jackson.databind.node.ObjectNode;

/**
 * Files papers: a copy of the PDF and a note to summarize it, side by side in the papers folder.
 *
 * <p>
 * Papers are filed by venue and year, e.g. {@code papers/ATC/2014/}, and both files are named after the title. The
 * note comes from a template built into ztk, so every paper note has the same fields and sections.
 * </p>
 *
 * @since 1.0
 * @author José Bolina
 */
public final class Papers {

   // The folder for papers without a venue, e.g., a preprint.
   private static final String NO_VENUE = "others";

   // Sections for summarizing a paper, each with guidance in italics for whoever writes it, person or agent.
   // The guidance only says what a section holds: examples would name real topics and match searches for them.
   private static final String TEMPLATE = """
         ## 1. The TL;DR
         *Write this last. Summarize the entire paper in one or two sentences.*
         >

         ## 2. The Gap (Problem Statement)
         *What is broken in the current state of the art, and why does this paper exist? Name the specific failure and the conditions under which it happens.*
         *

         ## 3. Key Definitions / Vocabulary
         *Terms and concepts that were new or used in a non-obvious way, defined in your own words. Skip this section for domains you already know well.*
         *

         ## 4. The Big Idea (Proposal)
         *What is the novel insight, and how does it solve the problem above? One idea, stated so it could be explained without the paper.*
         *

         ## 5. Systems & Implementation
         *How did they actually build it? Which algorithms, data structures, or protocol changes did they use?*
         * **Architecture:** *the main components and how they are layered or connected.*
         * **Data Structures:** *the state the system keeps, and where.*
         * **Trade-offs Made:** *what they gave up, and what they got for it.*

         ## 6. Benchmarks & Results
         *What are the hard numbers, and under which workloads does the system shine?*
         * **Throughput:**
         * **Tail Latency (p99/p99.9):**
         * **Hardware Setup:** *machines, cluster size, and network.*
         * **What was NOT measured:** *the conditions the evaluation leaves out.*

         ## 7. Limitations & Blind Spots
         *Put your reviewer hat on. What did they ignore? Are the workloads realistic? Would it hold outside their environment, e.g. over a WAN?*
         *

         ## 8. Project Relevance
         *How does this apply to your own projects?*
         * **Actionable Ideas:** *what to try, and where.*
         * **Concepts that map (and where the mapping breaks):** *ideas with a counterpart in your projects, and how they differ.*
         * **Things I disagree with or want to investigate further:**
         """;

   /**
    * What is known about a paper when filing it.
    *
    * @param title   The paper's title, also the source of the file names.
    * @param venue   Where it was published, e.g. {@code ATC}; also its folder. Papers without one go to {@code others}.
    * @param year    When it was published; the current year when not given.
    * @param authors The authors, as they should read in the note, e.g. {@code Diego Ongaro, John Ousterhout}.
    * @param link    Where to find the paper online.
    */
   public record Details(String title, Optional<String> venue, Optional<Year> year, Optional<String> authors, Optional<String> link) { }

   /**
    * A filed paper.
    *
    * @param name The note's path relative to {@code data.location}, without {@code .md}, as every note is named.
    * @param note The absolute path of the paper's note.
    * @param pdf  The absolute path of the copied PDF, next to the note.
    */
   public record Filed(String name, Path note, Path pdf) implements JsonWritable {

      /**
       * Writes the paper's {@code id}, {@code path} and {@code pdf}.
       *
       * @param target the JSON object to write into.
       * @return {@code target}, for chaining.
       */
      @Override
      public ObjectNode writeJson(ObjectNode target) {
         target.put("id", "note:" + name);
         target.put("path", note.toString());
         target.put("pdf", pdf.toString());
         return target;
      }
   }

   private final Configuration configuration;
   private final Clock clock;

   /**
    * @param configuration the configuration giving the papers folder.
    * @param clock the clock for {@code date_read} and the default year.
    */
   public Papers(Configuration configuration, Clock clock) {
      this.configuration = configuration;
      this.clock = clock;
   }

   /**
    * Files a paper: copies its PDF into the papers folder and creates its note next to it.
    *
    * <p>
    * The original PDF is never moved or deleted. The note's frontmatter has the paper's details, today as
    * {@code date_read}, and the PDF's file name as {@code pdf}; details not given are left empty.
    * </p>
    *
    * @param pdf the PDF to file, anywhere on disk.
    * @param details what is known about the paper.
    * @return the paths of the note and the copied PDF.
    * @throws ZtkException with {@link ZtkException.Kind#VALIDATION} when a PDF or note already exists at the target.
    *                      Nothing is written.
    * @throws ZtkException with {@link ZtkException.Kind#NOT_FOUND} when there is no file at {@code pdf}. Nothing is
    *                      written.
    * @throws UncheckedIOException when the PDF cannot be copied or the note cannot be written.
    */
   public Filed create(Path pdf, Details details) {
      // A folder, or anything else that cannot be copied as a file, is no PDF either.
      if (!Files.isRegularFile(pdf)) {
         throw ZtkException.notFound("No PDF at " + pdf + ". Check the path, e.g. with: ls " + pdf.getParent());
      }

      String venue = details.venue().orElse(NO_VENUE);
      // One visible folder under papers: no nesting or escaping, and nothing search would skip as hidden.
      if (venue.isEmpty() || venue.contains("/") || venue.startsWith(".")) {
         throw ZtkException.usage("The venue '" + venue + "' must be a single folder name, e.g. ATC or SIGMOD.");
      }

      String slug = Slugs.of(details.title());
      // File names keep only ASCII letters and digits, so some titles leave nothing to name the pair after.
      if (slug.isEmpty()) {
         throw ZtkException.usage("The title '" + details.title() + "' has no letters or digits to name the files "
               + "after. Give a title with some, e.g. its English or transliterated form.");
      }

      Year year = details.year().orElseGet(() -> Year.now(clock));
      Path folder = configuration.folders().papers()
            .resolve(details.venue.orElse(NO_VENUE))
            .resolve(year.toString());
      Path copy = folder.resolve(slug + ".pdf");
      Path note = folder.resolve(slug + ".md");

      // Both checked before anything is written.
      // A refused paper never leaves half of the pair behind.
      for (Path target : List.of(copy, note)) {
         if (Files.exists(target))
            throw ZtkException.validation("A paper is already filed at " + target
                  + ". Edit its note, or file this paper under another title, venue or year.");
      }

      SequencedMap<String, String> frontmatter = new LinkedHashMap<>();
      frontmatter.put("title", details.title());
      frontmatter.put("authors", details.authors().orElse(""));
      frontmatter.put("venue", details.venue().orElse(""));
      frontmatter.put("link", details.link().orElse(""));
      frontmatter.put("date_read", LocalDate.now(clock).toString());
      // Relative to the note, so the two can be moved together.
      frontmatter.put("pdf", copy.getFileName().toString());

      try {
         Files.createDirectories(folder);
         // The PDF first.
         // A note pointing to a missing PDF would be worse than a PDF without a note.
         Files.copy(pdf, copy);
      } catch (IOException e) {
         throw new UncheckedIOException("Failed copying pdf " + pdf + " to " + copy, e);
      }

      // Succeeded with the copy, proceed with the note.
      NoteFiles.write(note, frontmatter, TEMPLATE);

      // Named like every other note, so the ID matches what search prints for it.
      String name = configuration.dataLocation().relativize(folder).resolve(slug).toString();
      return new Filed(name, note, copy);
   }
}
