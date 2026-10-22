package io.github.jabolina.ztk.reports;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.jabolina.ztk.ZtkException;

/**
 * An ISO week, Monday to Sunday, the period of a weekly summary.
 *
 * <p>
 * Written as ISO writes it, e.g. {@code 2026-W42}. The first week of a year is the one holding its first Thursday,
 * so a week may start in the year before, and some years have a 53rd week.
 * </p>
 *
 * @param year The week-based year.
 * @param week The week of that year, 1 to 52 or 53.
 * @since 1.0
 * @author José Bolina
 */
public record Week(int year, int week) {

   private static final Pattern TEXT = Pattern.compile("(\\d{4})-W(\\d{2})");

   /**
    * Reads a week as written on the command line.
    *
    * @param text the week, e.g. {@code 2026-W42}.
    * @return the week.
    * @throws ZtkException with {@link ZtkException.Kind#USAGE} when it is not a week of its year.
    */
   public static Week parse(String text) {
      Matcher matcher = TEXT.matcher(text);
      if (matcher.matches()) {
         int year = Integer.parseInt(matcher.group(1));
         int week = Integer.parseInt(matcher.group(2));
         // January 4th is always in the first week, and tells how many weeks its year has.
         if (IsoFields.WEEK_OF_WEEK_BASED_YEAR.rangeRefinedBy(LocalDate.of(year, 1, 4)).isValidValue(week)) {
            return new Week(year, week);
         }
      }
      throw ZtkException.usage("The period '" + text + "' must be an ISO week, e.g. 2026-W42.");
   }

   /**
    * @param clock the clock telling today.
    * @return the week before the current one, whatever day it is now.
    */
   public static Week previous(Clock clock) {
      LocalDate day = LocalDate.now(clock).minusWeeks(1);
      return new Week(day.get(IsoFields.WEEK_BASED_YEAR), day.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
   }

   /**
    * @return the Monday the week starts on.
    */
   public LocalDate first() {
      return LocalDate.of(year, 1, 4)
            .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, week)
            .with(DayOfWeek.MONDAY);
   }

   /**
    * @return the Sunday the week ends on.
    */
   public LocalDate last() {
      return first().plusDays(6);
   }

   /**
    * @return the week, e.g. {@code 2026-W42}.
    */
   @Override
   public String toString() {
      return "%d-W%02d".formatted(year, week);
   }
}
