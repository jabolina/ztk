package io.github.jabolina.ztk;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Runs ztk in-process, as the command line would, and captures what it prints.
 *
 * <p>
 * {@code System.out} and {@code System.err} are replaced for the duration of the run, because Æsh prints help and
 * usage errors there itself. Tests using the harness must therefore not run in parallel.
 * </p>
 */
public final class CliHarness {

   /**
    * The outcome of one ztk invocation.
    *
    * @param exitCode The process exit code ztk would have returned.
    * @param out      Everything printed on stdout.
    * @param err      Everything printed on stderr.
    */
   public record Result(int exitCode, String out, String err) { }

   private CliHarness() { }

   /**
    * Runs ztk with the given arguments.
    *
    * @param env the process environment, e.g. from {@link TestTaskwarrior#environment()}.
    * @param tty whether stdout should be treated as a terminal.
    * @param args the command line arguments, without the program name.
    * @return the exit code and the captured output.
    */
   public static Result run(Properties env, boolean tty, String... args) {
      PrintStream originalOut = System.out;
      PrintStream originalErr = System.err;
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ByteArrayOutputStream err = new ByteArrayOutputStream();
      try {
         System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
         System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
         int exitCode = Ztk.run(args, env, tty);
         return new Result(exitCode, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
      } finally {
         System.setOut(originalOut);
         System.setErr(originalErr);
      }
   }
}
