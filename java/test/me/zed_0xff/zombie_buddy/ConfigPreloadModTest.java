package me.zed_0xff.zombie_buddy;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ConfigPreloadModTest {

    @Test
    void preloadSha256RoundTrips() {
        Map<String, Config.PreloadMod> preloads = new LinkedHashMap<>();
        preloads.put("TestMod", new Config.PreloadMod(
            Path.of("/mods/TestMod/mod.info"),
            Path.of("/mods/TestMod/TestMod.jar"),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        ));
        Config config = new Config(new ArrayList<>(), preloads, true, true, false, false);

        Config parsed = ZBGson.PRETTY.fromJson(ZBGson.PRETTY.toJson(config), Config.class);

        assertEquals(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            parsed.preload_mods().get("TestMod").sha256()
        );
    }

    @Test
    void legacyPreloadWithoutHashIsPreservedForFailClosedMigration() {
        String json = "{\"trusted_authors\":[],\"preload_mods\":{"
            + "\"LegacyMod\":{\"infPath\":\"/mods/LegacyMod/mod.info\",\"jarPath\":\"/mods/LegacyMod/LegacyMod.jar\"}},"
            + "\"auto_fix_mod_order\":true,\"fix_approval_dialog_cursor\":true,"
            + "\"fix_macos_retina\":false,\"stealth_mode\":false}";

        Config parsed = ZBGson.PRETTY.fromJson(json, Config.class);

        assertNotNull(parsed);
        assertNull(parsed.preload_mods().get("LegacyMod").sha256());
    }
}
