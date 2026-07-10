package dev.kleinbox.sivage.packet;

import dev.kleinbox.sivage.Sivage;
import dev.kleinbox.sivage.image.ImagePreparationException;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.Locale;

/**
 * <p>Ensures given strings are safe to fetch from.</p>
 */
public class LinkVerifier {

    public static URL fromString(String address) throws ImagePreparationException {
        URI uri;
        try {
            uri = new URI(address);
        } catch (Exception e) {
           throw new ImagePreparationException(ImageDialogs.FAILED_LINK);
        }

        return fromURI(uri);
    }

    public static URL fromURI(URI uri) throws ImagePreparationException {
        if (uri.getHost() == null || !isSafe(uri))
            throw new ImagePreparationException(ImageDialogs.FAILED_LINK);

        BlockReason blockReason = getBlockReason(uri);
        if (blockReason != null)
            throw new BlockedLinkException(uri.toString(), blockReason, configuredWhitelist());

        try {
            return uri.toURL();
        } catch (MalformedURLException e) {
            throw new ImagePreparationException(ImageDialogs.FAILED_LINK);
        }
    }

    /**
     * <p>Check if url is well formatted. IPs and non-http protocols are not allowed.</p>
     */
    private static boolean isSafe(URI uri) {
        // Validate Scheme

        if (uri.getScheme() == null)
            return false;

        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https")))
            return false;

        // All IP addresses will be filtered for the sake of the whitelist and blacklist.

        return uri.getHost().chars().anyMatch(Character::isLetter);
    }

    /**
     * <p>Ensures we are allowed to use this url.</p>
     */
    private static BlockReason getBlockReason(URI uri) {
        String host = uri.getHost();

        for (String rule : Sivage.CONFIG.network.whitelist)
            if (matches(host, rule))
                return null;

        // Explicit blacklist entries take precedence over a catch-all rule so
        // the player receives the more specific error.
        for (String rule : Sivage.CONFIG.network.blacklist)
            if (!rule.equals("*") && matches(host, rule))
                return BlockReason.BLACKLISTED;

        for (String rule : Sivage.CONFIG.network.blacklist)
            if (rule.equals("*"))
                return configuredWhitelist().isEmpty() ? BlockReason.BLACKLISTED : BlockReason.NOT_WHITELISTED;

        return null;
    }

    private static List<String> configuredWhitelist() {
        return Sivage.CONFIG.network.whitelist.stream()
                .filter(rule -> !rule.isBlank())
                .toList();
    }

    /**
     * <p>Compares the host with the given rule with additional wildcard support.</p>
     */
    private static boolean matches(String host, String rule) {
        if (rule.contains("*")) {
            if (rule.equals("*"))
                return true;

            if (rule.startsWith("*.")) {
                String ruleDomain = rule.substring(2);
                if (host.endsWith(ruleDomain)) {
                    // Ensure the wildcard matches only one level
                    String hostWithoutRule = host.substring(0, host.length() - ruleDomain.length() - 1);
                    return !hostWithoutRule.contains(".");
                }
                return false;
            }
        }

        return host.equals(rule);
    }

    public enum BlockReason {
        NOT_WHITELISTED,
        BLACKLISTED
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
