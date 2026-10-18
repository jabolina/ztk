package io.github.jabolina.ztk.search;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Stream;

import io.github.jabolina.ztk.notes.KnowledgeBase;
import io.github.jabolina.ztk.notes.Line;
import io.github.jabolina.ztk.notes.Note;
import io.github.jabolina.ztk.notes.NoteType;
import io.github.jabolina.ztk.notes.NoteWarning;

/**
 * Finds the notes containing words or phrases.
 *
 * <p>
 * A note matches when its title or body contains every term, ignoring case. A term with spaces is a phrase, matched
 * as written. There are no regular expressions and no fuzzy matching.
 * </p>
 *
 * @since 1.0
 * @author José Bolina
 */
public final class Search {

   // Enough lines to judge a note without opening it; the rest are one nvim +<line> away.
   private static final int MAX_LINES = 3;

   // Notes about the subject first, then notes discussing it most, then what was worked on last.
   private static final Comparator<Match> ORDER = Comparator.comparing(Match::titleMatched, Comparator.reverseOrder())
         .thenComparing(Match::count, Comparator.reverseOrder())
         .thenComparing(match -> match.note().header().modified(), Comparator.reverseOrder());

   /**
    * What to search for, and where.
    *
    * @param terms   The words or phrases every matching note contains, e.g. {@code ["lamport clocks", "paxos"]}.
    * @param types   The note types to search; empty to search every type.
    * @param project The only project whose notes are searched, if set.
    */
   public record Query(List<String> terms, EnumSet<NoteType> types, Optional<String> project) { }

   private final KnowledgeBase knowledgeBase;

   /**
    * @param knowledgeBase the notes to search.
    */
   public Search(KnowledgeBase knowledgeBase) {
      this.knowledgeBase = knowledgeBase;
   }

   /**
    * Finds the notes matching a query.
    *
    * <p>
    * Notes whose title contains a term come first, then notes with more matching lines, then the most recently
    * modified.
    * </p>
    *
    * @param query the terms and filters.
    * @param warnings receives the notes skipped because they cannot be read.
    * @return the matching notes with up to three matching lines each, in that order. It must be closed, e.g. with
    *         try-with-resources.
    */
   public Stream<Match> find(Query query, Consumer<NoteWarning> warnings) {
      List<String> terms = query.terms().stream()
            .map(term -> term.toLowerCase(Locale.ROOT))
            .toList();
      Stream<Match> matches = query.project()
            .map(project -> knowledgeBase.read(project, new SearchReader(terms, query.types()), warnings))
            .orElseGet(() -> knowledgeBase.read(new SearchReader(terms, query.types()), warnings));
      return matches.sorted(ORDER);
   }

   private static final class SearchReader implements BiFunction<Note, Stream<Line>, Optional<Match>> {

      private final Collection<String> terms;
      private final EnumSet<NoteType> types;

      /**
       * @param terms the lower-cased terms.
       * @param types the note types to search; empty for every type.
       */
      public SearchReader(Collection<String> terms, EnumSet<NoteType> types) {
         this.terms = terms;
         this.types = types;
      }

      /**
       * Matches one note against the terms.
       *
       * @param note the note.
       * @param lines the note's body lines, consumed here.
       * @return the match, or empty when the note is of another type or misses a term.
       */
      @Override
      public Optional<Match> apply(Note note, Stream<Line> lines) {
         // The type is known before the body is read, so a note of another type is skipped without reading it.
         if (!types.isEmpty() && !types.contains(note.type()))
            return Optional.empty();

         // The terms not found yet in this note. One reader serves every note, so this must not outlive the call.
         Set<String> missing = new HashSet<>(terms);
         String title = note.header().title().toLowerCase(Locale.ROOT);
         boolean titleMatched = terms.stream().anyMatch(title::contains);
         missing.removeIf(title::contains);

         // Reading the full document to count the number of matches.
         // However, the output is limited to maximum number of entries.
         List<Line> matching = new ArrayList<>();
         AtomicInteger count = new AtomicInteger();
         lines.forEach(line -> {
                  String text = line.text().toLowerCase(Locale.ROOT);

                  // A line is kept when it contains any term, even one already found earlier.
                  if (terms.stream().anyMatch(text::contains)) {
                     missing.removeIf(text::contains);
                     count.incrementAndGet();
                     if (matching.size() < MAX_LINES)
                        matching.add(line);
                  }
               });
         return missing.isEmpty()
               ? Optional.of(new Match(note, titleMatched, count.get(), List.copyOf(matching)))
               : Optional.empty();
      }
   }
}
