package io.github.jabolina.ztk.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.jabolina.ztk.ZtkException;

/**
 * Locating, parsing and validating the configuration file.
 *
 * <p>
 * Every test uses a temporary {@code HOME} passed through the environment, so the user's real configuration is never
 * read.
 * </p>
 */
class ConfigurationLoaderTest {

   @TempDir
   Path home;

   @Test
   void explicitConfigurationFileWinsOverOtherLocations() throws IOException {
      Path data = Files.createDirectories(home.resolve("cerebro"));
      Path explicit = write(home.resolve("explicit.rc"), "data.location=" + data + "\n");
      write(home.resolve(".ztkrc"), "data.location=" + home.resolve("elsewhere") + "\n");

      Configuration configuration = ConfigurationLoader.load(explicit, environment());

      assertThat(configuration.source()).isEqualTo(explicit);
      assertThat(configuration.dataLocation()).isEqualTo(data);
   }

   @Test
   void missingConfigurationListsSearchedLocationsAndSample() {
      assertThatThrownBy(() -> ConfigurationLoader.load(environment()))
            .isInstanceOfSatisfying(ZtkException.class,
                  e -> assertThat(e.exitCode()).isEqualTo(ZtkException.Kind.USAGE.exitCode()))
            .hasMessageContaining(home.resolve(".config/ztk/ztkrc").toString())
            .hasMessageContaining(home.resolve(".ztkrc").toString())
            .hasMessageContaining("data.location=");
   }

   @Test
   void standardLocationPrefersXdgOverDotFile() throws IOException {
      Path data = Files.createDirectories(home.resolve("cerebro"));
      Path xdg = write(home.resolve(".config/ztk/ztkrc"), "data.location=" + data + "\n");
      Path dotFile = write(home.resolve(".ztkrc"), "data.location=" + data + "\n");

      assertThat(ConfigurationLoader.load(environment()).source()).isEqualTo(xdg);

      Files.delete(xdg);
      assertThat(ConfigurationLoader.load(environment()).source()).isEqualTo(dotFile);
   }

   @Test
   void ztkrcEnvironmentVariableTakesPrecedenceOverStandardLocations() throws IOException {
      Path data = Files.createDirectories(home.resolve("cerebro"));
      Path fromEnv = write(home.resolve("custom/ztkrc"), "data.location=" + data + "\n");
      write(home.resolve(".config/ztk/ztkrc"), "data.location=" + data + "\n");
      write(home.resolve(".ztkrc"), "data.location=" + data + "\n");

      Properties env = environment();
      env.setProperty("ZTKRC", fromEnv.toString());

      assertThat(ConfigurationLoader.load(env).source()).isEqualTo(fromEnv);
   }

   @Test
   void xdgConfigHomeReplacesDefaultConfigDirectory() throws IOException {
      Path data = Files.createDirectories(home.resolve("cerebro"));
      Path custom = write(home.resolve("xdg/ztk/ztkrc"), "data.location=" + data + "\n");
      write(home.resolve(".config/ztk/ztkrc"), "data.location=" + data + "\n");

      Properties env = environment();
      env.setProperty("XDG_CONFIG_HOME", home.resolve("xdg").toString());

      assertThat(ConfigurationLoader.load(env).source()).isEqualTo(custom);
   }

   @Test
   void tildeInValuesExpandsToHomeIgnoringCommentsAndBlankLines() throws IOException {
      Files.createDirectories(home.resolve("cerebro"));
      Path rc = write(home.resolve(".ztkrc"), """
            # knowledge base

            data.location=~/cerebro
            """);

      Configuration configuration = ConfigurationLoader.load(rc, environment());

      assertThat(configuration.dataLocation()).isEqualTo(home.resolve("cerebro"));
   }

   @Test
   void dataLocationMustBeAnExistingDirectory() throws IOException {
      Path missing = home.resolve("missing");
      Path rc = write(home.resolve(".ztkrc"), "data.location=" + missing + "\n");

      assertThatThrownBy(() -> ConfigurationLoader.load(rc, environment()))
            .isInstanceOfSatisfying(ZtkException.class,
                  e -> assertThat(e.exitCode()).isEqualTo(ZtkException.Kind.USAGE.exitCode()))
            .hasMessageContaining("data.location")
            .hasMessageContaining(missing.toString())
            .hasMessageContaining(rc.toString());
   }

   @Test
   void dataLocationIsRequired() throws IOException {
      Path rc = write(home.resolve(".ztkrc"), "editor=vim\n");

      assertThatThrownBy(() -> ConfigurationLoader.load(rc, environment()))
            .isInstanceOfSatisfying(ZtkException.class,
                  e -> assertThat(e.exitCode()).isEqualTo(ZtkException.Kind.USAGE.exitCode()))
            .hasMessageContaining("data.location")
            .hasMessageContaining(rc.toString());
   }

   @Test
   void taskwarriorSettingsHaveDefaultsWhenUnset() throws IOException {
      Path data = Files.createDirectories(home.resolve("cerebro"));
      Path rc = write(home.resolve(".ztkrc"), "data.location=" + data + "\n");

      Configuration configuration = ConfigurationLoader.load(rc, environment());

      assertThat(configuration.taskCommand()).isEqualTo("task");
      assertThat(configuration.taskRc()).isEmpty();
      assertThat(configuration.taskData()).isEmpty();
   }

   @Test
   void taskwarriorSettingsAreReadWithTildeExpanded() throws IOException {
      Path data = Files.createDirectories(home.resolve("cerebro"));
      Path rc = write(home.resolve(".ztkrc"), """
            data.location=%s
            task.command=~/bin/task
            task.rc=~/test.taskrc
            task.data=~/tasks
            """.formatted(data));

      Configuration configuration = ConfigurationLoader.load(rc, environment());

      assertThat(configuration.taskCommand()).isEqualTo(home.resolve("bin/task").toString());
      assertThat(configuration.taskRc()).contains(home.resolve("test.taskrc"));
      assertThat(configuration.taskData()).contains(home.resolve("tasks"));
   }

   @Test
   void editorKeyWinsOverEditorVariableWhichWinsOverNvim() throws IOException {
      Path data = Files.createDirectories(home.resolve("cerebro"));
      Path withKey = write(home.resolve("with-key.rc"), "data.location=" + data + "\neditor=hx\n");
      Path withoutKey = write(home.resolve("without-key.rc"), "data.location=" + data + "\n");

      Properties withEditor = environment();
      withEditor.setProperty("EDITOR", "vim");

      assertThat(ConfigurationLoader.load(withKey, withEditor).editor()).isEqualTo("hx");
      assertThat(ConfigurationLoader.load(withoutKey, withEditor).editor()).isEqualTo("vim");
      assertThat(ConfigurationLoader.load(withoutKey, environment()).editor()).isEqualTo("nvim");
   }

   @Test
   void foldersDefaultToTheirNamesInsideDataLocation() throws IOException {
      Path data = Files.createDirectories(home.resolve("cerebro"));
      Path rc = write(home.resolve(".ztkrc"), "data.location=" + data + "\n");

      // Not required to exist: the first note written into a folder creates it.
      Folders folders = ConfigurationLoader.load(rc, environment()).folders();

      assertThat(folders.zettel()).isEqualTo(data.resolve("zettel"));
      assertThat(folders.papers()).isEqualTo(data.resolve("papers"));
      assertThat(folders.goals()).isEqualTo(data.resolve("goals"));
      assertThat(folders.efforts()).isEqualTo(data.resolve("efforts"));
      assertThat(folders.journal()).isEqualTo(data.resolve("journal"));
      assertThat(folders.summaries()).isEqualTo(data.resolve("summaries"));
   }

   @Test
   void foldersAreRelativeToDataLocationAndMayBeNested() throws IOException {
      Path data = Files.createDirectories(home.resolve("cerebro"));
      Path rc = write(home.resolve(".ztkrc"), """
            data.location=%s
            folder.zettel=notes/zettels
            folder.papers=reading
            """.formatted(data));

      Folders folders = ConfigurationLoader.load(rc, environment()).folders();

      assertThat(folders.zettel()).isEqualTo(data.resolve("notes/zettels"));
      assertThat(folders.papers()).isEqualTo(data.resolve("reading"));
      assertThat(folders.goals()).isEqualTo(data.resolve("goals"));
   }

   @ParameterizedTest
   @ValueSource(strings = { "/elsewhere/zettel", "../outside", "zettel/../../outside" })
   void foldersOutsideDataLocationAreRejected(String folder) throws IOException {
      Path data = Files.createDirectories(home.resolve("cerebro"));
      Path rc = write(home.resolve(".ztkrc"), "data.location=" + data + "\nfolder.zettel=" + folder + "\n");

      // Notes are only ever written inside the knowledge base, and node IDs are paths relative to it.
      assertThatThrownBy(() -> ConfigurationLoader.load(rc, environment()))
            .isInstanceOfSatisfying(ZtkException.class,
                  e -> assertThat(e.exitCode()).isEqualTo(ZtkException.Kind.USAGE.exitCode()))
            .hasMessageContaining("folder.zettel")
            .hasMessageContaining(folder)
            .hasMessageContaining("inside data.location")
            .hasMessageContaining(rc.toString());
   }

   @Test
   void twoKeysNamingTheSameFolderAreRejected() throws IOException {
      Path data = Files.createDirectories(home.resolve("cerebro"));
      // "./zettel" is the default zettel folder written differently: compared after normalizing.
      Path rc = write(home.resolve(".ztkrc"), "data.location=" + data + "\nfolder.papers=./zettel\n");

      // A note's type comes from its folder, so a folder shared by two keys has no single type.
      assertThatThrownBy(() -> ConfigurationLoader.load(rc, environment()))
            .isInstanceOfSatisfying(ZtkException.class,
                  e -> assertThat(e.exitCode()).isEqualTo(ZtkException.Kind.USAGE.exitCode()))
            .hasMessageContaining("folder.papers")
            .hasMessageContaining("folder.zettel")
            .hasMessageContaining(data.resolve("zettel").toString())
            .hasMessageContaining("its own folder")
            .hasMessageContaining(rc.toString());
   }

   @Test
   void unreadableExplicitConfigurationIsAUsageError() {
      Path missing = home.resolve("missing.rc");

      assertThatThrownBy(() -> ConfigurationLoader.load(missing, environment()))
            .isInstanceOfSatisfying(ZtkException.class,
                  e -> assertThat(e.exitCode()).isEqualTo(ZtkException.Kind.USAGE.exitCode()))
            .hasMessageContaining(missing.toString());
   }

   private Properties environment() {
      Properties env = new Properties();
      env.setProperty("HOME", home.toString());
      return env;
   }

   private static Path write(Path file, String content) throws IOException {
      Files.createDirectories(file.getParent());
      return Files.writeString(file, content);
   }
}
