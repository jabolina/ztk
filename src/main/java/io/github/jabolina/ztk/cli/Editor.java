package io.github.jabolina.ztk.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.github.jabolina.ztk.ZtkException;

public final class Editor {

   private Editor() { }

   /**
    * Opens a file in the editor and waits until the editor exits.
    *
    * <p>
    * The editor shares ztk's terminal. The command may carry arguments, e.g. {@code nvim -c 'set spell'}; it is split
    * on whitespace, so arguments containing spaces are not supported.
    * </p>
    *
    * @param command the editor command, as configured.
    * @param file the file to open.
    * @throws ZtkException with {@link ZtkException.Kind#USAGE} when the editor cannot be started or exits with an error.
    */
   public static void open(String command, Path file) {
      List<String> line = new ArrayList<>(List.of(command.strip().split("\\s+")));
      line.add(file.toAbsolutePath().toString());

      try {
         int exitCode = new ProcessBuilder(line)
               .inheritIO()
               .start()
               .waitFor();
         if (exitCode != 0) {
            throw ZtkException.usage("The editor '" + command + "' exited with code " + exitCode + " while editing "
                  + file + ".");
         }
      } catch (IOException e) {
         throw ZtkException.usage("Cannot start the editor '" + command + "': " + e.getMessage()
               + System.lineSeparator()
               + "Set 'editor' in the ztk configuration, or the EDITOR variable. Run 'ztk config show' to see the current value.");
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
         throw ZtkException.usage("Interrupted while waiting for the editor '" + command + "'.");
      }
   }
}
