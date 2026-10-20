package io.github.jabolina.ztk.planning;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Year;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import io.github.jabolina.ztk.ZtkException;

/**
 * The period a goal is for: a calendar year, or one calendar quarter of it.
 *
 * <p>
 * Written as goal IDs start, e.g. {@code 2026} or {@code 2026-Q4}.
 * </p>
 *
 * @since 1.0
 * @author José Bolina
 */
public sealed interface Period {

   /**
    * Reads a period as written on the command line.
    *
    * @param text the period, e.g. {@code 2026} or {@code 2026-Q4}.
    * @return the year or the quarter.
    * @throws ZtkException with {@link ZtkException.Kind#USAGE} when it is neither a year nor a quarter.
    */
   static Period parse(String text) {
      // Compiled here: an interface cannot hold a private constant, and a period is parsed once per command.
      Matcher matcher = Pattern.compile("(\\d{4})(?:-Q([1-4]))?").matcher(text);
      if (!matcher.matches()) {
         throw ZtkException.usage("The period '" + text + "' must be a year or a quarter of it, e.g. 2026 or 2026-Q4.");
      }
      Year year = Year.parse(matcher.group(1));
      return matcher.group(2) == null
            ? new OfYear(year)
            : new OfQuarter(year, Integer.parseInt(matcher.group(2)));
   }

   /**
    * @return the calendar year of the period, or the year it is a quarter of.
    */
   Year year();

   /**
    * Gives the folder holding the goals of this period.
    *
    * @param goals the goals folder.
    * @return {@code <goals>/2026} for a year, {@code <goals>/2026/Q4} for a quarter.
    */
   Path folder(Path goals);

   /**
    * @return the year the period is, or the year it is a quarter of.
    */
   OfYear ofYear();

   /**
    * @return the last day of the period, e.g. 2026-12-31 for both 2026 and 2026-Q4.
    */
   LocalDate end();

   /**
    * Tells whether a quarter is within this period.
    *
    * @param quarter the quarter, e.g. {@code 2026-Q4}.
    * @return whether it is this quarter, or one of the four quarters of this year.
    */
   boolean contains(OfQuarter quarter);

   /**
    * A calendar year, the period of yearly goals.
    *
    * @param year The year.
    */
   record OfYear(Year year) implements Period {

      @Override
      public Path folder(Path goals) {
         return goals.resolve(year.toString());
      }

      @Override
      public OfYear ofYear() {
         return this;
      }

      @Override
      public LocalDate end() {
         return year.atMonth(12).atEndOfMonth();
      }

      @Override
      public boolean contains(OfQuarter quarter) {
         return quarter.year().equals(year);
      }

      /**
       * @return the four quarters of the year, in order.
       */
      public Stream<OfQuarter> quarters() {
         return Stream.of(1, 2, 3, 4).map(quarter -> new OfQuarter(year, quarter));
      }

      /**
       * @return the year, e.g. {@code 2026}.
       */
      @Override
      public String toString() {
         return year.toString();
      }
   }

   /**
    * A calendar quarter, the period of quarterly goals.
    *
    * @param year    The year the quarter belongs to.
    * @param quarter The quarter, 1 (January to March) to 4.
    */
   record OfQuarter(Year year, int quarter) implements Period {

      @Override
      public OfYear ofYear() {
         return new OfYear(year);
      }

      @Override
      public LocalDate end() {
         return year.atMonth(quarter * 3).atEndOfMonth();
      }

      @Override
      public boolean contains(OfQuarter quarter) {
         return equals(quarter);
      }

      @Override
      public Path folder(Path goals) {
         return ofYear().folder(goals).resolve("Q" + quarter);
      }

      /**
       * @return the quarter, e.g. {@code 2026-Q4}.
       */
      @Override
      public String toString() {
         return year + "-Q" + quarter;
      }
   }
}
