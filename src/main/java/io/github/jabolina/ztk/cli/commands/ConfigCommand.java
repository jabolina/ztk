package io.github.jabolina.ztk.cli.commands;

import static io.github.jabolina.ztk.configuration.ConfigurationLoader.DATA_LOCATION;
import static io.github.jabolina.ztk.configuration.ConfigurationLoader.EDITOR;
import static io.github.jabolina.ztk.configuration.ConfigurationLoader.FOLDER_EFFORTS;
import static io.github.jabolina.ztk.configuration.ConfigurationLoader.FOLDER_GOALS;
import static io.github.jabolina.ztk.configuration.ConfigurationLoader.FOLDER_JOURNAL;
import static io.github.jabolina.ztk.configuration.ConfigurationLoader.FOLDER_PAPERS;
import static io.github.jabolina.ztk.configuration.ConfigurationLoader.FOLDER_SUMMARIES;
import static io.github.jabolina.ztk.configuration.ConfigurationLoader.FOLDER_ZETTEL;
import static io.github.jabolina.ztk.configuration.ConfigurationLoader.TASK_COMMAND;

import java.util.LinkedHashMap;
import java.util.Map;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;

import io.github.jabolina.ztk.ZtkException;
import io.github.jabolina.ztk.cli.ZtkInvocation;
import io.github.jabolina.ztk.configuration.Configuration;
import io.github.jabolina.ztk.configuration.Folders;
import tools.jackson.databind.node.ObjectNode;

@CommandDefinition(name = "config", description = "Inspect the configuration", generateHelp = true,
      groupCommands = {
      ConfigCommand.Show.class,
      })
public class ConfigCommand implements Command<ZtkInvocation> {

   @Override
   public CommandResult execute(ZtkInvocation invocation) {
      invocation.println(invocation.getHelpInfo());
      return CommandResult.SUCCESS;
   }

   @CommandDefinition(name = "show", description = "Print the effective configuration", generateHelp = true)
   public static class Show implements Command<ZtkInvocation> {

      @Override
      public CommandResult execute(ZtkInvocation invocation) {
         Configuration configuration;
         try {
            configuration = invocation.context().configuration();
         } catch (ZtkException e) {
            // Unlike other commands, a missing or invalid configuration is this command's answer, not a failure.
            ObjectNode json = invocation.jsonResult();
            json.putNull("source");
            json.put("error", e.getMessage());
            invocation.result(e.getMessage() + System.lineSeparator(), json);
            return CommandResult.SUCCESS;
         }

         // Insertion order is the display order.
         Map<String, String> values = new LinkedHashMap<>();
         values.put(DATA_LOCATION, configuration.dataLocation().toString());

         Folders folders = configuration.folders();
         values.put(FOLDER_ZETTEL, folders.zettel().toString());
         values.put(FOLDER_PAPERS, folders.papers().toString());
         values.put(FOLDER_GOALS, folders.goals().toString());
         values.put(FOLDER_EFFORTS, folders.efforts().toString());
         values.put(FOLDER_JOURNAL, folders.journal().toString());
         values.put(FOLDER_SUMMARIES, folders.summaries().toString());
         values.put(TASK_COMMAND, configuration.taskCommand());

         // Unset Taskwarrior paths are left out: Taskwarrior then uses its own defaults.
         configuration.taskRc().ifPresent(rc -> values.put("task.rc", rc.toString()));
         configuration.taskData().ifPresent(data -> values.put("task.data", data.toString()));
         values.put(EDITOR, configuration.editor());

         StringBuilder human = new StringBuilder("source: ").append(configuration.source()).append('\n');
         ObjectNode json = invocation.jsonResult();
         json.put("source", configuration.source().toString());
         ObjectNode jsonValues = json.putObject("values");
         values.forEach((key, value) -> {
            human.append(key).append('=').append(value).append('\n');
            jsonValues.put(key, value);
         });

         invocation.result(human.toString(), json);
         return CommandResult.SUCCESS;
      }
   }
}
