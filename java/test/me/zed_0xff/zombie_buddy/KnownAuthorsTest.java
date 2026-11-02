package me.zed_0xff.zombie_buddy;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.Test;

import me.zed_0xff.zombie_buddy.SteamWorkshop.SteamID64;

class KnownAuthorsTest {
    private static final String KEY_A = "a".repeat(64);
    private static final String KEY_B = "b".repeat(64);

    @Test
    void parseAuthorsJSON_loadsValidList() {
        String json = authorsJson(
            author(1, "Alice", KEY_A),
            author(2, "Bob", KEY_B)
        );

        Map<SteamID64, KnownAuthors.AuthorEntry> authors = KnownAuthors.parseAuthorsJSON(json);

        assertEquals(2, authors.size());
        assertEquals("Alice", authors.get(new SteamID64(1L)).name);
    }

    @Test
    void parseAuthorsJSON_rejectsDuplicateAuthorIds() {
        String json = authorsJson(
            author(1, "Alice", KEY_A),
            author(1, "Mallory", KEY_B)
        );

        assertTrue(KnownAuthors.parseAuthorsJSON(json).isEmpty());
    }

    @Test
    void parseAuthorsJSON_rejectsSameSigningKeyForDifferentAuthors() {
        String json = authorsJson(
            author(1, "Alice", KEY_A),
            author(2, "Mallory", KEY_A)
        );

        assertTrue(KnownAuthors.parseAuthorsJSON(json).isEmpty());
    }

    @Test
    void parseAuthorsJSON_dropsMalformedKeys() {
        String json = authorsJson(
            author(1, "Alice", "not-a-key")
        );

        Map<SteamID64, KnownAuthors.AuthorEntry> authors = KnownAuthors.parseAuthorsJSON(json);

        assertEquals(1, authors.size());
        assertTrue(authors.get(new SteamID64(1L)).keys.isEmpty());
    }

    private static String author(long id, String name, String key) {
        return "{\"id\":" + id + ",\"name\":\"" + name + "\",\"keys\":[\"" + key + "\"]}";
    }

    private static String authorsJson(String... authors) {
        return "{\"updated_at\":\"2026-11-02T00:00:00Z\",\"authors\":["
            + String.join(",", authors)
            + "],\"signature\":\"\"}";
    }
}
