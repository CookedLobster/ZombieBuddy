package me.zed_0xff.zombie_buddy.frontend;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Locale;

import me.zed_0xff.zombie_buddy.JarBatchApprovalProtocol;
import me.zed_0xff.zombie_buddy.Logger;
import me.zed_0xff.zombie_buddy.Utils;

/**
 * Runs a subprocess executing {@link SwingApprovalMain} (javax.swing). If the subprocess fails,
 * no decisions are applied here; {@link me.zed_0xff.zombie_buddy.Loader} will treat still-unapproved mods according to policy.
 */
public final class SwingModApprovalFrontend implements ModApprovalFrontend {

    @Override
    public List<JarBatchApprovalProtocol.Entry> approvePendingMods(List<JarBatchApprovalProtocol.Entry> pending) {
        if (pending.isEmpty()) {
            return pending;
        }
        List<JarBatchApprovalProtocol.Entry> result = runSwingSubprocessBatch(pending);
        if (result == null) {
            Logger.warn("Swing batch approval failed or unavailable (" + pending.size() + " pending mods)");
            return pending;
        }
        return result;
    }

    private List<JarBatchApprovalProtocol.Entry> runSwingSubprocessBatch(List<JarBatchApprovalProtocol.Entry> pending) {
        String jarPath = Utils.getZombieBuddyJarPath();
        if (jarPath == null) {
            Logger.warn("Batch approval skipped: ZombieBuddy not loaded from a JAR (or path unknown)");
            return null;
        }
        Path tmpDir = null;
        Path tmpIn = null;
        Path tmpOut = null;
        try {
            tmpDir = Files.createTempDirectory("zb-batch-");
            try {
                Files.setPosixFilePermissions(tmpDir, PosixFilePermissions.fromString("rwx------"));
            } catch (UnsupportedOperationException ignored) {
                // Windows / non-POSIX filesystem.
            }
            tmpIn = Files.createTempFile(tmpDir, "zb-batch-req-", ".json");
            tmpOut = Files.createTempFile(tmpDir, "zb-batch-resp-", ".json");
            String nonce = JarBatchApprovalProtocol.writeRequest(tmpIn, pending);
            String javaExe = Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java"
            ).toAbsolutePath().toString();
            ProcessBuilder pb = new ProcessBuilder(
                javaExe,
                "-Djava.awt.headless=false",
                "-cp",
                jarPath,
                SwingApprovalMain.class.getName(),
                tmpIn.toAbsolutePath().toString(),
                tmpOut.toAbsolutePath().toString()
            );
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Logger.info("Starting batch approval subprocess: commandLine=" + pb.command()
                + " pendingEntries=" + pending.size());
            Process p = pb.start();
            p.waitFor();
            if (p.exitValue() != 0) {
                Logger.info("Batch approval subprocess exited with " + p.exitValue());
                return null;
            }
            JarBatchApprovalProtocol.ResponseEnvelope response = JarBatchApprovalProtocol.readResponseWithNonce(tmpOut);
            if (response == null) {
                Logger.warn("Batch approval response malformed");
                return null;
            }
            if (!nonce.equals(response.nonce())) {
                Logger.warn("Batch approval response nonce mismatch; refusing response");
                return null;
            }
            List<JarBatchApprovalProtocol.Entry> lines = response.entries();
            if (lines.size() != pending.size()) {
                Logger.warn("Batch approval response row count mismatch");
                return null;
            }
            for (int i = 0; i < pending.size(); i++) {
                JarBatchApprovalProtocol.Entry expected = pending.get(i);
                JarBatchApprovalProtocol.Entry actual = lines.get(i);
                if (!expected.modId.equals(actual.modId) || !expected.sha256.equals(actual.sha256)) {
                    Logger.warn("Batch approval response row mismatch for " + expected.modId);
                    return null;
                }
            }
            List<JarBatchApprovalProtocol.Entry> sanitized =
                JarBatchApprovalProtocol.sanitizeResponse(pending, lines);
            if (sanitized == null) {
                Logger.warn("Batch approval response changed immutable request fields; refusing response");
                return null;
            }
            return sanitized;
        } catch (Exception e) {
            Logger.error("Batch approval subprocess failed: " + e);
            return null;
        } finally {
            try {
                if (tmpIn != null) {
                    Files.deleteIfExists(tmpIn);
                }
                if (tmpOut != null) {
                    Files.deleteIfExists(tmpOut);
                }
                if (tmpDir != null) {
                    Files.deleteIfExists(tmpDir);
                }
            } catch (Exception ignored) {
            }
        }
    }
}
