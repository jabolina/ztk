package io.github.jabolina.ztk.cli;

import org.aesh.command.validator.OptionValidator;
import org.aesh.command.validator.OptionValidatorException;
import org.aesh.command.validator.ValidatorInvocation;

/**
 * Rejects positional arguments that look like options.
 *
 * <p>
 * Æsh rejects unknown {@code --long} options by itself, but passes single-dash words such as {@code -week} through
 * as arguments. ztk forwards arguments to Taskwarrior, where {@code -week} means "remove the tag week", so they are
 * rejected before the command runs. A lone {@code -} is allowed, as the conventional stdin marker.
 * </p>
 *
 * @since 1.0
 * @author José Bolina
 */
public class ArgumentGuard implements OptionValidator<ValidatorInvocation<String, Object>> {
   @Override
   public void validate(ValidatorInvocation<String, Object> invocation) throws OptionValidatorException {
      String argument = invocation.getValue();
      if (argument.startsWith("-") && !argument.equals("-"))
         throw new OptionValidatorException("Unsupported argument: " + argument + ". " +
               "Options ztk does not know are rejected, including Taskwarrior's -tag");
   }
}
