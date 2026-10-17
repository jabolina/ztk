package io.github.jabolina.ztk.cli;

import org.aesh.command.completer.OptionCompleter;

/**
 * A completer that needs the ztk {@link io.github.jabolina.ztk.Context}, e.g. to read candidates from Taskwarrior.
 *
 * <p>
 * Declare it on an argument or option with {@code completer = ...}, as any Æsh completer. ztk hands it a
 * {@link ZtkCompleterInvocation} when the command's container is created.
 * </p>
 *
 * @since 1.0
 * @author José Bolina
 */
public interface ZtkCompleter extends OptionCompleter<ZtkCompleterInvocation> { }
