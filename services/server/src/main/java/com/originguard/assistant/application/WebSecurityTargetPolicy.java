package com.originguard.assistant.application;

import com.originguard.shared.application.BusinessConflictException;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Security boundary for any user-controlled network target. */
@Component
public class WebSecurityTargetPolicy {
    private static final Pattern URL = Pattern.compile("(?i)https?://[^\\s<>\\\"'，。！？、；：）】》}]+");

    public URI requireTarget(String input) {
        Matcher matcher = URL.matcher(input == null ? "" : input);
        if (!matcher.find()) {
            throw new BusinessConflictException(
                    "WEB_SECURITY_URL_REQUIRED", "Web security investigation requires an explicit HTTP or HTTPS URL");
        }
        String candidate = trimTrailingPunctuation(matcher.group());
        try {
            URI parsed = new URI(candidate).normalize();
            String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme))) return reject("Only HTTP and HTTPS URLs are allowed");
            if (parsed.getRawUserInfo() != null) return reject("URLs containing embedded credentials are not allowed");
            if (parsed.getHost() == null || parsed.getHost().isBlank()) return reject("The URL hostname is invalid");
            int port = effectivePort(parsed);
            if (!(port == 80 || port == 443)) return reject("Only standard web ports 80 and 443 are allowed");
            String host = asciiHost(parsed);
            if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")
                    || host.endsWith(".internal") || host.endsWith(".lan") || host.contains("%")) {
                return reject("Local and internal hostnames are blocked");
            }
            return new URI(scheme, null, host, parsed.getPort(),
                    emptyToNull(parsed.getRawPath()), emptyToNull(parsed.getRawQuery()), null);
        } catch (URISyntaxException | IllegalArgumentException exception) {
            throw new BusinessConflictException("WEB_SECURITY_URL_INVALID", "The supplied URL is invalid");
        }
    }

    public void requirePublicAddresses(URI target, List<InetAddress> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            throw new BusinessConflictException("WEB_SECURITY_DNS_UNRESOLVED", "The target hostname did not resolve");
        }
        if (addresses.stream().anyMatch(this::isBlockedAddress)) {
            throw new BusinessConflictException(
                    "WEB_SECURITY_PRIVATE_TARGET_BLOCKED",
                    "The target resolves to a private, local, reserved, or non-routable address");
        }
    }

    public String asciiHost(URI target) {
        String host = target.getHost();
        return host.contains(":") ? host.toLowerCase(Locale.ROOT)
                : IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
    }

    public int effectivePort(URI target) {
        if (target.getPort() >= 0) return target.getPort();
        return "https".equalsIgnoreCase(target.getScheme()) ? 443 : 80;
    }

    boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return true;
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) return (bytes[0] & 0xfe) == 0xfc;
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        int third = bytes[2] & 0xff;
        if (first == 0 || first == 10 || first == 127 || first >= 224) return true;
        if (first == 100 && second >= 64 && second <= 127) return true;
        if (first == 169 && second == 254) return true;
        if (first == 172 && second >= 16 && second <= 31) return true;
        if (first == 192 && (second == 168 || (second == 0 && (third == 0 || third == 2)))) return true;
        if (first == 198 && (second == 18 || second == 19 || (second == 51 && third == 100))) return true;
        return first == 203 && second == 0 && third == 113;
    }

    private URI reject(String message) {
        throw new BusinessConflictException("WEB_SECURITY_TARGET_BLOCKED", message);
    }

    private String trimTrailingPunctuation(String value) {
        return value.replaceFirst("[)\\]}>，。！？、,.;:!?]+$", "");
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
