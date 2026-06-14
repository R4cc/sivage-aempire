package dev.kleinbox.sivage.packet;

import dev.kleinbox.sivage.Sivage;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.common.ClientboundClearDialogPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * <p>Reference to <code>sivage:new</code> dialog.</p>
 */
public enum ImageDialogs {
    NEW(Sivage.of("new")),
    PREPARE(Sivage.of("prepare")),
    FAILED_FILE(Sivage.of("failed_file")),
    FAILED_LINK(Sivage.of("failed_link")),
    BLOCKED(Sivage.of("blocked")),
    EXCEPTION(Sivage.of("exception")),
    TOO_LARGE(Sivage.of("too_large"));

    public final Identifier id;

    ImageDialogs(Identifier dialog) {
        this.id = dialog;
    }

    /**
     * <p>Opens this dialog for the client, if it exists <i>(which should be always the case)</i>.</p>
     */
    public void open(ServerLevel level, ServerPlayer player) {
        RegistryAccess registryAccess = level.registryAccess();
        Registry<@NotNull Dialog> dialogRegistry = registryAccess.lookupOrThrow(Registries.DIALOG);
        Optional<Holder.Reference<@NotNull Dialog>> optionalDialog = dialogRegistry.get(id);

        optionalDialog.ifPresent(player::openDialog);
    }

    public static void close(ServerPlayer player) {
        player.connection.send(ClientboundClearDialogPacket.INSTANCE);

    }
}
