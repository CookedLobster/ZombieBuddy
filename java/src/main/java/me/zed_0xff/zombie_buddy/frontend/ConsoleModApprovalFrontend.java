package me.zed_0xff.zombie_buddy.frontend;

import static me.zed_0xff.zombie_buddy.ModFlags.MF_PERSIST;

import me.zed_0xff.zombie_buddy.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Text-mode approvals on {@link System#in} / {@link System#out}.
 * Intended for headless dedicated servers where Swing/TinyFD are unavailable or undesirable.
 */
public final class ConsoleModApprovalFrontend implements ModApprovalFrontend {
    private static final String DATE_FORMAT = "yyyy-MM-dd";

    private final BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

    @Override
    public List<JarBatchApprovalProtocol.Entry> approvePendingMods(List<JarBatchApprovalProtocol.Entry> pending) {
        if (pending.isEmpty()) {
            return pending;
        }
        Logger.info("Java mod approval (console): " + pending.size() + " mod(s). Answer y/n.");
        for (JarBatchApprovalProtocol.Entry e : pending) {
            System.out.println();
            System.out.println("---");
            System.out.println("Mod id:    " + e.modId);
            System.out.println("Workshop:  " + (e.workshopItemId != null ? e.workshopItemId.value() : "(none)"));
            System.out.println("JAR:       " + e.jarAbsolutePath);
            System.out.println("SHA-256:   " + e.sha256);
            System.out.println("Updated:   " + formatDate(e.date));
            System.out.println("ZBS valid: " + e.zbs.valid());
            if (!Utils.isBlank(e.zbs.notice())) {
                System.out.println("ZBS note:  " + e.zbs.notice());
            }
            if (e.steamBan != null) {
                System.out.println("Steam ban: " + e.steamBan.status()
                    + (!Utils.isBlank(e.steamBan.reason()) ? " (" + e.steamBan.reason() + ")" : ""));
            }
            boolean allow;
            if (e.zbs.invalid()) {
                System.out.println("ZBS invalid — load will be denied.");
                allow = false;
            } else if (e.steamBan != null && e.steamBan.isBanned()) {
                System.out.println("Steam Workshop item is banned — load will be denied.");
                allow = false;
            } else {
                allow = readYesNo("Allow this Java mod to load?");
            }
            e.decision = allow;
            if (readYesNo("Save this decision to disk?")) {
                e.flags = e.flags.with(MF_PERSIST);
            } else {
                e.flags = e.flags.without(MF_PERSIST);
            }
        }
        return pending;
    }

    private boolean readYesNo(String prompt) {
        while (true) {
            System.out.print(prompt + " [y/n]: ");
            System.out.flush();
            String line;
            try {
                line = in.readLine();
            } catch (Exception e) {
                Logger.error("Console approval read failed: " + e);
                return false;
            }
            if (line == null) {
                return false;
            }
            String s = line.trim().toLowerCase(Locale.ROOT);
            if (s.isEmpty()) {
                continue;
            }
            if (s.startsWith("y")) {
                return true;
            }
            if (s.startsWith("n")) {
                return false;
            }
            System.out.println("Please answer y or n.");
        }
    }

    private static String formatDate(Date date) {
        if (date == null) {
            return "(unknown)";
        }
        return new SimpleDateFormat(DATE_FORMAT, Locale.ROOT).format(date);
    }
}
