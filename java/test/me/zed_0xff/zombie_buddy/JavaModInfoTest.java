package me.zed_0xff.zombie_buddy;

import java.io.File;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

import static me.zed_0xff.zombie_buddy.SteamWorkshop.WorkshopItemID;
import java.nio.file.Files;
import java.nio.file.Path;

class JavaModInfoTest {
    private static final String HOME_DIR  = System.getProperty("user.home");

    @TempDir
    Path tempDir;

    private Path cacheDir;
    private MockedStatic<Utils> utilsMock;
    @BeforeEach
    void setUp() throws IOException {
        cacheDir = tempDir.resolve("Zomboid");
        Path workshopMod = cacheDir.resolve("Workshop/ZBExhume41");
        Files.createDirectories(workshopMod);
        Files.writeString(workshopMod.resolve("workshop.txt"), "id=3718604798\n");

        utilsMock = mockStatic(Utils.class, CALLS_REAL_METHODS);
        utilsMock.when(Utils::getCacheDir).thenReturn(cacheDir.toString());
        utilsMock.when(Utils::getCachePath).thenReturn(cacheDir);
    }

    @AfterEach
    void tearDown() {
        utilsMock.close();
    }

    @Test
    void workshopItemIdFromInfPath_valid() {
        assertEquals(
                new WorkshopItemID(3718604798L),
                JavaModInfo.workshopItemIdFromInfPath( cacheDir.resolve("Workshop/ZBExhume41/Contents/mods/ZBExhume41/common/mod.info"))
                );
        assertEquals(
                new WorkshopItemID(2986022978L),
                JavaModInfo.workshopItemIdFromInfPath( Path.of(HOME_DIR, "/Library/Application Support/Steam/steamapps/workshop/content/108600/2986022978/mods/DoubleDeckerBusInterior"))
                );
    }

    @Test
    void workshopItemIdFromInfPath_usesLocalWorkshopTxt() throws IOException {
        Path localMod = tempDir.resolve("local/ZBMod");
        Files.createDirectories(localMod);
        Files.writeString(localMod.resolve("workshop.txt"), "id=2986022978\n");

        assertEquals(
            new WorkshopItemID(2986022978L),
            JavaModInfo.workshopItemIdFromInfPath(localMod.resolve("mod.info"))
        );
    }

    @Test
    void workshopItemIdFromInfPath_invalid() {
        assertNull( JavaModInfo.workshopItemIdFromInfPath( cacheDir.resolve("Workshop/ZBExhume41/Contents/mods/ZBExhume41/common/foo/mod.info")));
        assertNull( JavaModInfo.workshopItemIdFromInfPath( cacheDir.resolve("Workshop/ZBExhume41/Contents/mods/ZBExhume41/mod.info")));
        assertNull( JavaModInfo.workshopItemIdFromInfPath( cacheDir.resolve("Workshop/ZBExhume41/Contents/mods/ZBExhume41/42.13/media/java/ZBExhume41.jar")));
        assertNull( JavaModInfo.workshopItemIdFromInfPath( Path.of("/etc/passwd")));
    }

    @Test
    void workshopItemIdFromInfPath_oneDirBelowRoot() {
        // 4 levels up from mod.info lands on "/x", whose parent is the root (no file name)
        assertNull( JavaModInfo.workshopItemIdFromInfPath( Path.of("/x/cache/mods/ZBPopFix/42.20/mod.info")));
    }
}
