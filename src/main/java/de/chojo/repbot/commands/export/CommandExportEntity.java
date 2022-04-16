package de.chojo.repbot.commands.export;

import net.dv8tion.jda.api.interactions.commands.OptionType;

import java.util.List;

public record CommandExportEntity(
        String name,
        String description,
        boolean requiresManagerRole,
        List<SubcommandExportEntity> subcommands,
        List<ArgumentExportEntity> arguments
) {
    public record SubcommandExportEntity(String name, String description, List<ArgumentExportEntity> arguments) {
    }

    public record ArgumentExportEntity(String name, String description, boolean required, OptionType type) {
    }
}
