package dev.kleinbox.sivage.mixin;

import dev.kleinbox.sivage.packet.C2SCustomActionEvent;
import net.minecraft.network.Connection;
import net.minecraft.network.TickablePacketListener;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.network.ServerPlayerConnection;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin extends ServerCommonPacketListenerImpl
        implements GameProtocols.Context, ServerGamePacketListener, ServerPlayerConnection, TickablePacketListener {

    @Shadow
    public ServerPlayer player;

    public ServerGamePacketListenerImplMixin(MinecraftServer server, Connection connection, CommonListenerCookie cookie) {
        super(server, connection, cookie);
    }

    @Override
    public void handleCustomClickAction(@NotNull ServerboundCustomClickActionPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.server.packetProcessor());
        boolean handled = false;

        if (packet.payload().isPresent())
            handled = C2SCustomActionEvent.ON_RECEIVE.invoker().handlePayload(packet.id(), packet.payload().get(), player);

        if (!handled) super.handleCustomClickAction(packet);
    }
}
