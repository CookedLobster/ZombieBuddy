package me.zed_0xff.zombie_buddy;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Authentication helpers for the experimental HTTP Lua API.
 *
 * <p>The API is reachable by any local process (and potentially the network when a
 * non-loopback bind address is used). It therefore requires a bearer token, rejects
 * browser-originated requests, and only accepts the token from request headers.
 */
public final class HttpAuth {
    /** Minimum accepted token length. Generated tokens are 64 hex characters. */
    public static final int MIN_TOKEN_LENGTH = 32;

    /** Number of random bytes in a generated token (32 bytes -> 64 hex characters). */
    public static final int GENERATED_TOKEN_BYTES = 32;

    public static final String TOKEN_HEADER = "X-ZombieBuddy-Token";
    public static final String AUTHORIZATION_HEADER = "Authorization";
    public static final String TOKEN_FILE_NAME = "zbLuaAPI.token";

    private static final int MAX_TOKEN_LENGTH = 512;
    private static final SecureRandom RANDOM = new SecureRandom();

    private HttpAuth() {}

    /**
     * Returns true when {@code token} is non-null, non-blank, has no whitespace, and
     * is between {@link #MIN_TOKEN_LENGTH} and 512 characters long.
     */
    public static boolean isValidToken(String token) {
        if (token == null) {
            return false;
        }
        int length = token.length();
        if (length < MIN_TOKEN_LENGTH || length > MAX_TOKEN_LENGTH) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if (Character.isWhitespace(token.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Generates a cryptographically random 32-byte token encoded as 64 lowercase hex characters. */
    public static String generateToken() {
        byte[] bytes = new byte[GENERATED_TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * Extracts a token from {@code X-ZombieBuddy-Token} or
     * {@code Authorization: Bearer <token>}. Query-string tokens are never accepted.
     *
     * @return the token, or {@code null} when no valid token was supplied
     */
    public static String extractToken(String headerToken, String authorization) {
        if (isValidToken(headerToken)) {
            return headerToken;
        }
        if (authorization == null) {
            return null;
        }
        String value = authorization.trim();
        if (value.length() < 7 || !value.regionMatches(true, 0, "Bearer", 0, 6)
                || !Character.isWhitespace(value.charAt(6))) {
            return null;
        }
        String token = value.substring(7).trim();
        return isValidToken(token) ? token : null;
    }

    /** Constant-time comparison of two valid tokens. */
    public static boolean isAuthorized(String expected, String supplied) {
        if (!isValidToken(expected) || !isValidToken(supplied)) {
            return false;
        }
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            supplied.getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * Returns true for loopback bind addresses (127.0.0.0/8, ::1, localhost and
     * hostnames that resolve to a loopback address).
     */
    public static boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String value = host.trim();
        if (value.isEmpty()) {
            return false;
        }
        if (value.startsWith("[") && value.endsWith("]") && value.length() > 2) {
            value = value.substring(1, value.length() - 1);
        }
        value = value.toLowerCase(Locale.ROOT);
        if ("localhost".equals(value) || "localhost.".equals(value)
                || "::1".equals(value) || "0:0:0:0:0:0:0:1".equals(value)) {
            return true;
        }
        try {
            return InetAddress.getByName(value).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }
}
