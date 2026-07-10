package dev.kleinbox.sivage;

import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;

import java.util.OptionalInt;
import java.util.UUID;

/**
 * Optional LuckPerms integration. This class is loaded only after Sivage has
 * confirmed that the LuckPerms mod is present.
 */
final class LuckPermsImageLimits {
    private LuckPermsImageLimits() {
    }

    static OptionalInt get(UUID playerId, String metadataKey) {
        try {
            User user = LuckPermsProvider.get().getUserManager().getUser(playerId);
            if (user == null)
                return OptionalInt.empty();

            String value = user.getCachedData().getMetaData().getMetaValue(metadataKey);
            if (value == null)
                return OptionalInt.empty();

            int limit = Integer.parseInt(value);
            return limit >= 0 ? OptionalInt.of(limit) : OptionalInt.empty();
        } catch (IllegalStateException | NumberFormatException ignored) {
            return OptionalInt.empty();
        }
    }
}
