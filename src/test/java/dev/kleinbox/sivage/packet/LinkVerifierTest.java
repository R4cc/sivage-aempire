package dev.kleinbox.sivage.packet;

import org.junit.jupiter.api.Test;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LinkVerifierTest {
    @Test
    void normalizesCaseAndTrailingDotBeforeBlacklistChecks() throws Exception {
        for (String raw : List.of("http://LOCALHOST/image.png", "http://localhost./image.png")) {
            URI normalized = LinkVerifier.normalize(new URI(raw));
            assertEquals("localhost", normalized.getHost());
            assertEquals(
                    LinkVerifier.BlockReason.BLACKLISTED,
                    LinkVerifier.getBlockReason(normalized.getHost(), List.of(), List.of("localhost"))
            );
        }
    }

    @Test
    void convertsUnicodeHostsToAscii() throws Exception {
        URI normalized = LinkVerifier.normalize(new URI("https://BÜCHER.example./image.png"));
        assertEquals("xn--bcher-kva.example", normalized.getHost());
    }

    @Test
    void rejectsLiteralAddressesAndCredentials() {
        assertThrows(URISyntaxException.class,
                () -> LinkVerifier.normalize(new URI("http://127.0.0.1/image.png")));
        assertThrows(URISyntaxException.class,
                () -> LinkVerifier.normalize(new URI("http://[::ffff:127.0.0.1]/image.png")));
        assertThrows(URISyntaxException.class,
                () -> LinkVerifier.normalize(new URI("https://user:secret@example.com/image.png")));
        assertThrows(URISyntaxException.class,
                () -> LinkVerifier.normalize(new URI("https://example.com:invalid/image.png")));
        assertThrows(URISyntaxException.class,
                () -> LinkVerifier.normalize(new URI("ftp://example.com/image.png")));
    }

    @Test
    void wildcardMatchesExactlyOneDomainLabel() {
        assertTrue(LinkVerifier.matches("a.example.com", "*.example.com"));
        assertFalse(LinkVerifier.matches("example.com", "*.example.com"));
        assertFalse(LinkVerifier.matches("a.b.example.com", "*.example.com"));
        assertFalse(LinkVerifier.matches("evil-example.com", "*.example.com"));
        assertTrue(LinkVerifier.matches("example.com", "EXAMPLE.COM."));

        assertNull(LinkVerifier.getBlockReason("a.example.com", List.of("*.example.com"), List.of("*")));
        assertEquals(
                LinkVerifier.BlockReason.NOT_WHITELISTED,
                LinkVerifier.getBlockReason("a.b.example.com", List.of("*.example.com"), List.of("*"))
        );
    }

    @Test
    void classifiesSpecialUseIpv4RangesAsUnsafe() throws Exception {
        for (String address : new String[]{
                "0.0.0.1", "10.0.0.1", "100.64.0.1", "127.0.0.1", "169.254.1.1",
                "172.16.0.1", "192.0.2.1", "192.168.1.1", "198.18.0.1",
                "198.51.100.1", "203.0.113.1", "224.0.0.1"
        }) {
            assertFalse(LinkVerifier.isPublicAddress(InetAddress.getByName(address)), address);
        }
        assertTrue(LinkVerifier.isPublicAddress(InetAddress.getByName("8.8.8.8")));
        assertThrows(java.net.UnknownHostException.class, () -> LinkVerifier.resolvePublicHost("localhost"));
    }

    @Test
    void rejectsPrivateMappedIpv6AndAllowsPublicUnicast() throws Exception {
        byte[] mappedPrivate = new byte[16];
        mappedPrivate[10] = (byte) 0xFF;
        mappedPrivate[11] = (byte) 0xFF;
        mappedPrivate[12] = 10;
        mappedPrivate[15] = 1;
        InetAddress mapped = Inet6Address.getByAddress(null, mappedPrivate, -1);

        assertFalse(LinkVerifier.isPublicAddress(mapped));
        assertFalse(LinkVerifier.isPublicAddress(InetAddress.getByName("2001:db8::1")));
        assertTrue(LinkVerifier.isPublicAddress(InetAddress.getByName("2606:4700:4700::1111")));
    }

    @Test
    void enforcesUrlLength() {
        String oversized = "https://example.com/" + "a".repeat(LinkVerifier.MAX_URL_LENGTH);
        assertThrows(URISyntaxException.class, () -> LinkVerifier.normalize(new URI(oversized)));
    }
}
