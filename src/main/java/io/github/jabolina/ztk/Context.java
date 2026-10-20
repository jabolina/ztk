package io.github.jabolina.ztk;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Properties;

import io.github.jabolina.ztk.configuration.Configuration;
import io.github.jabolina.ztk.configuration.ConfigurationLoader;
import io.github.jabolina.ztk.graph.KnowledgeGraph;
import io.github.jabolina.ztk.notes.KnowledgeBase;
import io.github.jabolina.ztk.notes.Papers;
import io.github.jabolina.ztk.notes.Zettels;
import io.github.jabolina.ztk.planning.Goals;
import io.github.jabolina.ztk.search.Search;
import io.github.jabolina.ztk.tasks.Tasks;
import io.github.jabolina.ztk.taskwarrior.TaskStore;

/**
 * The dependencies of one ztk invocation.
 *
 * <p>
 * Every dependency is created on first use, so commands that do not need a configuration (such as {@code --help})
 * work without one. Commands reach the context through {@link io.github.jabolina.ztk.cli.ZtkInvocation#context()}.
 * </p>
 */
public final class Context {

   private final Properties env;
   private final boolean tty;
   private final OutputMode mode;
   private final Path configurationFile;

   // Internal objects created lazily, when requested by the command handlers.
   private Configuration configuration;
   private TaskStore store;
   private Tasks tasks;
   private Zettels zettels;
   private Search search;
   private Papers papers;
   private Goals goals;

   /**
    * @param env the process environment.
    * @param tty whether stdout is a terminal.
    * @param mode the output mode requested on the command line.
    * @param configurationFile the configuration file given with {@code --config}, if any.
    */
   public Context(Properties env, boolean tty, OutputMode mode, Path configurationFile) {
      this.env = env;
      this.tty = tty;
      this.mode = mode;
      this.configurationFile = configurationFile;
   }

   /**
    * @return the effective configuration, loaded on first use.
    * @throws ZtkException with {@link ZtkException.Kind#USAGE} when no valid configuration exists.
    */
   public Configuration configuration() {
      if (configuration == null) {
         configuration = configurationFile != null
               ? ConfigurationLoader.load(configurationFile, env)
               : ConfigurationLoader.load(env);
      }
      return configuration;
   }

   /**
    * @return the Taskwarrior store, created on first use.
    * @throws ZtkException when the configuration is missing or invalid, or Taskwarrior is unsupported.
    */
   public TaskStore store() {
      if (store == null) {
         store = new TaskStore(configuration());
      }
      return store;
   }

   /**
    * @return the task use cases, created on first use.
    * @throws ZtkException when the configuration is missing or invalid, or Taskwarrior is unsupported.
    */
   public Tasks tasks() {
      if (tasks == null) {
         tasks = new Tasks(configuration(), store(), Clock.systemDefaultZone());
      }
      return tasks;
   }

   /**
    * @return the zettel service, created on first use.
    * @throws ZtkException with {@link ZtkException.Kind#USAGE} when no valid configuration exists.
    */
   public Zettels zettels() {
      if (zettels == null) {
         zettels = new Zettels(configuration(), Clock.systemDefaultZone());
      }
      return zettels;
   }

   /**
    * @return the note search, created on first use.
    * @throws ZtkException with {@link ZtkException.Kind#USAGE} when no valid configuration exists.
    */
   public Search search() {
      if (search == null) {
         search = new Search(knowledgeBase());
      }
      return search;
   }

   /**
    * @return the service that files papers, created on first use.
    */
   public Papers papers() {
      if (papers == null) {
         papers = new Papers(configuration(), Clock.systemDefaultZone());
      }
      return papers;
   }

   /**
    * @return the notes of the knowledge base, read anew on each use.
    * @throws ZtkException with {@link ZtkException.Kind#USAGE} when no valid configuration exists.
    */
   public KnowledgeBase knowledgeBase() {
      return new KnowledgeBase(configuration(), goals());
   }

   /**
    * Reads the graph of the knowledge base: its notes, and the tasks and projects of Taskwarrior.
    *
    * <p>
    * Read again on every call, as the notes and tasks are now.
    * </p>
    *
    * @return the graph of the knowledge base, with the notes it skipped.
    * @throws ZtkException with {@link ZtkException.Kind#USAGE} when no valid configuration exists, and with
    *                      {@link ZtkException.Kind#TASKWARRIOR} when Taskwarrior fails.
    */
   public KnowledgeGraph graph() {
      return KnowledgeGraph.read(knowledgeBase(), store());
   }

   /**
    * @return the goals of the knowledge base, created on first use.
    * @throws ZtkException with {@link ZtkException.Kind#USAGE} when no valid configuration exists.
    */
   public Goals goals() {
      if (goals == null) {
         goals = new Goals(configuration(), Clock.systemDefaultZone());
      }
      return goals;
   }

   /**
    * @return whether results are printed as JSON rather than human text.
    */
   public boolean json() {
      return switch (mode) {
         case JSON -> true;
         case HUMAN -> false;

         // Piped or redirected output is read by programs and agents.
         case AUTO -> !tty;
      };
   }

   /**
    * @return whether output may use ANSI colors.
    */
   public boolean colors() {
      // Colors only help people at a terminal. NO_COLOR turns them off when present, whatever its value, even empty.
      return tty && !env.containsKey("NO_COLOR");
   }

   /**
    * @return whether ztk runs in an interactive session, where an editor can be opened.
    */
   public boolean interactive() {
      return tty;
   }

   /**
    * How results are printed.
    */
   public enum OutputMode {
      /**
       * JSON when stdout is not a terminal, human text otherwise.
       */
      AUTO,

      /**
       * Always JSON, as requested with {@code --json}.
       */
      JSON,
      /**
       * Always human text, as requested with {@code --human}.
       */
      HUMAN
   }
}
