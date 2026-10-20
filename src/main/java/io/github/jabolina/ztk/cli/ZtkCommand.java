package io.github.jabolina.ztk.cli;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Option;

import io.github.jabolina.ztk.cli.commands.AddCommand;
import io.github.jabolina.ztk.cli.commands.CheckCommand;
import io.github.jabolina.ztk.cli.commands.ConfigCommand;
import io.github.jabolina.ztk.cli.commands.CurrentCommand;
import io.github.jabolina.ztk.cli.commands.DoneCommand;
import io.github.jabolina.ztk.cli.commands.GoalCommand;
import io.github.jabolina.ztk.cli.commands.GraphCommand;
import io.github.jabolina.ztk.cli.commands.InfoCommand;
import io.github.jabolina.ztk.cli.commands.ListCommand;
import io.github.jabolina.ztk.cli.commands.NewCommand;
import io.github.jabolina.ztk.cli.commands.PaperCommand;
import io.github.jabolina.ztk.cli.commands.ReviewCommand;
import io.github.jabolina.ztk.cli.commands.SearchCommand;
import io.github.jabolina.ztk.cli.commands.StartCommand;
import io.github.jabolina.ztk.cli.commands.StopCommand;
import io.github.jabolina.ztk.cli.commands.WeekCommand;

/**
 * The {@code ztk} root command, grouping every subcommand.
 */
@CommandDefinition(name = "ztk", description = "Zettelkasten and task management on Taskwarrior", generateHelp = true,
      groupCommands = {
            ConfigCommand.class,
            AddCommand.class,
            StartCommand.class,
            StopCommand.class,
            ReviewCommand.class,
            DoneCommand.class,
            CurrentCommand.class,
            WeekCommand.class,
            ListCommand.class,
            InfoCommand.class,
            NewCommand.class,
            SearchCommand.class,
            PaperCommand.class,
            GraphCommand.class,
            CheckCommand.class,
            GoalCommand.class,
})
public class ZtkCommand implements Command<ZtkInvocation> {

   // Global output flags. Declared here so Æsh accepts them in any position and lists them in --help. Their values are
   // read by Ztk.run instead, because Æsh does not pass inherited values down through nested groups.
   @Option(name = "json", hasValue = false, inherited = true, description = "Print results as JSON")
   boolean json;

   @Option(name = "human", hasValue = false, inherited = true, description = "Print results as human text, even when piped")
   boolean human;

   @Option(name = "config", inherited = true, description = "Read the configuration from this file")
   String config;

   @Override
   public CommandResult execute(ZtkInvocation invocation) {
      invocation.println(invocation.getHelpInfo());
      return CommandResult.SUCCESS;
   }
}
