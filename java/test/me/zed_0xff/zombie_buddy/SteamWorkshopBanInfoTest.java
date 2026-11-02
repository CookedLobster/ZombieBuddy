package me.zed_0xff.zombie_buddy;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class SteamWorkshopBanInfoTest {

    private static JsonObject json(String value) {
        return JsonParser.parseString(value).getAsJsonObject();
    }

    @Test
    void parseItemDetails_readsNotBannedAndCreator() {
        SteamWorkshop.ItemDetails details = SteamWorkshop.parseItemDetails(json(
            "{\"result\":1,\"banned\":0,\"creator\":\"76561198000000000\"}"));

        assertNotNull(details);
        assertEquals(SteamWorkshop.BanStatus.NOT_BANNED, details.ban().status());
        assertEquals(76561198000000000L, details.creatorSteamId64().value());
    }

    @Test
    void parseItemDetails_readsBannedReason() {
        SteamWorkshop.ItemDetails details = SteamWorkshop.parseItemDetails(json(
            "{\"result\":1,\"banned\":1,\"ban_reason\":\"policy\",\"creator\":\"76561198000000000\"}"));

        assertNotNull(details);
        assertEquals(SteamWorkshop.BanStatus.BANNED, details.ban().status());
        assertEquals("policy", details.ban().reason());
    }

    @Test
    void parseItemDetails_missingResultIsUnknown() {
        SteamWorkshop.ItemDetails details = SteamWorkshop.parseItemDetails(json(
            "{\"banned\":0,\"creator\":\"76561198000000000\"}"));

        assertNotNull(details);
        assertTrue(details.ban().isUnknown());
    }

    @Test
    void parseItemDetails_nonSuccessResultIsUnknown() {
        SteamWorkshop.ItemDetails details = SteamWorkshop.parseItemDetails(json(
            "{\"result\":9,\"banned\":0,\"creator\":\"76561198000000000\"}"));

        assertNotNull(details);
        assertTrue(details.ban().isUnknown());
    }

    @Test
    void parseItemDetails_missingOrInvalidBanIsUnknown() {
        assertTrue(SteamWorkshop.parseItemDetails(json(
            "{\"result\":1,\"creator\":\"76561198000000000\"}")).ban().isUnknown());
        assertTrue(SteamWorkshop.parseItemDetails(json(
            "{\"result\":1,\"banned\":\"no\",\"creator\":\"76561198000000000\"}")).ban().isUnknown());
    }

    @Test
    void legacyBooleanNullBecomesUnknown() {
        SteamWorkshop.BanInfo info = new SteamWorkshop.BanInfo((Boolean) null, "API unavailable");

        assertEquals(SteamWorkshop.BanStatus.UNKNOWN, info.status());
        assertTrue(info.isUnknown());
        assertFalse(info.isBanned());
    }

    @Test
    void legacyBooleanTrueBecomesBanned() {
        SteamWorkshop.BanInfo info = new SteamWorkshop.BanInfo(Boolean.TRUE, "banned");

        assertEquals(SteamWorkshop.BanStatus.BANNED, info.status());
        assertTrue(info.isBanned());
        assertFalse(info.isUnknown());
    }

    @Test
    void factoriesProduceExpectedStates() {
        assertTrue(SteamWorkshop.BanInfo.banned("reason").isBanned());
        assertFalse(SteamWorkshop.BanInfo.notBanned().isBanned());
        assertTrue(SteamWorkshop.BanInfo.unknown("reason").isUnknown());
    }
}
