package dev.kleinbox.sivage.packet;

import dev.kleinbox.sivage.Sivage;
import dev.kleinbox.sivage.image.ImagePreparationException;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Validates player-supplied image URLs before any network access occurs. */
public final class LinkVerifier {
    public static final int MAX_URL_LENGTH = 2_048;

    private LinkVerifier() {
    }

    public static URL fromString(String address) throws ImagePreparationException {
        if (address == null || address.length() > MAX_URL_LENGTH)
            throw new ImagePreparationException(ImageDialogs.FAILED_LINK);

        try {
            return fromURI(new URI(address));
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new ImagePreparationException(ImageDialogs.FAILED_LINK);
        }
    }

    public static URL fromURI(URI uri) throws ImagePreparationException {
        try {
            URI normalized = normalize(uri);
            BlockReason blockReason = getBlockReason(
                    normalized.getHost(), Sivage.CONFIG.network.whitelist, Sivage.CONFIG.network.blacklist
            );
            if (blockReason != null)
                throw new BlockedLinkException(normalized.toString(), blockReason, configuredWhitelist());

            return normalized.toURL();
        } catch (BlockedLinkException e) {
            throw e;
        } catch (Exception e) {
            throw new ImagePreparationException(ImageDialogs.FAILED_LINK);
        }
    }

    static URI normalize(URI uri) throws URISyntaxException {
        if (uri == null || uri.toString().length() > MAX_URL_LENGTH || uri.getRawAuthority() == null
                || uri.getRawUserInfo() != null || uri.getRawAuthority().indexOf('@') >= 0)
            throw new URISyntaxException(String.valueOf(uri), "Missing or unsupported URL authority");

        String scheme = uri.getScheme();
        if (scheme == null)
            throw new URISyntaxException(uri.toString(), "Missing scheme");
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https"))
            throw new URISyntaxException(uri.toString(), "Unsupported scheme");

        HostAndPort authority = parseAuthority(uri);
        int port = authority.port();
        if (port < -1 || port > 65_535)
            throw new URISyntaxException(uri.toString(), "Invalid port");

        String rawHost = authority.host();
        if (rawHost.indexOf(':') >= 0 || rawHost.indexOf('[') >= 0 || rawHost.indexOf(']') >= 0)
            throw new URISyntaxException(uri.toString(), "Literal addresses are not allowed");
        String host = normalizeHost(rawHost);
        if (isLiteralAddress(host))
            throw new URISyntaxException(uri.toString(), "Literal addresses are not allowed");

        return new URI(scheme, null, host, port, uri.getRawPath(), uri.getRawQuery(), null);
    }

    private static HostAndPort parseAuthority(URI uri) throws URISyntaxException {
        String authority = uri.getRawAuthority();
        if (authority.startsWith("[") || authority.indexOf('[') >= 0 || authority.indexOf(']') >= 0)
            throw new URISyntaxException(uri.toString(), "Literal addresses are not allowed");

        String host = uri.getHost();
        if (host != null)
            return new HostAndPort(host, uri.getPort());

        int separator = authority.lastIndexOf(':');
        if (separator < 0)
            return new HostAndPort(authority, -1);
        if (authority.indexOf(':') != separator)
            throw new URISyntaxException(uri.toString(), "Literal addresses are not allowed");

        String rawPort = authority.substring(separator + 1);
        if (rawPort.isEmpty() || !rawPort.chars().allMatch(Character::isDigit))
            throw new URISyntaxException(uri.toString(), "Invalid port");
        try {
            return new HostAndPort(authority.substring(0, separator), Integer.parseInt(rawPort));
        } catch (NumberFormatException e) {
            throw new URISyntaxException(uri.toString(), "Invalid port");
        }
    }

    static String normalizeHost(String host) {
        String unwrapped = host;
        if (unwrapped.startsWith("[") && unwrapped.endsWith("]"))
            unwrapped = unwrapped.substring(1, unwrapped.length() - 1);
        while (unwrapped.endsWith("."))
            unwrapped = unwrapped.substring(0, unwrapped.length() - 1);
        if (unwrapped.isBlank())
            throw new IllegalArgumentException("Empty host");

        return IDN.toASCII(unwrapped, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
    }

    private static boolean isLiteralAddress(String host) {
        if (host.indexOf(':') >= 0 || host.chars().allMatch(Character::isDigit))
            return true;
        return host.indexOf('.') >= 0 && host.chars().allMatch(ch -> Character.isDigit(ch) || ch == '.');
    }

    static BlockReason getBlockReason(String host, List<String> whitelist, List<String> blacklist) {
        for (String rule : whitelist)
            if (matches(host, rule))
                return null;

        for (String rule : blacklist)
            if (!rule.isBlank() && !rule.equals("*") && matches(host, rule))
                return BlockReason.BLACKLISTED;

        for (String rule : blacklist)
            if (rule.equals("*"))
                return whitelist.stream().noneMatch(ruleEntry -> !ruleEntry.isBlank())
                        ? BlockReason.BLACKLISTED
                        : BlockReason.NOT_WHITELISTED;

        return null;
    }

    private static List<String> configuredWhitelist() {
        return Sivage.CONFIG.network.whitelist.stream()
                .filter(rule -> !rule.isBlank())
                .toList();
    }

    static boolean matches(String host, String rawRule) {
        if (rawRule == null || rawRule.isBlank())
            return false;
        String rule = rawRule.trim();
        if (rule.equals("*"))
            return true;

        boolean wildcard = rule.startsWith("*.");
        String domain;
        try {
            domain = normalizeHost(wildcard ? rule.substring(2) : rule);
        } catch (IllegalArgumentException ignored) {
            return false;
        }

        if (!wildcard)
            return host.equals(domain);
        if (!host.endsWith("." + domain))
            return false;

        String prefix = host.substring(0, host.length() - domain.length() - 1);
        return !prefix.isEmpty() && !prefix.contains(".");
    }

    /** Resolves a host once and returns only addresses safe for the HTTP client to connect to. */
    public static List<InetAddress> resolvePublicHost(String hostname) throws UnknownHostException {
        String normalized;
        try {
            normalized = normalizeHost(hostname);
        } catch (IllegalArgumentException e) {
            UnknownHostException exception = new UnknownHostException("Invalid image host");
            exception.initCause(e);
            throw exception;
        }

        InetAddress[] addresses = InetAddress.getAllByName(normalized);
        if (addresses.length == 0 || Arrays.stream(addresses).anyMatch(address -> !isPublicAddress(address)))
            throw new UnknownHostException("Image host does not resolve exclusively to public addresses");
        return List.of(addresses);
    }

    static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress())
            return false;

        byte[] raw = address.getAddress();
        if (raw.length == 4)
            return isPublicIpv4(raw);
        if (raw.length != 16)
            return false;

        if (isIpv4Mapped(raw))
            return isPublicIpv4(Arrays.copyOfRange(raw, 12, 16));

        // Public IPv6 unicast is 2000::/3, minus special-use and documentation ranges.
        if ((raw[0] & 0xE0) != 0x20)
            return false;
        return !inCidr(raw, hex("20010000000000000000000000000000"), 23)
                && !inCidr(raw, hex("20010db8000000000000000000000000"), 32)
                && !inCidr(raw, hex("20020000000000000000000000000000"), 16)
                && !inCidr(raw, hex("3fff0000000000000000000000000000"), 20);
    }

    private static boolean isPublicIpv4(byte[] raw) {
        int first = raw[0] & 0xFF;
        int second = raw[1] & 0xFF;
        int third = raw[2] & 0xFF;

        if (first == 0 || first == 10 || first == 127 || first >= 224)
            return false;
        if (first == 100 && second >= 64 && second <= 127)
            return false;
        if (first == 169 && second == 254)
            return false;
        if (first == 172 && second >= 16 && second <= 31)
            return false;
        if (first == 192 && second == 168)
            return false;
        if (first == 192 && second == 0 && (third == 0 || third == 2))
            return false;
        if (first == 192 && second == 88 && third == 99)
            return false;
        if (first == 198 && (second == 18 || second == 19))
            return false;
        if (first == 198 && second == 51 && third == 100)
            return false;
        return !(first == 203 && second == 0 && third == 113);
    }

    private static boolean isIpv4Mapped(byte[] raw) {
        for (int i = 0; i < 10; i++)
            if (raw[i] != 0)
                return false;
        return raw[10] == (byte) 0xFF && raw[11] == (byte) 0xFF;
    }

    private static boolean inCidr(byte[] address, byte[] network, int prefixBits) {
        int fullBytes = prefixBits / 8;
        int remainingBits = prefixBits % 8;
        for (int i = 0; i < fullBytes; i++)
            if (address[i] != network[i])
                return false;
        if (remainingBits == 0)
            return true;
        int mask = 0xFF << (8 - remainingBits);
        return (address[fullBytes] & mask) == (network[fullBytes] & mask);
    }

    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++)
            bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }

    public enum BlockReason {
        NOT_WHITELISTED,
        BLACKLISTED
    }

    private record HostAndPort(String host, int port) {
    }

    public static class BlockedLinkException extends ImagePreparationException {
        private final String url;
        private final BlockReason reason;
        private final List<String> whitelist;

        private BlockedLinkException(String url, BlockReason reason, List<String> whitelist) {
            super(ImageDialogs.BLOCKED);
            this.url = url;
            this.reason = reason;
            this.whitelist = whitelist;
        }

        public String getUrl() {
            return url;
        }

        public BlockReason getReason() {
            return reason;
        }

        public List<String> getWhitelist() {
            return whitelist;
        }
    }
}
