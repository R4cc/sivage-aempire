package dev.kleinbox.sivage.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.datafixers.util.Pair;
import dev.kleinbox.sivage.SivagePermissions;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.UUID;

import static dev.kleinbox.sivage.item.ImageItem.ID_TYPE;
import static dev.kleinbox.sivage.item.ImageItem.SIGNATURE_TYPE;

@SuppressWarnings("UnstableApiUsage")
public class CheckSignatureCommand {
    private static final Component INVALID_ENTITY = Component.translatableWithFallback("sivage.chat.signature.error.entity", "Entity must be an Custom Image.");
    private static final Component INVALID_DATA = Component.translatableWithFallback("sivage.chat.signature.error.data", "Data of Custom Image seems invalid.");

    public static int executeCheckSignature(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Entity entity = EntityArgument.getEntity(context, "image");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();

        if (!SivagePermissions.canCheck(source)) {
            source.sendFailure(SivagePermissions.CHECK_DENIED);
            return 0;
        }

        if (!(entity instanceof ItemFrame frame) || !(frame.hasAttached(ID_TYPE) && frame.hasAttached(SIGNATURE_TYPE))) {
            source.sendFailure(INVALID_ENTITY);
            return 0;
        }

        @Nullable String uuid = entity.getAttached(ID_TYPE);
        @Nullable Pair<String, String> signature = entity.getAttached(SIGNATURE_TYPE);

        if (uuid == null || signature == null) {
            source.sendFailure(INVALID_ENTITY);
            return 0;
        }

        try {
            String url = signature.getSecond();
            Player player = level.getPlayerInAnyDimension(UUID.fromString(signature.getFirst()));
            Component playerName = (player == null)
                    ? Component.translatableWithFallback("sivage.chat.signature.unknown", "[Unknown]")
                    : player.getDisplayName();

            source.sendSuccess(() -> getSingleFeedback(frame.getOnPos(), uuid, url, playerName), true);
            return 1;
        } catch (IllegalArgumentException ignored) {
            source.sendFailure(INVALID_DATA);
            return 0;
        }
    }

    private static Component getSingleFeedback(BlockPos blockPos, String uuid, String url, Component playerName) {
        MutableComponent component = Component.empty();

        component.append(
                Component.translatableWithFallback(
                        "sivage.chat.signature.header",
                        "Custom Image at [%s] has the following signature:",
                        colored(blockPos.toShortString(), ChatFormatting.GRAY)
                )
        );
        component.append(Component.literal("\n"));

        component.append(
                Component.translatableWithFallback(
                        "sivage.chat.signature.uuid",
                        " - UUID: %s",
                        colored(uuid, ChatFormatting.YELLOW)
                ).withStyle(ChatFormatting.GRAY)
        );
        component.append(Component.literal("\n"));

        component.append(
                Component.translatableWithFallback(
                        "sivage.chat.signature.url",
                        " - URL: %s",
                        getClickableUrl(url)
                ).withStyle(ChatFormatting.GRAY)
        );
        component.append(Component.literal("\n"));

        component.append(
                Component.translatableWithFallback(
                        "sivage.chat.signature.creator",
                        " - Created by: %s",
                        Component.empty().append(playerName).withStyle(ChatFormatting.AQUA)
                ).withStyle(ChatFormatting.GRAY)
        );

        return component;
    }

    private static Component colored(String text, ChatFormatting formatting) {
        return Component.literal(text).withStyle(Style.EMPTY.withColor(formatting));
    }

    private static Component getClickableUrl(String url) {
        try {
            URI uri = new URI(url);

            String host = uri.getHost();
            String path = uri.getPath();

            String display = path == null || path.length() <= 20
                    ? path
                    : "/…" + path.substring(path.length() - 20);

            return Component.literal(host + display)
                    .withStyle(Style.EMPTY
                            .withClickEvent(new ClickEvent.OpenUrl(uri))
                            .withHoverEvent(new HoverEvent.ShowText(Component.translatableWithFallback("sivage.chat.signature.link", "Link to source image")))
                            .withUnderlined(true)
                            .withColor(ChatFormatting.BLUE)
                    );
        } catch (URISyntaxException e) {
            return Component.literal(url).withStyle(Style.EMPTY
                    .withUnderlined(true)
                    .withColor(ChatFormatting.BLUE)
            );
        }
    }
}
