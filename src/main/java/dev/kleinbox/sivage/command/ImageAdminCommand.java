package dev.kleinbox.sivage.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.datafixers.util.Pair;
import dev.kleinbox.sivage.item.ImageItem;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

public class ImageAdminCommand {
    public static int executeListAll(CommandContext<CommandSourceStack> context) {
        List<ImageInfo> images = getImages(context.getSource(), null);
        sendImageList(context.getSource(), images, null);
        return images.size();
    }

    public static int executeListForUser(CommandContext<CommandSourceStack> context) {
        @Nullable String owner = resolveOwner(context);
        if (owner == null) {
            sendUnknownUser(context.getSource(), StringArgumentType.getString(context, "user"));
            return 0;
        }

        List<ImageInfo> images = getImages(context.getSource(), owner);
        sendImageList(context.getSource(), images, owner);
        return images.size();
    }

    public static int executeDeleteForUser(CommandContext<CommandSourceStack> context) {
        @Nullable String owner = resolveOwner(context);
        if (owner == null) {
            sendUnknownUser(context.getSource(), StringArgumentType.getString(context, "user"));
            return 0;
        }

        List<ImageInfo> images = getImages(context.getSource(), owner);
        int frames = 0;

        for (ImageInfo image : images) {
            for (ItemFrame frame : image.frames) {
                frame.remove(Entity.RemovalReason.KILLED);
                frames++;
            }
        }

        int removedImages = images.size();
        int removedFrames = frames;
        context.getSource().sendSuccess(
                () -> Component.translatableWithFallback(
                        "sivage.chat.images.deleted",
                        "Deleted %s Sivage image(s) with %s frame(s) for %s.",
                        removedImages,
                        removedFrames,
                        owner
                ),
                true
        );
        return removedImages;
    }

    public static StringArgumentType userArgument() {
        return StringArgumentType.word();
    }

    private static List<ImageInfo> getImages(CommandSourceStack source, @Nullable String owner) {
        HashMap<UUID, ImageInfo> images = new HashMap<>();

        for (ServerLevel level : source.getServer().getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof ItemFrame frame) || !frame.hasAttached(ImageItem.ID_TYPE) || !frame.hasAttached(ImageItem.SIGNATURE_TYPE))
                    continue;

                Pair<String, String> signature = frame.getAttachedOrThrow(ImageItem.SIGNATURE_TYPE);
                if (owner != null && !owner.equals(signature.getFirst()))
                    continue;

                UUID imageId;
                try {
                    imageId = UUID.fromString(frame.getAttachedOrThrow(ImageItem.ID_TYPE));
                } catch (IllegalArgumentException ignored) {
                    continue;
                }

                images.computeIfAbsent(imageId, (ignored) -> new ImageInfo(imageId, signature, level)).add(frame);
            }
        }

        return new ArrayList<>(images.values());
    }

    private static void sendImageList(CommandSourceStack source, List<ImageInfo> images, @Nullable String owner) {
        MutableComponent message = Component.empty();
        message.append(Component.translatableWithFallback(
                owner == null ? "sivage.chat.images.list.header.all" : "sivage.chat.images.list.header.user",
                owner == null ? "Found %s Sivage image(s):" : "Found %s Sivage image(s) for %s:",
                images.size(),
                owner
        ).withStyle(ChatFormatting.GOLD));

        if (images.isEmpty()) {
            source.sendSuccess(() -> message, false);
            return;
        }

        for (ImageInfo image : images) {
            message.append(Component.literal("\n"));
            message.append(Component.translatableWithFallback(
                    "sivage.chat.images.list.entry",
                    " - %s in %s at %s, owner %s, %s frame(s)",
                    image.imageId.toString(),
                    image.level.dimension().location().toString(),
                    image.bounds(),
                    image.signature.getFirst(),
                    image.frames.size()
            ).withStyle(ChatFormatting.GRAY));
        }

        source.sendSuccess(() -> message, false);
    }

    private static void sendUnknownUser(CommandSourceStack source, String user) {
        source.sendFailure(Component.translatableWithFallback(
                "sivage.chat.images.unknown_user",
                "Unknown player '%s'. Use an online player name or UUID.",
                user
        ));
    }

    private static @Nullable String resolveOwner(CommandContext<CommandSourceStack> context) {
        String user = StringArgumentType.getString(context, "user");

        try {
            return UUID.fromString(user).toString();
        } catch (IllegalArgumentException ignored) {
            // Try online player names below.
        }

        for (ServerPlayer player : context.getSource().getServer().getPlayerList().getPlayers()) {
            if (player.getGameProfile().getName().equalsIgnoreCase(user))
                return player.getStringUUID();
        }

        return null;
    }

    private static final class ImageInfo {
        private final UUID imageId;
        private final Pair<String, String> signature;
        private final ServerLevel level;
        private final List<ItemFrame> frames = new ArrayList<>();

        private ImageInfo(UUID imageId, Pair<String, String> signature, ServerLevel level) {
            this.imageId = imageId;
            this.signature = signature;
            this.level = level;
        }

        private void add(ItemFrame frame) {
            frames.add(frame);
        }

        private String bounds() {
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxY = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;

            for (ItemFrame frame : frames) {
                BlockPos pos = frame.getOnPos();
                minX = Math.min(minX, pos.getX());
                minY = Math.min(minY, pos.getY());
                minZ = Math.min(minZ, pos.getZ());
                maxX = Math.max(maxX, pos.getX());
                maxY = Math.max(maxY, pos.getY());
                maxZ = Math.max(maxZ, pos.getZ());
            }

            if (minX == maxX && minY == maxY && minZ == maxZ)
                return minX + " " + minY + " " + minZ;

            return minX + " " + minY + " " + minZ + " -> " + maxX + " " + maxY + " " + maxZ;
        }
    }
}
