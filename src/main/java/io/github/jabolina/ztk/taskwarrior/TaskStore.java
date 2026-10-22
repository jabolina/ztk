package io.github.jabolina.ztk.taskwarrior;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.jabolina.ztk.ZtkException;
import io.github.jabolina.ztk.configuration.Configuration;
import io.github.jabolina.ztk.util.Concurrency;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads and writes Taskwarrior tasks.
 *
 * <p>
 * This is the only class that runs the Taskwarrior executable. It honors the configured {@code task.command},
 * {@code task.rc} and {@code task.data}, and reads tasks through {@code task export}.
 * </p>
 *
 * @since 1.0
 * @author José Bolina
 */
public final class TaskStore {

   // Jackson to parse Taskwarrior's output as JSON.
   private static final JsonMapper JSON = JsonMapper.shared();

   // Leading major version of "task --version", e.g. "3" in "3.5.0".
   private static final Pattern MAJOR_VERSION = Pattern.compile("^(\\d+)\\.");

   // A working-set ID (positive integer) or a full UUID, as printed by Taskwarrior.
   private static final Pattern REFERENCE = Pattern.compile("[1-9]\\d*|[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");

   // With verbose=new-uuid, "task add" reports the created task as "Created task <uuid>."
   private static final Pattern CREATED = Pattern.compile("Created task ([0-9a-f-]{36})");
   // Taskwarrior's export dates, always in UTC, e.g. 20261001T030000Z.
   private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssX");

   static final String NOTE_UDA = "note";

   /**
    * ztk's attribute holding the slug of a task's effort; {@code effort:<slug>} sets it, {@code effort:} clears it.
    */
   public static final String EFFORT_UDA = "effort";

   private final Configuration configuration;

   /**
    * Creates a store that runs Taskwarrior as configured.
    *
    * @param configuration the effective configuration, providing the executable and the optional rc and data paths.
    */
   public TaskStore(Configuration configuration) {
      this.configuration = configuration;
      requireSupportedVersion();
   }

   /**
    * Lists the tasks matching a Taskwarrior filter.
    *
    * @param filter the filter terms, e.g. {@code "project:Alpha", "+week"}. Empty matches every task.
    * @return the matching tasks, in Taskwarrior's export order.
    */
   public List<Task> query(String... filter) {
      List<String> args = new ArrayList<>();
      // Without json.array, export prints one object per line instead of a JSON array.
      args.add("rc.json.array=on");
      args.addAll(List.of(filter));
      args.add("export");

      List<Task> tasks = new ArrayList<>();
      for (JsonNode node : JSON.readTree(run(args))) {
         tasks.add(toTask(node));
      }
      return tasks;
   }

   /**
    * Lists the IDs of the tasks that have one.
    *
    * <p>
    * Taskwarrior gives IDs only to pending and waiting tasks; completed and deleted tasks are known by their UUID.
    * </p>
    *
    * @return the task IDs, in ascending order.
    * @throws ZtkException with {@link ZtkException.Kind#TASKWARRIOR} when Taskwarrior fails.
    */
   public List<String> ids() {
      // A helper command for shell completion: one ID per line, without a report around it.
      return run(List.of("_ids")).lines()
            .filter(id -> !id.isBlank())
            .toList();
   }

   /**
    * Lists the projects that still have work to do.
    *
    * <p>
    * Only projects with pending tasks are listed, sub-projects included (e.g. {@code Alpha} and {@code Alpha.Docs}).
    * Projects whose tasks are all completed or deleted are left out.
    * </p>
    *
    * @return the project names, in Taskwarrior's order.
    * @throws ZtkException with {@link ZtkException.Kind#TASKWARRIOR} when Taskwarrior fails.
    */
   public List<String> projects() {
      // A helper command meant for shell completion: one project per line.
      return run(List.of("_projects")).lines().toList();
   }

   /**
    * Lists the tags of the tasks still open.
    *
    * <p>
    * Only tags set on pending or waiting tasks are listed. Taskwarrior's virtual tags, such as {@code ACTIVE}, are left
    * out: they describe a task's state and can never be added to one.
    * </p>
    *
    * @return the tag names, without the leading {@code +}, sorted and without duplicates.
    * @throws ZtkException with {@link ZtkException.Kind#TASKWARRIOR} when Taskwarrior fails.
    */
   public List<String> tags() {
      // Taskwarrior's _tags helper mixes in the virtual tags, so the tags are read from the open tasks instead.
      return query("-COMPLETED", "-DELETED").stream()
            .flatMap(task -> task.tags().stream())
            .distinct()
            .sorted()
            .toList();
   }

   /**
    * Renders a Taskwarrior report as Taskwarrior prints it at a terminal, for people to read.
    *
    * <p>
    * The table always has ANSI colors; callers remove them when colors are not wanted.
    * </p>
    *
    * @param report the report to render.
    * @return the report table, with column labels and colors.
    * @throws ZtkException with {@link ZtkException.Kind#TASKWARRIOR} when Taskwarrior fails, including when nothing
    *                      matches the report's filter.
    */
   public String render(Report report) {
      List<String> args = new ArrayList<>();
      // Overrides the base command's rc.verbose=nothing: the later setting wins, and people need the column labels.
      args.add("rc.verbose=label");
      // Captured output is not a terminal, so Taskwarrior would drop colors and wrap at 80 columns.
      // Width 0 never wraps; the terminal wraps long lines itself.
      args.add("rc.defaultwidth=0");
      args.add("rc._forcecolor=on");
      args.addAll(report.arguments());
      args.add(report.name());
      return run(args);
   }

   /**
    * Resolves a single task by its working-set ID or its UUID.
    *
    * @param reference the numeric ID shown by Taskwarrior, or the task UUID.
    * @return the referenced task.
    * @throws ZtkException with {@link ZtkException.Kind#NOT_FOUND} when no task has that ID or UUID.
    * @throws ZtkException with {@link ZtkException.Kind#USAGE} when {@code reference} is neither an ID nor a UUID.
    */
   public Task get(String reference) {
      // Anything else would be passed to Taskwarrior as a filter and could match an arbitrary task.
      if (!REFERENCE.matcher(reference).matches()) {
         throw ZtkException.usage("Not a task ID or UUID: " + reference);
      }

      // A bare ID or UUID is a valid Taskwarrior filter matching at most one task.
      List<Task> tasks = query(reference);
      if (tasks.isEmpty()) {
         // Completed and deleted tasks lose their ID, so only their UUID still finds them.
         throw ZtkException.notFound("Task " + reference + " not found."
               + System.lineSeparator()
               + "Run 'ztk list' to see the pending tasks and their IDs, or use the task UUID.");
      }
      return tasks.getFirst();
   }

   /**
    * Creates a task.
    *
    * @param words the description words and Taskwarrior attributes, e.g. {@code "fix", "bug", "project:Alpha"}.
    * @return the created task, as stored by Taskwarrior.
    * @throws ZtkException with {@link ZtkException.Kind#TASKWARRIOR} when Taskwarrior rejects the task or does not
    *                      report the created task.
    */
   public Task add(List<String> words) {
      List<String> args = new ArrayList<>();
      // Overrides the rc.verbose=nothing set by run(): this is the only way to learn the new task's UUID.
      args.add("rc.verbose=new-uuid");
      args.add("add");
      args.addAll(words);

      String stdout = run(args);
      Matcher created = CREATED.matcher(stdout);
      if (!created.find()) {
         throw ZtkException.taskwarrior("Taskwarrior did not report the created task: " + stdout);
      }
      return get(created.group(1));
   }

   /**
    * Starts a task, applying modifications in the same Taskwarrior command.
    *
    * @param task the task to start.
    * @param modifications Taskwarrior modifications to apply while starting, e.g. {@code "+week", "-review"}.
    * @throws ZtkException with {@link ZtkException.Kind#TASKWARRIOR} when Taskwarrior rejects the change, e.g. the
    *                      task is already started.
    */
   public void start(Task task, List<String> modifications) {
      change(task, "start", modifications);
   }

   /**
    * Stops a started task.
    *
    * @param task the task to stop.
    * @throws ZtkException with {@link ZtkException.Kind#TASKWARRIOR} when Taskwarrior rejects the change, e.g. the
    *                      task is not started.
    */
   public void stop(Task task) {
      change(task, "stop", List.of());
   }

   /**
    * Changes attributes of a task.
    *
    * @param task the task to change.
    * @param modifications Taskwarrior modifications, e.g. {@code "project:Beta", "+review"}.
    * @throws ZtkException with {@link ZtkException.Kind#TASKWARRIOR} when Taskwarrior rejects the change.
    */
   public void modify(Task task, List<String> modifications) {
      change(task, "modify", modifications);
   }

   /**
    * Marks a task as completed, applying modifications in the same Taskwarrior command.
    *
    * <p>
    * Completing an active task also stops it.
    * </p>
    *
    * @param task the task to complete.
    * @param modifications Taskwarrior modifications to apply while completing, e.g. {@code "-week"}.
    * @throws ZtkException with {@link ZtkException.Kind#TASKWARRIOR} when Taskwarrior rejects the change, e.g. the
    *                      task is already completed.
    */
   public void done(Task task, List<String> modifications) {
      change(task, "done", modifications);
   }

   /**
    * Links a task to its note.
    *
    * @param task the task to link.
    * @param note the note path, relative to the knowledge base.
    * @throws ZtkException with {@link ZtkException.Kind#TASKWARRIOR} when Taskwarrior rejects the change.
    */
   public void setNote(Task task, Path note) {
      modify(task, List.of(NOTE_UDA + ":" + note));
   }

   /**
    * Runs a Taskwarrior command on a single task.
    *
    * @param task the task to change.
    * @param command the Taskwarrior command, e.g. {@code start} or {@code modify}.
    * @param modifications Taskwarrior modifications passed after the command.
    * @throws ZtkException with {@link ZtkException.Kind#TASKWARRIOR} when Taskwarrior rejects the change.
    */
   private void change(Task task, String command, List<String> modifications) {
      List<String> args = new ArrayList<>();
      // The UUID selects exactly this task, even if working-set IDs shifted since it was read.
      args.add(task.uuid());
      args.add(command);
      args.addAll(modifications);
      run(args);
   }

   /**
    * Runs Taskwarrior with the configured environment.
    *
    * @param args the Taskwarrior arguments, after the executable.
    * @return the standard output.
    * @throws ZtkException with {@link ZtkException.Kind#TASKWARRIOR} when the executable cannot be started.
    * @throws ZtkException with {@link ZtkException.Kind#TASKWARRIOR} when the executable cannot be started or exits
    *                      with a non-zero code. The message includes Taskwarrior's stderr.
    */
   private String run(Collection<String> args) {
      List<String> command = prepareTaskwarriorCommand();
      command.addAll(args);

      return execute(command, args);
   }

   /**
    * Starts a Taskwarrior process with the configured environment and collects its output.
    *
    * @param command the full command line, starting with the executable.
    * @param args the arguments to show in error messages, without ztk's internal overrides.
    * @return the standard output.
    * @throws ZtkException with {@link ZtkException.Kind#TASKWARRIOR} when the executable cannot be started or exits
    *                      with a non-zero code. The message includes Taskwarrior's stderr.
    */
   private String execute(List<String> command, Collection<String> args) {
      ProcessBuilder builder = new ProcessBuilder(command);
      configuration.taskRc().ifPresent(rc -> builder.environment().put("TASKRC", rc.toString()));
      configuration.taskData().ifPresent(data -> builder.environment().put("TASKDATA", data.toString()));

      try {
         Process process = builder.start();
         CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> readAll(process.getErrorStream()), Concurrency.executor());
         String stdout = readAll(process.getInputStream());
         int exitCode = process.waitFor();
         // In case the process has failed, we'll join to collect the stderr from taskwarrior.
         // Since the process has already returned with an exit code, the future should be completed, too.
         if (exitCode != 0) {
            // Taskwarrior explains most rejections on stdout (e.g. "Task 2 'bar' not started.") and some errors on
            // stderr (e.g. an unknown column), so the reason is whichever of the two it wrote.
            String reason = (stdout.strip() + System.lineSeparator() + stderr.join().strip()).strip();
            throw ZtkException.taskwarrior("Taskwarrior failed (exit " + exitCode + "): " + String.join(" ", args)
                  + System.lineSeparator() + reason);
         }

         // Operation succeeded
         return stdout;
      } catch (IOException e) {
         throw ZtkException.taskwarrior("Cannot run " + configuration.taskCommand() + ": " + e.getMessage());
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
         throw ZtkException.taskwarrior("Interrupted while running " + configuration.taskCommand());
      }
   }

   private List<String> prepareTaskwarriorCommand() {
      List<String> command = new ArrayList<>();
      command.add(configuration.taskCommand());
      // The user's .taskrc may be verbose or ask for confirmation.
      // ztk needs bare, non-interactive output.
      command.add("rc.verbose=nothing");
      command.add("rc.confirmation=off");

      // Define ztk's UDAs for this invocation only, without touching the user's .taskrc. Without the definition,
      // Taskwarrior reads "note:<path>" as description text, and "modify note:<path>" replaces the description.
      command.add("rc.uda." + NOTE_UDA + ".type=string");
      command.add("rc.uda." + NOTE_UDA + ".label=Note");
      command.add("rc.uda." + EFFORT_UDA + ".type=string");
      command.add("rc.uda." + EFFORT_UDA + ".label=Effort");
      return command;
   }

   /**
    * Ensures the configured Taskwarrior is version 3.0 or newer, checking only on the first call.
    *
    * @throws ZtkException with {@link ZtkException.Kind#TASKWARRIOR} when the version is older or cannot be read.
    */
   private void requireSupportedVersion() {
      // --version is only recognized as the first argument, so ztk's rc. overrides must not precede it.
      String version = execute(List.of(configuration.taskCommand(), "--version"), List.of("--version")).strip();
      Matcher major = MAJOR_VERSION.matcher(version);
      // An unrecognizable answer is treated as unsupported rather than guessed at.
      if (!major.find() || Integer.parseInt(major.group(1)) < 3) {
         throw ZtkException.taskwarrior("ztk requires Taskwarrior 3.0 or newer, found: " + version);
      }
   }

   /**
    * Converts one object of {@code task export} into a task.
    *
    * @param node the exported JSON object.
    * @return the task it describes.
    */
   private static Task toTask(JsonNode node) {
      List<String> tags = new ArrayList<>();
      for (JsonNode tag : node.path("tags")) {
         tags.add(tag.asString());
      }
      return new Task(
            node.path("id").asInt(0),
            node.path("uuid").asString(),
            node.path("description").asString(),
            Status.parse(node.path("status").asString()),
            node.optional("project").map(JsonNode::asString),
            List.copyOf(tags),
            node.optional(NOTE_UDA).map(JsonNode::asString),
            // The user's own attribute: read when present, never defined by ztk.
            node.optional("issue").map(JsonNode::asString),
            node.optional(EFFORT_UDA).map(JsonNode::asString),
            // Taskwarrior sets both on every task it creates.
            instant(node.path("entry")),
            instant(node.path("modified")),
            // Taskwarrior only exports "start" while the task is started, stopping it removes the field.
            node.optional("start").map(TaskStore::instant),
            node.optional("end").map(TaskStore::instant));
   }

   /**
    * @param date a date as Taskwarrior exports it, e.g. {@code 20261001T030000Z}.
    * @return the instant it names.
    */
   private static Instant instant(JsonNode date) {
      return OffsetDateTime.parse(date.asString(), DATE).toInstant();
   }

   /**
    * Reads a process stream to the end.
    *
    * @param stream the process output or error stream.
    * @return the stream content, decoded as UTF-8.
    * @throws UncheckedIOException when the stream cannot be read.
    */
   private static String readAll(InputStream stream) {
      try {
         return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      } catch (IOException e) {
         throw new UncheckedIOException(e);
      }
   }
}
