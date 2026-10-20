package io.github.jabolina.ztk.util;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * An enum constant written in lowercase wherever people and agents see it: JSON, frontmatter and the command line.
 *
 * @since 1.0
 * @author José Bolina
 */
public interface Labeled {

   /**
    * @return the constant's name, as every enum has it.
    */
   String name();

   /**
    * @return the name used in JSON, frontmatter and on the command line, e.g. {@code zettel}.
    */
   default String label() {
      return name().toLowerCase(Locale.ROOT);
   }

   /**
    * Finds the constant with a label.
    *
    * @param type the enum.
    * @param label the label, exactly as written, e.g. {@code reached}.
    * @param <E> the enum type.
    * @return the constant; empty when none has that label.
    */
   static <E extends Enum<E> & Labeled> Optional<E> parse(Class<E> type, String label) {
      return Arrays.stream(type.getEnumConstants())
            .filter(constant -> constant.label().equals(label))
            .findFirst();
   }

   /**
    * @param type the enum.
    * @param <E> the enum type.
    * @return every label of the enum, in declaration order, e.g. for completion and error messages.
    */
   static <E extends Enum<E> & Labeled> List<String> labels(Class<E> type) {
      return Arrays.stream(type.getEnumConstants())
            .map(Labeled::label)
            .toList();
   }
}
