package io.github.jabolina.ztk.cli.completers;

import java.time.Clock;
import java.time.LocalDate;
import java.time.Year;
import java.time.temporal.IsoFields;
import java.util.stream.Stream;

import io.github.jabolina.ztk.planning.Period;
import io.github.jabolina.ztk.reports.Week;

/**
 * Completes the quarter or year of a plan listing: the previous, current and next quarter, and the current and next
 * year, the periods planned and reviewed.
 *
 * <p>
 * The nested completer offers the periods a summary is taken of instead.
 * </p>
 *
 * @since 1.0
 * @author José Bolina
 */
public class PeriodCompleter implements ZtkCompleter {

   @Override
   public void complete(ZtkCompleterInvocation invocation) {
      invocation.offer(periods(Clock.systemDefaultZone()).toList());
   }

   /**
    * @param clock the clock telling today.
    * @return the periods offered, as the command takes them.
    */
   protected Stream<String> periods(Clock clock) {
      LocalDate today = LocalDate.now(clock);
      return Stream.of(quarter(today.minusMonths(3)), quarter(today), quarter(today.plusMonths(3)),
                  new Period.OfYear(Year.from(today)), new Period.OfYear(Year.from(today).plusYears(1)))
            .map(Period::toString);
   }

   /**
    * @param day a day.
    * @return the quarter it is in.
    */
   private static Period quarter(LocalDate day) {
      return new Period.OfQuarter(Year.from(day), day.get(IsoFields.QUARTER_OF_YEAR));
   }

   /**
    * Completes the period of a summary: the previous week, the weekly review's, and the current and previous quarter
    * and year, a retrospective's.
    *
    * @since 1.0
    * @author José Bolina
    */
   public static final class Summary extends PeriodCompleter {

      @Override
      protected Stream<String> periods(Clock clock) {
         LocalDate today = LocalDate.now(clock);
         return Stream.of(Week.previous(clock), quarter(today.minusMonths(3)), quarter(today),
                     new Period.OfYear(Year.from(today).minusYears(1)), new Period.OfYear(Year.from(today)))
               .map(Object::toString);
      }
   }
}
