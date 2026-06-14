package dev.kleinbox.sivage;

import me.lucko.fabric.api.permissions.v0.Permissions;
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
            "You do not have permission to remove Sivage images."
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

    public static boolean canRemove(ServerPlayer player) {
        return has(player, REMOVE);
    }

    public static boolean canCheck(CommandSourceStack source) {
        Entity entity = source.getEntity();
        if (!(entity instanceof ServerPlayer)) {
            return true;
        }

        return has(source, CHECK);
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
