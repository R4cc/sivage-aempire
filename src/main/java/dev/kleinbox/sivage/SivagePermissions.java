package dev.kleinbox.sivage;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

public class SivagePermissions {
    public static final String CREATE = "sivage.create";
    public static final String EDIT = "sivage.edit";
    public static final String REMOVE = "sivage.remove";
    public static final String CHECK = "sivage.check";
    public static final String COMMAND_ITEM = "sivage.command.item";
    public static final String COMMAND_IMAGES_LIST = "sivage.command.images.list";
    public static final String COMMAND_IMAGES_DELETE = "sivage.command.images.delete";
    public static final String LIMIT_BYPASS = "sivage.limit.bypass";
    public static final String MAX_IMAGES_META_KEY = "sivage.max-images";
    public static final String ADMIN = "sivage.admin";

    public static final Component CREATE_DENIED = Component.translatableWithFallback(
            "sivage.chat.permission.create",
            "You do not have permission to create Sivage images."
    );
    public static final Component EDIT_DENIED = Component.translatableWithFallback(
            "sivage.chat.permission.edit",
            "You do not have permission to edit Sivage images."
    );
    public static final Component REMOVE_DENIED = Component.translatableWithFallback(
            "sivage.chat.permission.remove",
            "You can only remove Sivage images you created."
    );
    public static final Component CHECK_DENIED = Component.translatableWithFallback(
            "sivage.chat.permission.check",
            "You do not have permission to inspect Sivage images."
    );

    private static final PermissionLevel OP_FALLBACK_LEVEL = PermissionLevel.GAMEMASTERS;

    public static boolean canCreate(ServerPlayer player) {
        return has(player, CREATE);
    }

    public static boolean canEdit(ServerPlayer player) {
        return has(player, EDIT);
    }

    public static boolean canRemoveAnyImage(ServerPlayer player) {
        return has(player, REMOVE);
    }

    public static boolean canCheck(CommandSourceStack source) {
        Entity entity = source.getEntity();
        if (!(entity instanceof ServerPlayer)) {
            return true;
        }

        return has(source, CHECK);
    }

    public static boolean canUseItemCommand(CommandSourceStack source) {
        return has(source, COMMAND_ITEM);
    }

    public static boolean canListImages(CommandSourceStack source) {
        return has(source, COMMAND_IMAGES_LIST);
    }

    public static boolean canDeleteImages(CommandSourceStack source) {
        return has(source, COMMAND_IMAGES_DELETE);
    }

    public static boolean canBypassImageLimit(ServerPlayer player) {
        return has(player, LIMIT_BYPASS);
    }

    /**
     * Gets the maximum number of placed images allowed for a player.
     * LuckPerms metadata overrides the configured fallback when LuckPerms is installed.
     * A value of {@code 0} means unlimited.
     */
    public static int getImageLimit(ServerPlayer player, int fallbackLimit) {
        if (canBypassImageLimit(player))
            return 0;

        if (!FabricLoader.getInstance().isModLoaded("luckperms"))
            return fallbackLimit;

        try {
            return LuckPermsImageLimits.get(player.getUUID(), MAX_IMAGES_META_KEY).orElse(fallbackLimit);
        } catch (LinkageError ignored) {
            return fallbackLimit;
        }
    }

    private static boolean has(ServerPlayer player, String permission) {
        return Permissions.check(player, ADMIN, OP_FALLBACK_LEVEL)
                || Permissions.check(player, permission, OP_FALLBACK_LEVEL);
    }

    private static boolean has(CommandSourceStack source, String permission) {
        return Permissions.check(source, ADMIN, OP_FALLBACK_LEVEL)
                || Permissions.check(source, permission, OP_FALLBACK_LEVEL);
    }
}
