package io.github.jabolina.ztk.cli.converters;

import java.util.Arrays;
import java.util.stream.Collectors;

import org.aesh.command.converter.Converter;
import org.aesh.command.converter.ConverterInvocation;
import org.aesh.command.validator.OptionValidatorException;

import io.github.jabolina.ztk.notes.NoteType;

/**
 * Converts a {@code --type} value to a {@link NoteType}.
 *
 * <p>
 * Values are the type labels, e.g. {@code zettel}, ignoring case and surrounding spaces. An unknown value is
 * rejected naming the valid types, which ztk reports as a usage error.
 * </p>
 *
 * @since 1.0
 * @author José Bolina
 */
public class NoteTypeConverter implements Converter<NoteType, ConverterInvocation> {

   /**
    * Converts one value of the option; Æsh calls it once per value of a comma-separated or repeated option.
    *
    * @param invocation gives the value as typed.
    * @return the note type with that label.
    * @throws OptionValidatorException when no type has that label, listing the valid ones.
    */
   @Override
   public NoteType convert(ConverterInvocation invocation) throws OptionValidatorException {
      String value = invocation.getInput().strip();
      for (NoteType type : NoteType.values()) {
         if (type.label().equalsIgnoreCase(value))
            return type;
      }

      throw new OptionValidatorException("Unknown note type '" + value + "' for --type. Valid types: "
            + Arrays.stream(NoteType.values()).map(NoteType::label).collect(Collectors.joining(", ")));
   }
}
