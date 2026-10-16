package io.github.jabolina.ztk;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * An isolated Taskwarrior installation and ztk configuration for tests.
 *
 * <p>
 * Everything lives under a temporary directory: a {@code taskrc}, a {@code taskdata/} directory, a {@code cerebro/}
 * knowledge base and a {@code ztkrc} pointing at all three. Every {@code task} process started by the fixture gets
 * {@code TASKRC} and {@code TASKDATA} set explicitly, so the user's real Taskwarrior data is never read or written.
 * </p>
 *
 * <p>
 * The fixture refuses to be created outside the system temporary directory, so a misconfigured test cannot point it
 * at a real home directory.
 * </p>
 */
public final class TestTaskwarrior {

   private final Path root;
   private final Path taskRc;
   private final Path taskData;
   private final Path dataLocation;
   private final Path ztkrc;
   private final Path taskStderr;

   private TestTaskwarrior(Path root) throws IOException {
      requireUnderTemporaryDirectory(root);
      this.root = root;
      this.taskData = Files.createDirectories(root.resolve("taskdata"));
      this.dataLocation = Files.createDirectories(root.resolve("cerebro"));
      this.taskStderr = root.resolve("task.stderr");

      // confirmation=off: no interactive prompts. verbose=nothing: stdout carries only the command's output.
      // uda.issue mirrors the user's own .taskrc; ztk's UDAs (note) are deliberately absent.
      this.taskRc = Files.writeString(root.resolve("taskrc"), """
            data.location=%s
            confirmation=off
            verbose=nothing
            uda.issue.type=string
            uda.issue.label=Issue
            """.formatted(taskData));
      this.ztkrc = Files.writeString(root.resolve("ztkrc"), """
            data.location=%s
            task.rc=%s
            task.data=%s
            editor=true
            """.formatted(dataLocation, taskRc, taskData));
   }

   /**
    * Creates the fixture under the given directory.
    *
    * @param root an empty directory under the system temporary directory, usually a JUnit {@code @TempDir}.
    * @return the fixture, with its files created.
    * @throws IllegalStateException when {@code root} is outside the system temporary directory.
    * @throws UncheckedIOException when the fixture files cannot be created.
    */
   public static TestTaskwarrior create(Path root) {
      try {
         return new TestTaskwarrior(root);
      } catch (IOException e) {
         throw new UncheckedIOException(e);
      }
   }

   /**
    * @return the directory holding every fixture file.
    */
   public Path root() {
      return root;
   }

   /**
    * @return the Taskwarrior configuration file.
    */
   public Path taskRc() {
      return taskRc;
   }

   /**
    * @return the Taskwarrior data directory.
    */
   public Path taskData() {
      return taskData;
   }

   /**
    * @return the knowledge base root, configured as {@code data.location} in the ztk configuration.
    */
   public Path dataLocation() {
      return dataLocation;
   }

   /**
    * @return the ztk configuration file.
    */
   public Path ztkrc() {
      return ztkrc;
   }

   /**
    * The process environment for running ztk against this fixture.
    *
    * <p>
    * {@code HOME} points at the fixture root, so the standard configuration locations are inside it too, and
    * {@code ZTKRC} selects the fixture's configuration. A new instance is returned on every call, so tests may add
    * variables to it.
    * </p>
    *
    * @return the environment variables, as expected by the configuration loader.
    */
   public Properties environment() {
      Properties env = new Properties();
      env.setProperty("HOME", root.toString());
      env.setProperty("ZTKRC", ztkrc.toString());
      return env;
   }

   /**
    * Configures an editor that records the arguments it is started with, instead of the default {@code true}.
    *
    * @return the file the editor writes its arguments to, one per line. It exists only once the editor ran.
    * @throws UncheckedIOException when the editor script or the configuration cannot be written.
    */
   public Path recordingEditor() {
      Path record = root.resolve("editor.args");
      try {
         Path editor = Files.writeString(root.resolve("editor.sh"), """
               #!/bin/sh
               printf '%%s\\n' "$@" > '%s'
               """.formatted(record));
         editor.toFile().setExecutable(true);
         Files.writeString(ztkrc, Files.readString(ztkrc).replace("editor=true", "editor=" + editor));
      } catch (IOException e) {
         throw new UncheckedIOException(e);
      }
      return record;
   }

   /**
    * Runs {@code task} directly against the fixture, for test setup and assertions.
    *
    * @param args the Taskwarrior arguments, e.g. {@code "add", "fix bug", "project:X"}.
    * @return the standard output, stripped of leading and trailing whitespace.
    * @throws IllegalStateException when {@code task} exits with a non-zero code. The message includes its stderr.
    */
   public String task(String... args) {
      List<String> command = new ArrayList<>();
      command.add("task");
      command.addAll(List.of(args));

      ProcessBuilder builder = new ProcessBuilder(command)
            // stderr goes to a file so a chatty process cannot block on a full pipe while stdout is being read.
            .redirectError(taskStderr.toFile());
      builder.environment().put("TASKRC", taskRc.toString());
      builder.environment().put("TASKDATA", taskData.toString());

      try {
         Process process = builder.start();
         String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
         int exitCode = process.waitFor();
         if (exitCode != 0) {
            String stderr = Files.readString(taskStderr).strip();
            throw new IllegalStateException(
                  "task " + String.join(" ", args) + " exited with " + exitCode + ": " + stderr + stdout);
         }
         return stdout;
      } catch (IOException e) {
         throw new UncheckedIOException(e);
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
         throw new IllegalStateException(e);
      }
   }

   /**
    * Guards against creating the fixture anywhere a user's real data could live.
    *
    * @param root the requested fixture root.
    * @throws IOException when the temporary directory or the root cannot be resolved.
    * @throws IllegalStateException when {@code root} is outside the system temporary directory.
    */
   private static void requireUnderTemporaryDirectory(Path root) throws IOException {
      Path temporary = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
      // toRealPath resolves symlinks but requires the path to exist; a missing root is only normalized.
      Path candidate = Files.exists(root) ? root.toRealPath() : root.toAbsolutePath().normalize();
      if (!candidate.startsWith(temporary)) {
         throw new IllegalStateException("TestTaskwarrior must live under " + temporary + ", got " + candidate);
      }
   }
}
