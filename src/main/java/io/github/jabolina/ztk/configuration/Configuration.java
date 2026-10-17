package io.github.jabolina.ztk.configuration;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The effective `ztk` configuration.
 *
 * @param source         The file the configuration was read from.
 * @param dataLocation   The root of the knowledge base.
 * @param zettelLocation The folder for zettels, inside the knowledge base unless configured as an absolute path.
 * @param taskCommand    The Taskwarrior executable to run.
 * @param taskRc         The Taskwarrior configuration file to use instead of the user's default, if set.
 * @param taskData       The Taskwarrior data directory to use instead of the user's default, if set.
 * @param editor         The editor command for interactive commands.
 */
public record Configuration(
      Path source,
      Path dataLocation,
      Path zettelLocation,
      String taskCommand,
      Optional<Path> taskRc,
      Optional<Path> taskData,
      String editor
) { }
