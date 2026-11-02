package me.zed_0xff.zombie_buddy;

import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Handles replacing the running ZombieBuddy JAR with a newer version
 * (e.g. from the mods directory). Supports immediate replace or deferred
 * replace via a .new file when the current JAR is locked (e.g. on Windows).
 * Also handles verification of JAR signatures and certificates.
 */
public final class SelfUpdater {
    private SelfUpdater() {}

    private static String pendingNewVersion = null;

    /**
     * Expected SHA-256 fingerprint of the ZombieBuddy signing certificate.
     * Used to verify that a JAR is signed by the trusted ZombieBuddy key.
     */
    private static final byte[] EXPECTED_FINGERPRINT = {
        (byte)0xA7, (byte)0x75, (byte)0x10, (byte)0x1B, (byte)0xFB, (byte)0x6C, (byte)0x33, (byte)0xA9,
        (byte)0x2C, (byte)0xDF, (byte)0x25, (byte)0x20, (byte)0xAC, (byte)0x8D, (byte)0x02, (byte)0x95,
        (byte)0xCE, (byte)0xBF, (byte)0x89, (byte)0x0C, (byte)0x84, (byte)0x05, (byte)0x97, (byte)0x37,
        (byte)0x7F, (byte)0xD0, (byte)0x9B, (byte)0x17, (byte)0xD0, (byte)0xEA, (byte)0xDD, (byte)0x97
    };

    public static String getNewVersion() {
        return pendingNewVersion;
    }

    public static String getExclusionReasonSuffix(Path jarPath) {
        if (jarPath == null) {
            return " (null not found)";
        }
        if (!Files.isRegularFile(jarPath)) {
            return " (" + jarPath.toAbsolutePath() + " not found)";
        }

        StringBuilder sb = new StringBuilder();
        Manifest manifest = getJarManifest(jarPath);
        if (manifest != null) {
            String manifestVersion = manifest.getMainAttributes().getValue("Implementation-Version");
            if (manifestVersion != null) {
                sb.append(" (version ").append(manifestVersion).append(")");
            }
        }

        Path currentJarPath = Utils.getCurrentJarPath();
        if (checkAndUpdateIfNewer(jarPath, currentJarPath, ZombieBuddy.getVersion(), Loader.g_verbosity)) {
            String newVer = getNewVersion();
            if (newVer != null) {
                sb.append(" -> updating to ").append(newVer);
            }
        }
        return sb.toString();
    }

    public static void performUpdate(Path currentJarPath, Path newJarPath, String newVersion) {
        if (currentJarPath == null || newJarPath == null) {
            return;
        }
        Path staged = null;
        try {
            Path current = currentJarPath.toAbsolutePath();
            Path source = newJarPath.toAbsolutePath();
            Path dir = current.getParent();
            if (dir == null) {
                Logger.error("Cannot determine the ZombieBuddy JAR directory; update aborted.");
                return;
            }

            // Re-verify the source immediately before copying, then verify the staged copy
            // itself so a local swap between verification and copy cannot slip through.
            verifyTrustedSigner(source);
            String sourceHash = Utils.sha256Hex(source);
            if (Utils.isBlank(sourceHash)) {
                Logger.error("Could not hash the update JAR " + source + "; update aborted.");
                return;
            }

            Files.createDirectories(dir);
            staged = Files.createTempFile(dir, current.getFileName().toString() + ".", ".newtmp");
            Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING);
            String stagedHash = Utils.sha256Hex(staged);
            if (!sourceHash.equals(stagedHash)) {
                Logger.error("Staged update JAR hash mismatch (expected " + sourceHash
                    + ", got " + stagedHash + "); update aborted.");
                return;
            }
            verifyTrustedSigner(staged);

            Logger.info("replacing " + current + " with " + source);
            Path backupPath = current.resolveSibling(current.getFileName() + ".bak");
            Path deferredPath = current.resolveSibling(current.getFileName() + ".new");
            Path deferredHashPath = current.resolveSibling(current.getFileName() + ".new.sha256");
            boolean movedCurrent = false;

            try {
                if (Files.exists(current)) {
                    Files.move(current, backupPath, StandardCopyOption.REPLACE_EXISTING);
                    movedCurrent = true;
                    Logger.info("Renamed existing JAR to " + backupPath);
                }
                moveReplace(staged, current);
                staged = null;
                Files.deleteIfExists(deferredHashPath);
                pendingNewVersion = newVersion;
                Logger.info("Successfully replaced JAR file");
            } catch (Exception e) {
                Logger.error("Error replacing JAR file: " + e.getMessage());
                // If the live JAR was moved aside but the replacement failed, restore it.
                if (movedCurrent && !Files.exists(current)) {
                    try {
                        Files.move(backupPath, current, StandardCopyOption.REPLACE_EXISTING);
                        Logger.info("Restored the previous JAR from " + backupPath);
                    } catch (Exception restoreError) {
                        Logger.error("Could not restore the previous JAR from " + backupPath + ": "
                            + restoreError.getMessage());
                    }
                }
                Logger.error("JAR may be locked (e.g., on Windows). Copying to .new file for deferred update...");
                try {
                    moveReplace(staged, deferredPath);
                    staged = null;
                    // The native launcher only applies .new when this hash matches. This
                    // catches partial writes and accidental mismatches; it is not a signature.
                    Utils.writeFileAtomic(deferredHashPath, stagedHash + "\n", java.nio.charset.StandardCharsets.UTF_8);
                    pendingNewVersion = newVersion;
                    Logger.info("Copied verified new JAR to " + deferredPath
                        + " - update will be applied on next game launch");
                } catch (Exception e2) {
                    Logger.error("Error copying to .new file: " + e2.getMessage());
                    Logger.printStackTrace(e2);
                }
                Logger.printStackTrace(e);
            }
        } catch (Exception e) {
            Logger.error("Error preparing JAR update: " + e.getMessage());
            Logger.printStackTrace(e);
        } finally {
            if (staged != null) {
                try {
                    Files.deleteIfExists(staged);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void moveReplace(Path source, Path target) throws Exception {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static boolean checkAndUpdateIfNewer(Path jarPath, Path currentJarPath, String currentVersion, int verbosity) {
        if (jarPath == null || !Files.isRegularFile(jarPath)) {
            return false;
        }

        try {
            X509Certificate signer = verifyTrustedSigner(jarPath);
            if (signer == null) {
                return false;
            }

            if (verbosity > 0) {
                Logger.info("" + jarPath + " is signed by the expected ZombieBuddy certificate");
            }

            Manifest manifest = getJarManifest(jarPath);
            if (manifest != null) {
                String manifestVersion = manifest.getMainAttributes().getValue("Implementation-Version");
                if (manifestVersion != null && Utils.isVersionNewer(manifestVersion, currentVersion)) {
                    performUpdate(currentJarPath, jarPath, manifestVersion);
                    return true;
                }
            }
        } catch (Exception e) {
            Logger.error("Error verifying JAR signature: " + e.getMessage());
        }
        return false;
    }

    /**
     * Verifies that the JAR is signed, that every signed entry uses the same signer, that the
     * certificate chain is currently valid, and that the leaf certificate matches the pinned
     * ZombieBuddy fingerprint.
     */
    static X509Certificate verifyTrustedSigner(Path jarPath) throws Exception {
        Certificate[] certs = verifyJarAndGetCerts(jarPath);
        if (certs == null || certs.length == 0) {
            throw new SecurityException("No signed entries found");
        }

        X509Certificate leaf = null;
        for (Certificate cert : certs) {
            if (!(cert instanceof X509Certificate x509)) {
                throw new SecurityException("Non-X.509 signer certificate");
            }
            try {
                x509.checkValidity();
            } catch (CertificateExpiredException | CertificateNotYetValidException e) {
                throw new SecurityException("Signer certificate is not currently valid: " + e.getMessage(), e);
            }
            if (leaf == null) {
                leaf = x509;
            }
        }
        if (leaf == null) {
            throw new SecurityException("No X.509 signer certificate");
        }

        // JAR code signing requires digitalSignature (bit 0). If keyUsage is absent,
        // the certificate is not restricted and validity falls back to the CA basic
        // constraints/signature checks performed by JarFile.
        boolean[] keyUsage = leaf.getKeyUsage();
        if (keyUsage != null && (keyUsage.length == 0 || !keyUsage[0])) {
            throw new SecurityException("Signer certificate does not allow digital signatures");
        }

        byte[] fingerprint = Utils.sha256(leaf.getEncoded());
        if (fingerprint == null || !Arrays.equals(fingerprint, EXPECTED_FINGERPRINT)) {
            throw new SecurityException("JAR signer certificate fingerprint is not the expected ZombieBuddy key");
        }
        return leaf;
    }

    static boolean sameSignerCertificate(Certificate[] a, Certificate[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) {
            return false;
        }
        try {
            return MessageDigest.isEqual(a[0].getEncoded(), b[0].getEncoded());
        } catch (Exception e) {
            return false;
        }
    }

    public static Certificate[] verifyJarAndGetCerts(Path jarPath) throws Exception {
        Manifest mf = getJarManifest(jarPath);
        if (mf == null) {
            return null;
        }

        try (JarFile jar = new JarFile(jarPath.toFile(), true)) {
            Certificate[] signer = null;

            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e.isDirectory()) continue;

                try (InputStream is = jar.getInputStream(e)) {
                    is.readAllBytes();
                }

                String name = e.getName();
                boolean isMeta = name.startsWith("META-INF/");
                Certificate[] certs = e.getCertificates();
                if (certs == null || certs.length == 0) {
                    if (!isMeta) {
                        throw new SecurityException("Unsigned entry: " + name);
                    }
                } else if (!isMeta) {
                    if (signer == null) {
                        signer = certs;
                    } else if (!sameSignerCertificate(signer, certs)) {
                        throw new SecurityException("JAR has multiple signers (entry " + name + ")");
                    }
                }
            }

            if (signer == null) {
                throw new SecurityException("No signed entries found");
            }

            return signer;
        }
    }

    public static Manifest getJarManifest(Path jarPath) {
        if (jarPath == null) {
            return null;
        }
        try (JarFile jar = new JarFile(jarPath.toFile(), true)) {
            return jar.getManifest();
        } catch (Exception e) {
            Logger.error("Error getting JAR manifest: " + e);
            return null;
        }
    }

    private static byte[] getCertFingerprint(X509Certificate cert, int certNumber, int verbosity) {
        if (verbosity > 0) {
            Logger.info("  Certificate " + certNumber + ":");
            Logger.info("    Subject: " + cert.getSubjectX500Principal().getName());
            Logger.info("    Issuer: " + cert.getIssuerX500Principal().getName());
            Logger.info("    Serial Number: " + cert.getSerialNumber().toString(16).toUpperCase());
            Logger.info("    Valid From: " + cert.getNotBefore());
            Logger.info("    Valid Until: " + cert.getNotAfter());
        }

        try {
            byte[] sha256Bytes = Utils.sha256(cert.getEncoded());
            if (sha256Bytes != null && verbosity > 0) {
                Logger.info("    SHA-256 Fingerprint: " + Utils.bytesToHex(sha256Bytes, "%02X", ":"));
            }
            return sha256Bytes;
        } catch (Exception e) {
            Logger.error("    Error computing certificate fingerprints: " + e.getMessage());
        }
        return null;
    }
}
