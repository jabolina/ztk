package io.github.jabolina.ztk.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.aesh.command.CommandException;
import org.aesh.command.CommandNotFoundException;
import org.aesh.command.Executor;
import org.aesh.command.impl.context.CommandContext;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.invocation.CommandInvocationConfiguration;
import org.aesh.command.parser.CommandLineParserException;
import org.aesh.command.shell.Shell;
import org.aesh.command.validator.CommandValidatorException;
import org.aesh.command.validator.OptionValidatorException;
import org.aesh.readline.prompt.Prompt;
import org.aesh.terminal.KeyAction;

import io.github.jabolina.ztk.Context;
import io.github.jabolina.ztk.notes.NoteWarning;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The invocation every ztk command receives: Æsh's own invocation, plus the ztk {@link Context}.
 *
 * <p>
 * All Æsh behavior is delegated unchanged, except {@link #printErr(String)}: outside the interactive console, Æsh
 * writes it to stdout, while ztk keeps stdout for results and sends human messages to stderr. {@link #stdin()} is
 * ztk's own as well: Æsh's {@link #getStdin()} only sees its console's pipes, never the process's.
 * </p>
 *
 * @since 1.0
 * @author José Bolina
 */
public final class ZtkInvocation implements CommandInvocation {

   // ANSI color sequences, ESC [ parameters m: the only escape sequences Taskwarrior emits.
   private static final Pattern COLORS = Pattern.compile("\u001B\\[[0-9;]*m");

   // Version of every JSON shape ztk prints; bumped only on incompatible changes.
   private static final int SCHEMA = 1;
   private static final JsonMapper JSON = JsonMapper.shared();

   private final CommandInvocation delegate;
   private final Context context;

   /**
    * Wraps an Æsh invocation.
    *
    * @param delegate the invocation created by Æsh.
    * @param context the dependencies of the running ztk invocation.
    */
   public ZtkInvocation(CommandInvocation delegate, Context context) {
      this.delegate = delegate;
      this.context = context;
   }

   /**
    * @return the dependencies of the running ztk invocation.
    */
   public Context context() {
      return context;
   }

   /**
    * Prints a human message on stderr, followed by a line break.
    *
    * @param message the message to print.
    */
   @Override
   public void printErr(String message) {
      System.err.println(message);
   }

   /**
    * Reads everything piped or redirected into ztk, e.g. {@code echo "Tests pass." | ztk session add 3 --done - …}.
    *
    * <p>
    * Read once: a second call returns what is left, which is nothing.
    * </p>
    *
    * @return the text on standard input, as is; empty when nothing was given.
    * @throws UncheckedIOException when standard input cannot be read.
    */
   public String stdin() {
      // Æsh's getStdin() serves pipes between commands of its own console; the runner running ztk never connects it to
      // the process's standard input, so it returns null even when the shell pipes into ztk.
      try {
         return new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
      } catch (IOException e) {
         throw new UncheckedIOException(e);
      }
   }

   // Everything below delegates to Æsh unchanged.

   @Override
   public Shell getShell() {
      return delegate.getShell();
   }

   @Override
   public void setPrompt(Prompt prompt) {
      delegate.setPrompt(prompt);
   }

   @Override
   public Prompt getPrompt() {
      return delegate.getPrompt();
   }

   @Override
   public String getHelpInfo(String commandName) {
      return delegate.getHelpInfo(commandName);
   }

   @Override
   public String getHelpInfo() {
      return delegate.getHelpInfo();
   }

   @Override
   public void stop() {
      delegate.stop();
   }

   @Override
   public CommandInvocationConfiguration getConfiguration() {
      return delegate.getConfiguration();
   }

   @Override
   public KeyAction input() throws InterruptedException {
      return delegate.input();
   }

   @Override
   public KeyAction input(long timeout, TimeUnit unit) throws InterruptedException {
      return delegate.input(timeout, unit);
   }

   @Override
   public String inputLine() throws InterruptedException {
      return delegate.inputLine();
   }

   @Override
   public String inputLine(Prompt prompt) throws InterruptedException {
      return delegate.inputLine(prompt);
   }

   @Override
   public void executeCommand(String input) throws CommandNotFoundException, CommandLineParserException,
         OptionValidatorException, CommandValidatorException, CommandException, InterruptedException, IOException {
      delegate.executeCommand(input);
   }

   @Override
   public Executor<? extends CommandInvocation> buildExecutor(String line) throws CommandNotFoundException,
         CommandLineParserException, OptionValidatorException, CommandValidatorException, IOException {
      return delegate.buildExecutor(line);
   }

   @Override
   public void print(String message, boolean paging) {
      delegate.print(message, paging);
   }

   @Override
   public void println(String message, boolean paging) {
      delegate.println(message, paging);
   }

   @Override
   public CommandContext getCommandContext() {
      // Parent and inherited option values are resolved through the command context.
      return delegate.getCommandContext();
   }

   /**
    * Starts a JSON result.
    *
    * @return a new JSON object holding only the {@code schema} version.
    */
   public ObjectNode jsonResult() {
      ObjectNode result = JSON.createObjectNode();
      result.put("schema", SCHEMA);
      return result;
   }

   /**
    * Prints a command result on stdout, as human text or JSON depending on the output mode.
    *
    * @param human the result for people, printed as is.
    * @param json the same result for programs, started with {@link #jsonResult()}.
    */
   public void result(String human, ObjectNode json) {
      result(() -> human, json);
   }

   /**
    * Prints a command result on stdout, producing the human text only when it is printed.
    *
    * @param human produces the result for people, e.g. by rendering a Taskwarrior report. Not called in JSON mode.
    * @param json the same result for programs, started with {@link #jsonResult()}.
    */
   public void result(Supplier<String> human, ObjectNode json) {
      if (context.json()) {
         println(JSON.writeValueAsString(json));
      } else if (context.colors()) {
         print(human.get());
      } else {
         print(COLORS.matcher(human.get()).replaceAll(""));
      }
   }

   /**
    * Reports the notes skipped while reading the knowledge base: in a {@code warnings} array of the JSON result, and
    * on stderr for people.
    *
    * @param json the JSON result, started with {@link #jsonResult()}. It gets the array, empty when nothing is skipped.
    * @return the consumer to pass to the knowledge base.
    */
   public Consumer<NoteWarning> warnings(ObjectNode json) {
      // Always present, so agents can rely on the key. People see the warnings on stderr instead.
      ArrayNode warnings = json.putArray("warnings");
      return warning -> {
         warning.writeJson(warnings.addObject());
         if (!context.json()) {
            printErr("warning: " + warning.path() + ": " + warning.message() + " " + warning.hint());
         }
      };
   }
}
