package de.chojo.repbot.commands.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.chojo.jdautil.command.SimpleArgument;
import de.chojo.jdautil.command.dispatching.CommandHub;
import de.chojo.jdautil.localization.Localizer;
import de.chojo.jdautil.localization.util.Language;
import de.chojo.repbot.commands.Channel;
import de.chojo.repbot.commands.Prefix;
import de.chojo.repbot.commands.Reputation;
import de.chojo.repbot.data.GuildData;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;

public class CommandExport {

    public static void main(String[] args) throws IOException {
        var mapper = new ObjectMapper();

        var localizer = Localizer.builder(Language.ENGLISH)
                .addLanguage(Language.GERMAN, Language.of("es_ES", "Español"), Language.of("fr_FR", "Français"),
                        Language.of("pt_PT", "Português"), Language.of("ru_RU", "Русский"))
                .withLanguageProvider(guild -> new GuildData(null).getLanguage(guild))
                .withBundlePath("locale")
                .build();
        var hub = CommandHub.builder(null)
                .withCommands(
                        new Channel(null),
                        new Prefix(null, null),
                        new Reputation(null, null)
                )
                .withLocalizer(localizer)
                .build(true);

        var commands = hub.getCommands().stream().map(command -> {
            var meta = command.meta();
            var subcommandExportEntities = new ArrayList<CommandExportEntity.SubcommandExportEntity>();
            for (var simpleSubCommand : meta.subCommands()) {
                var arguments = new ArrayList<CommandExportEntity.ArgumentExportEntity>();
                for (SimpleArgument arg : simpleSubCommand.args()) {
                    arguments.add(new CommandExportEntity.ArgumentExportEntity(arg.name(), localizer.localize(arg.description()), arg.isRequired(), arg.type()));
                }

                subcommandExportEntities.add(
                        new CommandExportEntity.SubcommandExportEntity(
                                simpleSubCommand.name(),
                                localizer.localize(simpleSubCommand.description()),
                                arguments
                        )
                );
            }

            var arguments = new ArrayList<CommandExportEntity.ArgumentExportEntity>();
            for (SimpleArgument arg : meta.argument()) {
                arguments.add(new CommandExportEntity.ArgumentExportEntity(arg.name(), localizer.localize(arg.description()), arg.isRequired(), arg.type()));
            }

            return new CommandExportEntity(
                    meta.name(),
                    localizer.localize(meta.description()),
                    !meta.defaultEnabled(),
                    !subcommandExportEntities.isEmpty() ? subcommandExportEntities : null,
                    subcommandExportEntities.isEmpty() ? arguments : null
            );
        }).toList();

        var writer = new FileWriter("exported_commands.json");
        mapper.writeValue(writer, commands);
    }
}
