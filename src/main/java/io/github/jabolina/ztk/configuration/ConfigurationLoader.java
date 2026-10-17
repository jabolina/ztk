package io.github.jabolina.ztk.configuration;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

import io.github.jabolina.ztk.ZtkException;

/**
 * Locates, parses and validates the ztk configuration file.
 *
 * @since 1.0
 * @author José Bolina
 */
public final class ConfigurationLoader {

   public static final String DATA_LOCATION = "data.location";
   public static final String FOLDER_ZETTEL = "folder.zettel";
   public static final String FOLDER_PAPERS = "folder.papers";
   public static final String FOLDER_GOALS = "folder.goals";
   public static final String FOLDER_EFFORTS = "folder.efforts";
   public static final String FOLDER_JOURNAL = "folder.journal";
   public static final String FOLDER_SUMMARIES = "folder.summaries";
   public static final String TASK_COMMAND = "task.command";
   public static final String EDITOR = "editor";
   private static final String TASK_RC = "task.rc";
   private static final String TASK_DATA = "task.data";
   private static final String CONFIGURATION_SAMPLE = """
         # ~/.config/ztk/ztkrc
         data.location=~/cerebro
         """;

   private ConfigurationLoader() { }

   /**
    * Loads the effective configuration.
    *
    * @param explicit the configuration file to read, e.g. given with {@code --config}.
    * @param env      the process environment, used to resolve {@code HOME} and {@code EDITOR}.
    * @return the parsed configuration.
    * @throws ZtkException with {@link ZtkException.Kind#USAGE} when the file cannot be read, or when
    *                      {@code data.location} is missing or is not an existing directory.
    * @throws ZtkException with {@link ZtkException.Kind#USAGE} when the file cannot be read, when
    *                      {@code data.location} is missing or is not an existing directory, or when a folder is not
    *                      inside it.
    */
   public static Configuration load(Path explicit, Properties env) {
      Objects.requireNonNull(explicit, "Configuration path is missing");
      Properties loaded = loadConfigurationFile(explicit, env);

      String dl = loaded.getProperty(DATA_LOCATION);
      if (dl == null) {
         throw ZtkException.usage("Missing required key " + DATA_LOCATION + " in " + explicit
               + System.lineSeparator() + CONFIGURATION_SAMPLE);
      }
      Path dataLocation = Path.of(dl).toAbsolutePath();
      requireDirectory(DATA_LOCATION, dataLocation, explicit);

      // Retrieve the attributes for TaskWarrior now.
      String taskCommand = loaded.getProperty(TASK_COMMAND, "task");
      Optional<Path> taskRc = Optional.ofNullable(loaded.getProperty(TASK_RC)).map(Path::of);
      Optional<Path> taskData = Optional.ofNullable(loaded.getProperty(TASK_DATA)).map(Path::of);

      // The configured editor wins; otherwise follow the usual $EDITOR convention, then a fixed fallback.
      String editor = loaded.getProperty(EDITOR, env.getProperty("EDITOR", "nvim"));

      // Folders are relative to the knowledge base, and created when the first note is written into them.
      Folders folders = new Folders(
            folder(loaded, FOLDER_ZETTEL, "zettel", dataLocation, explicit),
            folder(loaded, FOLDER_PAPERS, "papers", dataLocation, explicit),
            folder(loaded, FOLDER_GOALS, "goals", dataLocation, explicit),
            folder(loaded, FOLDER_EFFORTS, "efforts", dataLocation, explicit),
            folder(loaded, FOLDER_JOURNAL, "journal", dataLocation, explicit),
            folder(loaded, FOLDER_SUMMARIES, "summaries", dataLocation, explicit)
      );
      requireDistinct(folders, explicit);
      return new Configuration(explicit, dataLocation, folders, taskCommand, taskRc, taskData, editor);
   }

   /**
    * Loads the configuration from the first file found in the standard locations.
    *
    * <p>
    * The locations are searched in order: {@code $ZTKRC}, {@code $XDG_CONFIG_HOME/ztk/ztkrc} (defaulting to
    * {@code ~/.config/ztk/ztkrc}), then {@code ~/.ztkrc}.
    * </p>
    *
    * @param env the process environment, used to resolve {@code HOME} and the other locations.
    * @return the parsed configuration.
    * @throws ZtkException with {@link ZtkException.Kind#USAGE} when no configuration file exists. The message lists
    *                      every location searched and a sample configuration.
    */
   public static Configuration load(Properties env) {
      List<Path> candidates = candidates(env);
      for (Path candidate : candidates) {
         if (Files.isRegularFile(candidate))
            return load(candidate, env);
      }

      StringBuilder message = new StringBuilder("No ztk configuration found. Searched in:");
      for (Path candidate : candidates) {
         message.append(System.lineSeparator())
               .append('\t')
               .append(candidate);
      }
      message.append(System.lineSeparator())
            .append("Create a configuration file, for example:")
            .append(System.lineSeparator())
            .append(CONFIGURATION_SAMPLE)
            .append(System.lineSeparator());
      throw ZtkException.usage(message.toString());
   }

   /**
    * Reads a configuration file and expands a leading {@code ~} in every value.
    *
    * @param explicit the configuration file to read.
    * @param env the process environment, used to resolve {@code HOME}.
    * @return the configuration values, with {@code ~} expanded.
    * @throws ZtkException with {@link ZtkException.Kind#USAGE} when the file cannot be read.
    */
   private static Properties loadConfigurationFile(Path explicit, Properties env) {
      Properties loaded = new Properties();
      try (BufferedReader reader = Files.newBufferedReader(explicit)) {
         loaded.load(reader);
      } catch (IOException e) {
         // Missing or unreadable file (e.g. a typo in --config): report it as a usage error naming the file.
         throw ZtkException.usage("Cannot read configuration " + explicit + ": " + e.getMessage());
      }

      // A second pass on all attributes so we can expand file locations.
      Properties actual = new Properties();
      for (String key : loaded.stringPropertyNames()) {
         actual.setProperty(key, expandHomeInPath(loaded.getProperty(key), env));
      }

      return actual;
   }

   /**
    * Lists the configuration locations in lookup order.
    *
    * @param env the process environment, used to resolve {@code HOME}, {@code ZTKRC} and {@code XDG_CONFIG_HOME}.
    * @return the candidate configuration files, most preferred first. They may not exist.
    */
   private static List<Path> candidates(Properties env) {
      Path home = Path.of(env.getProperty("HOME"));
      List<Path> candidates = new ArrayList<>();

      // An explicit $ZTKRC is only a preference: if the file does not exist, the standard locations are still tried.
      String ztkrc = env.getProperty("ZTKRC");
      if (ztkrc != null) {
         candidates.add(Path.of(ztkrc));
      }

      // XDG Base Directory: $XDG_CONFIG_HOME replaces ~/.config when set.
      String xdgConfigHome = env.getProperty("XDG_CONFIG_HOME");
      Path configDirectory = xdgConfigHome != null ? Path.of(xdgConfigHome) : home.resolve(".config");
      candidates.add(configDirectory.resolve("ztk").resolve("ztkrc"));

      // Or maybe a file at the home directory.
      candidates.add(home.resolve(".ztkrc"));
      return candidates;
   }

   /**
    * Expands a leading {@code ~} to the user's home directory.
    *
    * <p>
    * Only {@code ~} alone or followed by {@code /} is expanded; {@code ~user} forms are left untouched.
    * </p>
    *
    * @param value the configuration value, possibly starting with {@code ~}.
    * @param env the process environment, used to resolve {@code HOME}.
    * @return the value with a leading {@code ~} replaced by the home directory, or the value unchanged.
    */
   private static String expandHomeInPath(String value, Properties env) {
      String home = env.getProperty("HOME");
      if (value.equals("~")) {
         return home;
      }
      if (value.startsWith("~/")) {
         return home + value.substring(1);
      }
      return value;
   }

   /**
    * Ensures a configured path exists and is a directory.
    *
    * @param property the configuration key the path was read from, reported in the error message.
    * @param directory the resolved directory path to check.
    * @param source the configuration file the value was read from, reported in the error message.
    * @throws ZtkException with {@link ZtkException.Kind#USAGE} when the path does not exist or is not a directory.
    */
   private static void requireDirectory(String property, Path directory, Path source) {
      if (!Files.isDirectory(directory)) {
         throw ZtkException.usage(property + " is not a directory: " + directory + " (from " + source + ")");
      }
   }

   /**
    * Resolves a configured folder inside the knowledge base.
    *
    * <p>
    * The value is a path relative to {@code data.location}, possibly nested. The folder does not need to exist: the
    * first note written into it creates it.
    * </p>
    *
    * @param loaded the configuration values.
    * @param property the configuration key holding the folder, e.g. {@code folder.zettel}.
    * @param defaultValue the folder used when the key is not set.
    * @param dataLocation the root of the knowledge base.
    * @param source the configuration file the value was read from, reported in the error message.
    * @return the absolute, normalized folder path, inside {@code dataLocation}.
    * @throws ZtkException with {@link ZtkException.Kind#USAGE} when the value is absolute or leaves the knowledge base.
    */
   private static Path folder(Properties loaded, String property, String defaultValue, Path dataLocation, Path source) {
      String value = loaded.getProperty(property, defaultValue);
      // Path.resolve returns an absolute argument unchanged, and ".." is only removed by normalize(): check the
      // normalized result, so "/elsewhere" and "zettel/../../outside" are both caught.
      Path folder = dataLocation.resolve(value).normalize();
      if (!folder.startsWith(dataLocation) || folder.equals(dataLocation)) {
         throw ZtkException.usage(property + " must be a folder inside data.location, e.g. " + property + "="
               + defaultValue + ": " + value + " (from " + source + ")");
      }
      return folder;
   }

   /**
    * Ensures no two folder keys name the same folder.
    *
    * @param folders the resolved folders.
    * @param source the configuration file the folders were read from, reported in the error message.
    * @throws ZtkException with {@link ZtkException.Kind#USAGE} when two keys resolve to the same folder.
    */
   private static void requireDistinct(Folders folders, Path source) {
      // Insertion order keeps the error stable: the later key is reported as the one to change.
      Map<String, Path> folderByKey = new LinkedHashMap<>();
      folderByKey.put(FOLDER_ZETTEL, folders.zettel());
      folderByKey.put(FOLDER_PAPERS, folders.papers());
      folderByKey.put(FOLDER_GOALS, folders.goals());
      folderByKey.put(FOLDER_EFFORTS, folders.efforts());
      folderByKey.put(FOLDER_JOURNAL, folders.journal());
      folderByKey.put(FOLDER_SUMMARIES, folders.summaries());

      // Paths are already normalized by folder(), so equal folders are equal paths.
      Map<Path, String> keyByFolder = new HashMap<>();
      for (Map.Entry<String, Path> entry : folderByKey.entrySet()) {
         String previous = keyByFolder.putIfAbsent(entry.getValue(), entry.getKey());
         if (previous != null) {
            throw ZtkException.usage(entry.getKey() + " names the same folder as " + previous + ": "
                  + entry.getValue() + ". Give " + entry.getKey() + " its own folder (from " + source + ")");
         }
      }
   }
}
