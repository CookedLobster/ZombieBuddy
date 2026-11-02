package me.zed_0xff.zombie_buddy;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class HttpAuthTest {

    private static final String TOKEN_A = "0123456789abcdef0123456789abcdef";
    private static final String TOKEN_B = "fedcba9876543210fedcba9876543210";

    @Test
    void isValidToken_acceptsLongTokens() {
        assertTrue(HttpAuth.isValidToken(TOKEN_A));
        assertTrue(HttpAuth.isValidToken(HttpAuth.generateToken()));
    }

    @Test
    void isValidToken_rejectsShortBlankAndWhitespaceTokens() {
        assertFalse(HttpAuth.isValidToken(null));
        assertFalse(HttpAuth.isValidToken(""));
        assertFalse(HttpAuth.isValidToken("short"));
        assertFalse(HttpAuth.isValidToken("0123456789abcdef0123456789abcde "));
        assertFalse(HttpAuth.isValidToken("0123456789abcdef0123456789abcde\t"));
    }

    @Test
    void generateToken_is64LowercaseHexAndUnique() {
        String first = HttpAuth.generateToken();
        String second = HttpAuth.generateToken();
        assertEquals(64, first.length());
        assertTrue(first.matches("[0-9a-f]{64}"));
        assertNotEquals(first, second);
    }

    @Test
    void extractToken_prefersDedicatedHeader() {
        assertEquals(TOKEN_A, HttpAuth.extractToken(TOKEN_A, "Bearer " + TOKEN_B));
    }

    @Test
    void extractToken_acceptsBearerCaseInsensitively() {
        assertEquals(TOKEN_A, HttpAuth.extractToken(null, "Bearer " + TOKEN_A));
        assertEquals(TOKEN_A, HttpAuth.extractToken(null, "bearer " + TOKEN_A));
        assertEquals(TOKEN_A, HttpAuth.extractToken(null, "  BEARER   " + TOKEN_A + "  "));
    }

    @Test
    void extractToken_rejectsMissingMalformedAndQueryStyleValues() {
        assertNull(HttpAuth.extractToken(null, null));
        assertNull(HttpAuth.extractToken(null, ""));
        assertNull(HttpAuth.extractToken(null, "Basic " + TOKEN_A));
        assertNull(HttpAuth.extractToken(null, "Bearer"));
        assertNull(HttpAuth.extractToken(null, "Bearer short"));
        // An invalid dedicated header does not prevent a valid Authorization header
        // from being used.
        assertEquals(TOKEN_A, HttpAuth.extractToken("short", "Bearer " + TOKEN_A));
    }

    @Test
    void isAuthorized_comparesOnlyValidEqualTokens() {
        assertTrue(HttpAuth.isAuthorized(TOKEN_A, TOKEN_A));
        assertFalse(HttpAuth.isAuthorized(TOKEN_A, TOKEN_B));
        assertFalse(HttpAuth.isAuthorized(TOKEN_A, null));
        assertFalse(HttpAuth.isAuthorized(null, TOKEN_A));
        assertFalse(HttpAuth.isAuthorized("short", "short"));
    }

    @Test
    void isLoopbackHost_recognisesLoopbackForms() {
        assertTrue(HttpAuth.isLoopbackHost("127.0.0.1"));
        assertTrue(HttpAuth.isLoopbackHost("127.1.2.3"));
        assertTrue(HttpAuth.isLoopbackHost("localhost"));
        assertTrue(HttpAuth.isLoopbackHost("LOCALHOST"));
        assertTrue(HttpAuth.isLoopbackHost("::1"));
        assertTrue(HttpAuth.isLoopbackHost("[::1]"));
        assertTrue(HttpAuth.isLoopbackHost("0:0:0:0:0:0:0:1"));
    }

    @Test
    void isLoopbackHost_rejectsWildcardAndRemoteForms() {
        assertFalse(HttpAuth.isLoopbackHost(null));
        assertFalse(HttpAuth.isLoopbackHost(""));
        assertFalse(HttpAuth.isLoopbackHost("0.0.0.0"));
        assertFalse(HttpAuth.isLoopbackHost("*"));
        assertFalse(HttpAuth.isLoopbackHost("::"));
        assertFalse(HttpAuth.isLoopbackHost("192.0.2.10"));
        assertFalse(HttpAuth.isLoopbackHost("example.com"));
    }
}
