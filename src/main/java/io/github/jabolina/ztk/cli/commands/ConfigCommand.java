package io.github.jabolina.ztk.cli.commands;

import java.util.LinkedHashMap;
import java.util.Map;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;

import io.github.jabolina.ztk.ZtkException;
import io.github.jabolina.ztk.cli.ZtkInvocation;
import io.github.jabolina.ztk.configuration.Configuration;
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
         values.put("data.location", configuration.dataLocation().toString());
         values.put("folder.zettel", configuration.zettelLocation().toString());
         values.put("task.command", configuration.taskCommand());

         // Unset Taskwarrior paths are left out: Taskwarrior then uses its own defaults.
         configuration.taskRc().ifPresent(rc -> values.put("task.rc", rc.toString()));
         configuration.taskData().ifPresent(data -> values.put("task.data", data.toString()));
         values.put("editor", configuration.editor());

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
