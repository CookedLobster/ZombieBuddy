package me.zed_0xff.zombie_buddy.frontend;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import me.zed_0xff.zombie_buddy.JarBatchApprovalProtocol;
import me.zed_0xff.zombie_buddy.Logger;
import me.zed_0xff.zombie_buddy.Reflect;
import me.zed_0xff.zombie_buddy.Utils;

/**
 * LWJGL {@code TinyFileDialogs} (works when the game JVM is {@code java.awt.headless=true}).
 * No multi-mod window: {@link #approvePendingMods} prompts one entry at a time.
 */
public final class TinyfdModApprovalFrontend implements ModApprovalFrontend {

    private static final String DIALOG_TITLE = "ZombieBuddy Java mod approval";
    private static final String DATE_FORMAT = "yyyy-MM-dd";

    @Override
    public List<JarBatchApprovalProtocol.Entry> approvePendingMods(List<JarBatchApprovalProtocol.Entry> pending) {
        if (pending.isEmpty()) {
            return pending;
        }
        for (JarBatchApprovalProtocol.Entry e : pending) {
            Boolean allow = promptForEntry(e);
            if (allow != null) {
                e.decision = allow;
            }
        }
        return pending;
    }

    private static Boolean promptForEntry(JarBatchApprovalProtocol.Entry e) {
        if (e.zbs.invalid()) {
            String note = !Utils.isBlank(e.zbs.notice())
                ? e.zbs.notice()
                : "Invalid ZBS — load will be denied.";
            tinyfdYesNo(
                "ZBS invalid — this Java mod cannot be loaded.\n\n"
                    + note
                    + "\n\nIt will be denied."
            );
            return false;
        }

        if (e.steamBan != null && e.steamBan.isBanned()) {
            String reason = !Utils.isBlank(e.steamBan.reason()) ? "\n\nReason: " + e.steamBan.reason() : "";
            tinyfdYesNo(
                "Steam Workshop reports this item as banned.\n\n"
                    + "Mod: " + e.modId + reason
                    + "\n\nIt will be denied."
            );
            return false;
        }

        String modified = formatDate(e.date);
        String zbsLine = "";
        if (e.zbs.valid() || e.zbs.invalid() || e.zbs.unsigned()) {
            String sid = e.zbs.authorSteamId() != null ? e.zbs.authorSteamId().toString() : "";
            zbsLine = "ZBS: " + zbsStatus(e)
                + (!sid.isEmpty() ? " (Steam: " + sid + ")" : "")
                + "\n\n";
        }
        String banLine = "";
        if (e.steamBan != null && e.steamBan.isUnknown()) {
            banLine = "Steam ban status: UNKNOWN"
                + (!Utils.isBlank(e.steamBan.reason()) ? " (" + e.steamBan.reason() + ")" : "")
                + "\n\n";
        }
        Boolean allow = tinyfdYesNo(
            "Allow Java mod to load?\n\n"
                + zbsLine
                + banLine
                + "Mod: " + e.modId + "\n\n"
                + "JAR: " + e.jarAbsolutePath + "\n\n"
                + "Modified: " + modified + "\n\n"
                + "SHA-256: " + e.sha256 + "\n\n"
                + "Only allow if you trust this mod source."
        );
        if (allow == null) {
            return false;
        }
        return allow;
    }

    private static String zbsStatus(JarBatchApprovalProtocol.Entry e) {
        if (e.zbs.valid()) {
            return "valid";
        }
        if (e.zbs.invalid()) {
            return "invalid";
        }
        return "unsigned";
    }

    private static String formatDate(Date date) {
        if (date == null) {
            return "<unknown>";
        }
        return new SimpleDateFormat(DATE_FORMAT, Locale.ROOT).format(date);
    }

    /**
     * Returns TRUE (Yes), FALSE (No), or null if the dialog could not be shown.
     * A missing dialog library is fail-closed: the caller treats null as "deny".
     */
    static Boolean tinyfdYesNo(String msg) {
        Reflect r = Reflect.on("org.lwjgl.util.tinyfd.TinyFileDialogs");
        if (!r.isPresent()) {
            Logger.error("TinyFileDialogs is unavailable; cannot show the approval dialog. Denying the mod. Use frontend=swing or frontend=console.");
            return null;
        }
        try {
            return (boolean) Reflect
                .fastcall(() -> Reflect.on("org.lwjgl.util.tinyfd.TinyFileDialogs").getMethodHandle(
                            boolean.class,
                            new Class<?>[] {
                                CharSequence.class,
                                CharSequence.class,
                                CharSequence.class,
                                CharSequence.class,
                                boolean.class
                            },
                            "tinyfd_messageBox"))
                .invokeExact("tinyfd_messageBox", DIALOG_TITLE, msg, "yesno", "warning", false);
        } catch (Throwable t) {
            Logger.error("Could not show dialog: " + t);
            return null;
        }
    }
}
