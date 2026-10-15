package io.github.jabolina.ztk;

/**
 * A failure that ends the command with a well-known exit code.
 *
 * <p>
 * Commands throw it instead of printing or exiting; {@code Ztk.run} turns it into the message on stderr and the process
 * exit code.
 * </p>
 *
 * @since 1.0.0
 * @author José Bolina
 */
public final class ZtkException extends RuntimeException {

   public enum Kind {
      /**
       * Bad command line or configuration.
       */
      USAGE(1),

      /**
       * A referenced task, note, or file does not exist.
       */
      NOT_FOUND(2),

      /**
       * Input is well-formed but rejected, e.g., an unknown effort or missing project.
       */
      VALIDATION(3),

      /**
       * TaskWarrior failed. The message carries the process stderr.
       */
      TASKWARRIOR(4);

      private final int exitCode;

      Kind(int exitCode) {
         this.exitCode = exitCode;
      }

      public int exitCode() {
         return exitCode;
      }
   }

   private final Kind kind;

   public ZtkException(Kind kind, String message) {
      super(message);
      this.kind = kind;
   }

   public int exitCode() {
      return kind.exitCode();
   }

   public static ZtkException usage(String message) {
      return new ZtkException(Kind.USAGE, message);
   }

   public static ZtkException notFound(String message) {
      return new ZtkException(Kind.NOT_FOUND, message);
   }

   public static ZtkException validation(String message) {
      return new ZtkException(Kind.VALIDATION, message);
   }

   public static ZtkException taskwarrior(String message) {
      return new ZtkException(Kind.TASKWARRIOR, message);
   }
}
