package dev.kleinbox.sivage.packet;

import dev.kleinbox.sivage.Sivage;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundClearDialogPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.NoticeDialog;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.List;
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

    public static void openBlockedUrl(ServerPlayer player, LinkVerifier.BlockedLinkException exception) {
        Component title = Component.translatableWithFallback(
                "sivage.dialog.blocked.title",
                "Failed to Display Image!"
        );
        List<DialogBody> body;

        if (exception.getReason() == LinkVerifier.BlockReason.NOT_WHITELISTED) {
            String whitelist = exception.getWhitelist().stream()
                    .map(rule -> "- " + rule)
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("- (none)");

            body = List.of(
                    new PlainMessage(Component.translatableWithFallback(
                            "sivage.dialog.blocked.not_whitelisted",
                            "The URL %s is not whitelisted.",
                            exception.getUrl()
                    ), 400),
                    new PlainMessage(Component.translatableWithFallback(
                            "sivage.dialog.blocked.whitelist",
                            "Whitelisted URLs:\n%s",
                            whitelist
                    ), 400)
            );
        } else {
            body = List.of(new PlainMessage(Component.translatableWithFallback(
                    "sivage.dialog.blocked.blacklisted",
                    "The URL %s is blacklisted.",
                    exception.getUrl()
            ), 400));
        }

        CommonDialogData common = new CommonDialogData(
                title,
                Optional.empty(),
                true,
                false,
                DialogAction.CLOSE,
                body,
                List.of()
        );
        Holder<Dialog> dialog = Holder.direct(new NoticeDialog(common, NoticeDialog.DEFAULT_ACTION));
        player.openDialog(dialog);
    }
}
