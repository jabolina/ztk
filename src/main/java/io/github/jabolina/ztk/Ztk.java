package io.github.jabolina.ztk;

import java.nio.file.Path;
import java.util.Properties;

import org.aesh.AeshRuntimeRunner;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.invocation.CommandInvocationProvider;
import org.aesh.command.validator.OptionValidatorException;

import io.github.jabolina.ztk.Context.OutputMode;
import io.github.jabolina.ztk.cli.ZtkCommand;
import io.github.jabolina.ztk.cli.ZtkInvocation;

/**
 * Entry point of the {@code ztk} command line.
 *
 * @since 1.0
 * @author José Bolina
 */
public final class Ztk {

   private Ztk() { }

   static void main(String[] args) {
      Properties env = new Properties();
      env.putAll(System.getenv());
      System.exit(run(args, env, System.console() != null));
   }

   /**
    * Runs one ztk command line.
    *
    * @param args the command line arguments, without the program name.
    * @param env the process environment.
    * @param tty whether stdout is a terminal, which selects human or JSON output.
    * @return the process exit code.
    */
   public static int run(String[] args, Properties env, boolean tty) {
      GlobalOptions options = GlobalOptions.parse(args);
      Context context = new Context(env, tty, options.mode(), options.configuration());
      CommandInvocationProvider<ZtkInvocation> invocations = new CommandInvocationProvider<>() {
         @Override
         public ZtkInvocation enhanceCommandInvocation(CommandInvocation invocation) {
            return new ZtkInvocation(invocation, context);
         }
      };
      try {
         CommandResult result = AeshRuntimeRunner.builder()
               .commandInvocationProvider(invocations)
               .command(ZtkCommand.class)
               .args(args)
               .execute();
         // Commands report failures by throwing ZtkException, never through their result. A non-zero result therefore
         // means Æsh rejected the command line itself (unknown command or option, missing value): a usage error.
         return result == null || result.getExitCode() == 0 ? 0 : ZtkException.Kind.USAGE.exitCode();
      } catch (RuntimeException e) {
         ZtkException failure = failure(e);
         if (failure == null) {
            // Not ours: a bug, which should surface with its stack trace.
            throw e;
         }
         System.err.println(failure.getMessage());
         return failure.exitCode();
      }
   }

   /**
    * Finds the ztk failure behind an exception escaping from Æsh.
    *
    * @param e the exception thrown while running the command line.
    * @return the first {@link ZtkException} in the cause chain, with Æsh validation failures reported as usage errors,
    *         or {@code null} when the exception is neither.
    */
   private static ZtkException failure(Throwable e) {
      // Æsh wraps exceptions thrown by commands and validators, so search the whole chain.
      for (Throwable cause = e; cause != null; cause = cause.getCause()) {
         if (cause instanceof ZtkException failure) {
            return failure;
         }
         if (cause instanceof OptionValidatorException invalid) {
            return ZtkException.usage(invalid.getMessage());
         }
      }
      return null;
   }

   /**
    * The options that apply to every command, read before Æsh dispatches the command line.
    *
    * @param mode the requested output mode.
    * @param configuration the configuration file given with {@code --config}, if any.
    */
   record GlobalOptions(OutputMode mode, Path configuration) {

      /**
       * Reads the global options from the command line.
       *
       * <p>
       * Options may appear anywhere before a literal {@code --}. When repeated, the last one wins.
       * </p>
       *
       * @param args the command line arguments.
       * @return the global options found.
       */
      static GlobalOptions parse(String[] args) {
         OutputMode mode = OutputMode.AUTO;
         Path configuration = null;
         for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            // After "--", arguments belong to the command, even if they look like ztk options.
            if (arg.equals("--")) {
               break;
            }
            if (arg.equals("--json")) {
               mode = OutputMode.JSON;
            } else if (arg.equals("--human")) {
               mode = OutputMode.HUMAN;
            } else if (arg.equals("--config") && i + 1 < args.length) {
               configuration = Path.of(args[++i]);
            } else if (arg.startsWith("--config=")) {
               configuration = Path.of(arg.substring("--config=".length()));
            }
         }
         return new GlobalOptions(mode, configuration);
      }
   }
}
