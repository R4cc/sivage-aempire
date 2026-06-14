package dev.kleinbox.sivage.packet;

import dev.kleinbox.sivage.Sivage;
import dev.kleinbox.sivage.image.ImagePreparationException;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
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
        if (!isSafe(uri) || uri.getHost() == null)
            throw new ImagePreparationException(ImageDialogs.FAILED_LINK);

        if (!hasRulesEnsured(uri))
            throw new ImagePreparationException(ImageDialogs.BLOCKED);

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
    private static boolean hasRulesEnsured(URI uri) {
        String host = uri.getHost();

        for (String rule : Sivage.CONFIG.network.whitelist)
            if (matches(host, rule))
                return true;

        for (String rule : Sivage.CONFIG.network.blacklist)
            if (matches(host, rule))
                return false;

        return true;
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

}
