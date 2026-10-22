package io.github.jabolina.ztk.reports;

import java.time.LocalDate;

import io.github.jabolina.ztk.util.JsonWritable;
import io.github.jabolina.ztk.util.MarkdownWritable;

/**
 * The facts of a period: the whole of them as JSON, for the agent writing the narrative, and as Markdown for people
 * and the written file.
 *
 * @since 1.0
 * @author José Bolina
 */
public sealed interface Summary extends JsonWritable, MarkdownWritable permits WeeklySummary, PlanSummary {

   /**
    * @return the period as written on the command line and in the file's name, e.g. {@code 2026-W42},
    *         {@code 2026-Q4} or {@code 2026}.
    */
   String name();

   /**
    * @return the last day of the period; its summary is written from the day after.
    */
   LocalDate last();
}
