package me.zed_0xff.zombie_buddy;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SteamWorkshopBanInfoTest {

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
