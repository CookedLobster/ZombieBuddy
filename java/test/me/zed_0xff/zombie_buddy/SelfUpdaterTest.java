package me.zed_0xff.zombie_buddy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.Certificate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SelfUpdaterTest {

    @TempDir
    Path tempDir;

    @Test
    void unsignedJarIsRejected() throws Exception {
        Path jar = tempDir.resolve("unsigned.jar");
        try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new java.util.jar.JarEntry("Test.class"));
            out.write(new byte[] {(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe});
            out.closeEntry();
        }

        assertNull(SelfUpdater.verifyJarAndGetCerts(jar));
        assertThrows(SecurityException.class, () -> SelfUpdater.verifyTrustedSigner(jar));
    }

    @Test
    void signerCertificatesMustMatchExactly() throws Exception {
        Certificate a = mock(Certificate.class);
        Certificate b = mock(Certificate.class);
        when(a.getEncoded()).thenReturn(new byte[] {1, 2, 3});
        when(b.getEncoded()).thenReturn(new byte[] {1, 2, 4});

        assertTrue(SelfUpdater.sameSignerCertificate(new Certificate[] {a}, new Certificate[] {a}));
        assertFalse(SelfUpdater.sameSignerCertificate(new Certificate[] {a}, new Certificate[] {b}));
        assertFalse(SelfUpdater.sameSignerCertificate(null, new Certificate[] {a}));
        assertFalse(SelfUpdater.sameSignerCertificate(new Certificate[] {a}, new Certificate[0]));
    }
}
