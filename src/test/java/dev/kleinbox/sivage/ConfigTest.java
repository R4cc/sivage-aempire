package dev.kleinbox.sivage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigTest {
    @Test
    void fileSizeLimitCanBeLoweredButNeverDisabledOrRaisedAboveCeiling() {
        Config.Network network = new Config.Network();

        network.fileSizeLimit = 2_000_000;
        assertEquals(2_000_000, network.effectiveFileSizeLimit());

        network.fileSizeLimit = 0;
        assertEquals(Config.Network.MAX_FILE_SIZE_LIMIT, network.effectiveFileSizeLimit());

        network.fileSizeLimit = Integer.MAX_VALUE;
        assertEquals(Config.Network.MAX_FILE_SIZE_LIMIT, network.effectiveFileSizeLimit());
    }
}
