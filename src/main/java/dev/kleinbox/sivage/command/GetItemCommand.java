package dev.kleinbox.sivage.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.kleinbox.sivage.item.ImageItem;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Collection;

public class GetItemCommand {
    public static int executeGetItemForOne(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();

        ItemStack stack = ImageItem.getDefaultConsumable(context.getSource().getLevel());
        if (!player.getInventory().add(stack))
            player.drop(stack.copy(), true, false);

        context.getSource().sendSuccess(
                () -> Component.translatableWithFallback("sivage.chat.item.single", "Gave Custom Image item."),
                false
        );

        return 1;
    }

    public static int executeGetItemForMany(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Collection<ServerPlayer> players = EntityArgument.getPlayers(context, "for");

        ItemStack stack = ImageItem.getDefaultConsumable(context.getSource().getLevel());

        for (ServerPlayer player : players)
            if (!player.getInventory().add(stack))
                player.drop(stack.copy(), true, false);

        context.getSource().sendSuccess(
                () -> (players.size() == 1)
                        ? Component.translatableWithFallback("sivage.chat.item.single", "Gave Custom Image item.")
                        : Component.translatableWithFallback("sivage.chat.item.multiple", "Gave Custom Image item to %s players.", players.size()),
                false
        );

        return 1;
    }

}
