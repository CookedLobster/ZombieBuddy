package me.zed_0xff.zombie_buddy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Covers the numbered javaJarFileN / zbVersionMinN / zbVersionMaxN mod.info keys that let a single
 * mod.info offer several JARs built against different ZombieBuddy API versions.
 */
class JavaModInfoJarCandidatesTest {

    @TempDir
    Path tempDir;

    private MockedStatic<Utils> utilsMock;
    private MockedStatic<ZombieBuddy> zbMock;

    @BeforeEach
    void setUp() {
        utilsMock = mockStatic(Utils.class, CALLS_REAL_METHODS);
        utilsMock.when(Utils::isServer).thenReturn(false);

        zbMock = mockStatic(ZombieBuddy.class, CALLS_REAL_METHODS);
    }

    @AfterEach
    void tearDown() {
        utilsMock.close();
        zbMock.close();
    }

    private void setZbVersion(String version) {
        zbMock.when(ZombieBuddy::getVersion).thenReturn(version);
    }

    private void writeModInfo(String content) throws Exception {
        Files.writeString(tempDir.resolve("mod.info"), content);
    }

    private void touchJar(String name) throws Exception {
        Files.createFile(tempDir.resolve(name));
    }

    @Test
    void bareKeysOnly_stillWorkLikeBefore() throws Exception {
        touchJar("foo.jar");
        writeModInfo("""
            javaJarFile=foo.jar
            javaPkgName=some.pkg
            zbVersionMin=1.0.0
            """);
        setZbVersion("2.0.0");

        JavaModInfo info = JavaModInfo.parse(tempDir);
        assertNotNull(info);
        assertEquals(tempDir.resolve("foo.jar"), info.jarPath());
    }

    @Test
    void numberedCandidate_selectedWhenItsRangeMatches() throws Exception {
        touchJar("old.jar");
        touchJar("new.jar");
        writeModInfo("""
            javaJarFile=old.jar
            javaPkgName=some.pkg
            zbVersionMin=1.0.0
            zbVersionMax=1.9.9

            javaJarFile2=new.jar
            zbVersionMin2=2.0.0
            """);
        setZbVersion("3.0.0");

        JavaModInfo info = JavaModInfo.parse(tempDir);
        assertNotNull(info);
        assertEquals(tempDir.resolve("new.jar"), info.jarPath());
    }

    @Test
    void numberedCandidate_lowestIndexWinsWhenMultipleMatch() throws Exception {
        touchJar("a.jar");
        touchJar("b.jar");
        writeModInfo("""
            javaPkgName=some.pkg
            javaJarFile1=a.jar
            zbVersionMin1=1.0.0

            javaJarFile2=b.jar
            zbVersionMin2=1.0.0
            """);
        setZbVersion("5.0.0");

        JavaModInfo info = JavaModInfo.parse(tempDir);
        assertNotNull(info);
        assertEquals(tempDir.resolve("a.jar"), info.jarPath());
    }

    @Test
    void orphanVersionKeyWithoutMatchingJar_isIgnoredNotFatal() throws Exception {
        touchJar("foo.jar");
        writeModInfo("""
            zbVersionMin5=1.0.0
            javaJarFile=foo.jar
            javaPkgName=some.pkg
            """);
        setZbVersion("2.0.0");

        JavaModInfo info = JavaModInfo.parse(tempDir);
        assertNotNull(info);
        assertEquals(tempDir.resolve("foo.jar"), info.jarPath());
    }

    @Test
    void noCandidateInRange_parseReturnsNull() throws Exception {
        touchJar("foo.jar");
        writeModInfo("""
            javaJarFile=foo.jar
            javaPkgName=some.pkg
            zbVersionMax=1.0.0
            """);
        setZbVersion("9.9.9");

        assertNull(JavaModInfo.parse(tempDir));
    }

    @Test
    void rejectsParentTraversalOutsideModRoot() throws Exception {
        Path modDir = tempDir.resolve("mod");
        Files.createDirectories(modDir);
        Files.createFile(tempDir.resolve("outside.jar"));
        Files.writeString(modDir.resolve("mod.info"), """
            javaJarFile=../outside.jar
            javaPkgName=some.pkg
            """);
        assertNull(JavaModInfo.parse(modDir));
    }

    @Test
    void rejectsWindowsDriveRelativeJarPath() throws Exception {
        writeModInfo("javaJarFile=C:outside.jar\njavaPkgName=some.pkg\n");
        assertNull(JavaModInfo.parse(tempDir));
    }

    @Test
    void rejectsAbsoluteJarPath() throws Exception {
        Path jar = tempDir.resolve("absolute.jar");
        Files.createFile(jar);
        writeModInfo("javaJarFile=" + jar.toAbsolutePath() + "\njavaPkgName=some.pkg\n");
        assertNull(JavaModInfo.parse(tempDir));
    }

    @Test
    void allowsJarInsideModRoot() throws Exception {
        Files.createDirectories(tempDir.resolve("media/java"));
        Files.createFile(tempDir.resolve("media/java/inside.jar"));
        writeModInfo("""
            javaJarFile=media/java/inside.jar
            javaPkgName=some.pkg
            """);
        JavaModInfo info = JavaModInfo.parse(tempDir);
        assertNotNull(info);
        assertEquals(tempDir.resolve("media/java/inside.jar"), info.jarPath());
    }

    @Test
    void allowsB42ParentLibsInsideModRoot() throws Exception {
        Path modRoot = tempDir.resolve("TestMod");
        Path common = modRoot.resolve("common");
        Path version = modRoot.resolve("42");
        Files.createDirectories(common);
        Files.createDirectories(version);
        Files.createDirectories(modRoot.resolve("libs"));
        Files.createFile(modRoot.resolve("libs/TestMod.jar"));
        Files.writeString(version.resolve("mod.info"), """
            javaJarFile=../libs/TestMod.jar
            javaPkgName=some.pkg
            """);

        JavaModInfo info = JavaModInfo.parseMerged(common, version);
        assertNotNull(info);
        assertEquals(modRoot.resolve("libs/TestMod.jar"), info.jarPath());
    }

    @Test
    void rejectsB42ParentLibsOutsideModRoot() throws Exception {
        Path modRoot = tempDir.resolve("TestMod");
        Path common = modRoot.resolve("common");
        Path version = modRoot.resolve("42");
        Files.createDirectories(common);
        Files.createDirectories(version);
        Files.createFile(tempDir.resolve("outside.jar"));
        Files.writeString(version.resolve("mod.info"), """
            javaJarFile=../../outside.jar
            javaPkgName=some.pkg
            """);

        assertNull(JavaModInfo.parseMerged(common, version));
    }

    @Test
    void rejectsSymlinkEscapeFromModRoot() throws Exception {
        Path modDir = tempDir.resolve("mod");
        Files.createDirectories(modDir);
        Path target = tempDir.resolve("outside.jar");
        Files.createFile(target);
        Path link = modDir.resolve("link.jar");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return; // Filesystem does not support symlinks.
        }
        Files.writeString(modDir.resolve("mod.info"), """
            javaJarFile=link.jar
            javaPkgName=some.pkg
            """);
        assertNull(JavaModInfo.parse(modDir));
    }

    @Test
    void versionDirAllowsParentLibsInsideModRoot() throws Exception {
        Path modRoot = tempDir.resolve("TestMod");
        Path version = modRoot.resolve("42");
        Files.createDirectories(version);
        Files.createDirectories(modRoot.resolve("libs"));
        Files.createFile(modRoot.resolve("libs/TestMod.jar"));
        Files.writeString(version.resolve("mod.info"), """
            javaJarFile=../libs/TestMod.jar
            javaPkgName=some.pkg
            """);

        JavaModInfo info = JavaModInfo.parseVersionDir(version);
        assertNotNull(info);
        assertEquals(modRoot.resolve("libs/TestMod.jar"), info.jarPath());
    }

    @Test
    void versionDirRejectsEscapeBeyondModRoot() throws Exception {
        Path modRoot = tempDir.resolve("TestMod");
        Path version = modRoot.resolve("42");
        Files.createDirectories(version);
        Files.createFile(tempDir.resolve("outside.jar"));
        Files.writeString(version.resolve("mod.info"), """
            javaJarFile=../../outside.jar
            javaPkgName=some.pkg
            """);

        assertNull(JavaModInfo.parseVersionDir(version));
    }
}
