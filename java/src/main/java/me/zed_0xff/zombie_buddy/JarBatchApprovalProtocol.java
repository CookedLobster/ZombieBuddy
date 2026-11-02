package me.zed_0xff.zombie_buddy;

import static me.zed_0xff.zombie_buddy.SteamWorkshop.SteamID64;
import static me.zed_0xff.zombie_buddy.SteamWorkshop.WorkshopItemID;
import static me.zed_0xff.zombie_buddy.ModFlags.MF_PERSIST;
import static me.zed_0xff.zombie_buddy.ModFlags.MF_TRUST_AUTHOR;
import me.zed_0xff.zombie_buddy.ModFlags;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

/**
 * JSON file protocol between {@link Loader} (game process) and
 * {@link me.zed_0xff.zombie_buddy.frontend.SwingApprovalMain} (non-headless child JVM with Swing UI).
 */
public final class JarBatchApprovalProtocol {

    static final String HDR_REQ  = "ZB_BATCH_V8";
    static final String HDR_RESP = "ZB_BATCH_V8_OUT";
    private static final Gson JSON = ZBGson.PRETTY;

    public static final class Entry {
        public final String modId;
        public final WorkshopItemID workshopItemId;
        public final Path jarAbsolutePath;
        public final Path infAbsolutePath;
        public final String sha256;
        public final Date date;
        public Boolean decision;
        public ModFlags flags = ModFlags.EMPTY;
        public final String modDisplayName;
        public final ZBSignature zbs;
        public final SteamBan steamBan;

        public Entry(
            String modId,
            WorkshopItemID workshopItemId,
            Path jarAbsolutePath,
            Path infAbsolutePath,
            String sha256,
            Date date,
            Boolean decision,
            ModFlags flags,
            String modDisplayName,
            ZBSignature zbs,
            SteamBan steamBan
        ) {
            this.modId = modId;
            this.workshopItemId = workshopItemId;
            this.jarAbsolutePath = jarAbsolutePath;
            this.infAbsolutePath = infAbsolutePath;
            this.sha256 = sha256 != null ? sha256 : "";
            this.date = date;
            this.decision = decision;
            this.flags = flags != null ? flags : ModFlags.EMPTY;
            this.modDisplayName = modDisplayName != null ? modDisplayName : "";
            this.zbs = zbs != null ? zbs : ZBSignature.none();
            this.steamBan = steamBan;
        }

        public record SteamBan(SteamWorkshop.BanStatus status, String reason) {
            public SteamBan {
                status = status != null ? status : SteamWorkshop.BanStatus.UNKNOWN;
                reason = reason != null ? reason : "";
            }

            public static SteamBan banned(String reason) {
                return new SteamBan(SteamWorkshop.BanStatus.BANNED, reason);
            }

            public static SteamBan unknown(String reason) {
                return new SteamBan(SteamWorkshop.BanStatus.UNKNOWN, reason);
            }

            public boolean isBanned() {
                return status == SteamWorkshop.BanStatus.BANNED;
            }

            public boolean isUnknown() {
                return status == SteamWorkshop.BanStatus.UNKNOWN;
            }
        }

        public record ZBSignature(
            boolean valid,
            SteamID64 authorSteamId,
            String notice
        ) {
            public ZBSignature {
                notice = notice != null ? notice : "";
            }

            public static ZBSignature none() {
                return new ZBSignature(false, null, "");
            }

            public boolean invalid() {
                return !valid && !Utils.isBlank(notice);
            }

            public boolean unsigned() {
                return !valid && authorSteamId == null && Utils.isBlank(notice);
            }
        }
    }

    /**
     * Writes an approval request and returns the random nonce embedded in it. The child dialog
     * must echo the nonce in its response; the parent rejects a response with a different nonce.
     */
    public static String writeRequest(Path path, List<Entry> entries) throws IOException {
        List<Entry> safe = entries == null ? Collections.emptyList() : entries;
        String nonce = newNonce();
        Utils.writeFileAtomic(path, JSON.toJson(new RequestEnvelope(HDR_REQ, nonce, safe)), StandardCharsets.UTF_8);
        return nonce;
    }

    public static List<Entry> readRequest(Path path) throws IOException {
        return readRequestWithNonce(path).entries();
    }

    public static RequestEnvelope readRequestWithNonce(Path path) throws IOException {
        try (Reader r = Files.newBufferedReader(path)) {
            RequestEnvelope env = JSON.fromJson(r, RequestEnvelope.class);
            if (env == null || !HDR_REQ.equals(env.header)) {
                throw new IOException("Bad request header: " + (env != null ? env.header : null));
            }
            return new RequestEnvelope(HDR_REQ, env.nonce, env.entries != null ? env.entries : Collections.emptyList());
        }
    }

    public static void writeResponse(Path path, List<Entry> entries) throws IOException {
        writeResponse(path, entries, "");
    }

    public static void writeResponse(Path path, List<Entry> entries, String nonce) throws IOException {
        List<Entry> safe = entries == null ? Collections.emptyList() : entries;
        Utils.writeFileAtomic(path, JSON.toJson(new ResponseEnvelope(HDR_RESP, nonce != null ? nonce : "", safe)), StandardCharsets.UTF_8);
    }

    public static List<Entry> readResponse(Path path) throws IOException {
        ResponseEnvelope env = readResponseWithNonce(path);
        return env != null ? env.entries() : null;
    }

    public static ResponseEnvelope readResponseWithNonce(Path path) throws IOException {
        try (Reader r = Files.newBufferedReader(path)) {
            ResponseEnvelope env = JSON.fromJson(r, ResponseEnvelope.class);
            if (env == null || !HDR_RESP.equals(env.header)) {
                return null;
            }
            return new ResponseEnvelope(HDR_RESP, env.nonce, env.entries != null ? env.entries : Collections.emptyList());
        }
    }

    /**
     * Validates a response row against its request row and strips fields the dialog must not be
     * able to change. Returns {@code null} if the response cannot be trusted; otherwise returns a
     * sanitized list safe for {@link Loader#applyBatchApprovalLines}.
     */
    public static List<Entry> sanitizeResponse(List<Entry> request, List<Entry> response) {
        if (request == null || response == null || request.size() != response.size()) {
            return null;
        }
        List<Entry> out = new ArrayList<>(response.size());
        for (int i = 0; i < request.size(); i++) {
            Entry expected = request.get(i);
            Entry actual = response.get(i);
            if (expected == null || actual == null || !sameRequestRow(expected, actual)) {
                return null;
            }

            Entry.ZBSignature expectedZbs = expected.zbs != null ? expected.zbs : Entry.ZBSignature.none();
            ModFlags expectedFlags = expected.flags != null ? expected.flags : ModFlags.EMPTY;
            ModFlags actualFlags = actual.flags != null ? actual.flags : ModFlags.EMPTY;

            boolean safeToAllow = !expectedZbs.invalid()
                && (expected.steamBan == null || !expected.steamBan.isBanned());
            actual.decision = safeToAllow ? actual.decision : Boolean.FALSE;

            int flags = expectedFlags.value() & ~(MF_PERSIST | MF_TRUST_AUTHOR);
            if (actualFlags.has(MF_PERSIST)) {
                flags |= MF_PERSIST;
            }
            boolean canTrust = expectedZbs.valid()
                && expectedZbs.authorSteamId() != null
                && (expected.steamBan == null || (!expected.steamBan.isBanned() && !expected.steamBan.isUnknown()));
            if (actualFlags.has(MF_TRUST_AUTHOR) && canTrust) {
                flags |= MF_TRUST_AUTHOR;
            }
            actual.flags = new ModFlags(flags);
            out.add(actual);
        }
        return out;
    }

    private static boolean sameRequestRow(Entry expected, Entry actual) {
        return Objects.equals(expected.modId, actual.modId)
            && Objects.equals(expected.workshopItemId, actual.workshopItemId)
            && Objects.equals(expected.jarAbsolutePath, actual.jarAbsolutePath)
            && Objects.equals(expected.infAbsolutePath, actual.infAbsolutePath)
            && Objects.equals(expected.sha256, actual.sha256)
            && Objects.equals(expected.zbs, actual.zbs)
            && Objects.equals(expected.steamBan, actual.steamBan);
    }

    private static String newNonce() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public record RequestEnvelope(
        @SerializedName("header") String header,
        @SerializedName("nonce") String nonce,
        @SerializedName("entries") List<Entry> entries
    ) {
        public RequestEnvelope {
            entries = entries != null ? entries : new ArrayList<>();
            nonce = nonce != null ? nonce : "";
        }
    }

    public record ResponseEnvelope(
        @SerializedName("header") String header,
        @SerializedName("nonce") String nonce,
        @SerializedName("entries") List<Entry> entries
    ) {
        public ResponseEnvelope {
            entries = entries != null ? entries : new ArrayList<>();
            nonce = nonce != null ? nonce : "";
        }
    }
}
