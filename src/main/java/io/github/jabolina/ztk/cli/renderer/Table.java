package io.github.jabolina.ztk.cli.renderer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * A plain-text table for human output, built once per command from what each column shows.
 *
 * <p>
 * Columns are left-aligned, padded to their widest cell and separated by two spaces; the last column is not padded,
 * so no line ends in spaces. Each row may have detail lines, printed under it and aligned with the second column,
 * e.g. the matching lines of a search result. A built table only renders and holds no rows, so it can be a constant.
 * </p>
 *
 * <pre>
 * Table&lt;Match&gt; table = Table.&lt;Match&gt;builder()
 *       .column("TYPE", match -&gt; match.note().type().label())
 *       .column("TITLE", match -&gt; match.note().header().title())
 *       .details(match -&gt; List.of("5: Raft elects a leader."))
 *       .build();
 *
 * TYPE    TITLE
 * zettel  Raft
 *         5: Raft elects a leader.
 * </pre>
 *
 * @param <T> the values shown, one per row.
 * @since 1.0
 * @author José Bolina
 */
public class Table<T> {

   private static final String GAP = "  ";

   /**
    * A column: its header and the cell it shows for each value.
    *
    * @param name  The header, e.g. {@code TITLE}.
    * @param value Gives the cell for a value.
    * @param <T>   The values shown.
    */
   private record Column<T>(String name, Function<? super T, String> value) { }

   /**
    * Defines a table's columns and detail lines.
    *
    * @param <T> the values shown, one per row.
    */
   public static final class Builder<T> {

      private final List<Column<T>> columns = new ArrayList<>();
      private Function<? super T, List<String>> details = row -> List.of();

      private Builder() { }

      /**
       * Adds a column after the existing ones.
       *
       * @param name the header, e.g. {@code TITLE}.
       * @param value gives the cell for a value.
       * @return this builder, for chaining.
       */
      public Builder<T> column(String name, Function<? super T, String> value) {
         columns.add(new Column<>(name, value));
         return this;
      }

      /**
       * Sets the lines printed under each row; without it, rows have none.
       *
       * @param details gives a value's detail lines, in order; empty for none.
       * @return this builder, for chaining.
       */
      public Builder<T> details(Function<? super T, List<String>> details) {
         this.details = details;
         return this;
      }

      /**
       * @return the table, with the columns added so far.
       * @throws IllegalStateException when no column was added.
       */
      public Table<T> build() {
         // Details are indented by the first column's width, so there must be one.
         if (columns.isEmpty()) {
            throw new IllegalStateException("A table needs at least one column");
         }
         return new Table<>(List.copyOf(columns), details);
      }
   }

   private final List<Column<T>> columns;
   private final Function<? super T, List<String>> details;

   private Table(List<Column<T>> columns, Function<? super T, List<String>> details) {
      this.columns = columns;
      this.details = details;
   }

   /**
    * Starts defining a table.
    *
    * @param <T> the values shown, one per row.
    * @return a builder without columns or details.
    */
   public static <T> Builder<T> builder() {
      return new Builder<>();
   }

   /**
    * Renders the values as rows.
    *
    * @param values the values, one row each, in order.
    * @return the header, then every row followed by its detail lines, each line ending with a line separator. Only
    *         the header when there are no rows.
    */
   public String render(List<? extends T> values) {
      List<String> header = columns.stream().map(Column::name).toList();
      List<List<String>> cells = values.stream()
            .map(row -> columns.stream().map(column -> column.value().apply(row)).toList())
            .toList();

      int[] widths = new int[columns.size()];
      widen(widths, header);
      cells.forEach(line -> widen(widths, line));

      StringBuilder out = new StringBuilder();
      line(out, widths, header);
      // Details start where the second column does.
      String indent = " ".repeat(widths[0] + GAP.length());
      for (int i = 0; i < values.size(); i++) {
         line(out, widths, cells.get(i));
         details.apply(values.get(i))
               .forEach(detail -> out.append(indent)
                     .append(detail)
                     .append(System.lineSeparator()));
      }
      return out.toString();
   }

   /**
    * Grows the column widths to fit a line's cells.
    *
    * @param widths the widths so far, one per column; updated in place.
    * @param cells the line's cells.
    */
   private static void widen(int[] widths, List<String> cells) {
      for (int i = 0; i < cells.size(); i++) {
         widths[i] = Math.max(widths[i], cells.get(i).length());
      }
   }

   /**
    * Appends one aligned line.
    *
    * @param out where the line goes.
    * @param widths the width of every column.
    * @param cells the line's cells.
    */
   private static void line(StringBuilder out, int[] widths, List<String> cells) {
      for (int i = 0; i < cells.size(); i++) {
         if (i > 0) {
            out.append(GAP);
         }
         out.append(cells.get(i));
         // The last column is not padded, so lines never end in spaces.
         if (i < cells.size() - 1) {
            out.repeat(" ", widths[i] - cells.get(i).length());
         }
      }
      out.append(System.lineSeparator());
   }
}
