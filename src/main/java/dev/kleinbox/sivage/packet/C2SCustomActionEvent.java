package dev.kleinbox.sivage.packet;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

/**
 * Event related to whenever the server receives a ClickEvent.
 */
public class C2SCustomActionEvent {
    /**
     * Called when the server received the payload and is about to handle it.
     */
    public static final Event<@NotNull Payload> ON_RECEIVE = EventFactory.createArrayBacked(Payload.class, listeners -> (id, payload, player) -> {
        for (Payload listener : listeners)
            if (listener.handlePayload(id, payload, player))
                return true;

        return false;
    });

    public interface Payload {
        /**
         * @return true for payload being handled and no further processing needed by others, false to pass it.
         */
        boolean handlePayload(Identifier id, Tag payload, ServerPlayer player);
    }
}
