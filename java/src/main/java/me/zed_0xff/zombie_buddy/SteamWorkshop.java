package me.zed_0xff.zombie_buddy;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * Steam Workshop API client for fetching mod details and ban status.
 */
public final class SteamWorkshop {
    public static final String PZ_APP_ID = "108600";

    /** Strongly-typed Steam account id. */
    public static record SteamID64(long value) {
        public SteamID64(String s) { this(Long.parseLong(s)); }
        @Override public String toString() { return Long.toString(value); }
    }

    /** Strongly-typed Steam Workshop item id ({@code publishedfileid}). */
    public static record WorkshopItemID(long value) {}

    public static String authorProfileUrl(SteamID64 steamId) {
        return steamId != null
            ? "https://steamcommunity.com/profiles/" + steamId.value() + "/"
            : "";
    }

    public static String authorWorkshopUrl(SteamID64 steamId) {
        return steamId != null
            ? "https://steamcommunity.com/profiles/" + steamId.value() + "/myworkshopfiles/?appid=" + PZ_APP_ID
            : "";
    }

    public static String workshopItemUrl(WorkshopItemID workshopItemId) {
        return workshopItemId != null
            ? "https://steamcommunity.com/sharedfiles/filedetails/?id=" + workshopItemId.value()
            : "";
    }

    private static final String STEAM_GET_PUBLISHED_FILE_DETAILS_URL = "https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/";
    private static final int BATCH_SIZE = 100;
    public  static final int DEFAULT_TIMEOUT = 5;

    private record CachedResponse(String body, long expireAt) {
        boolean isExpired() { return System.currentTimeMillis() > expireAt; }
    }
    static final int HTTP_CACHE_TTL_S = parseHttpCacheTTL();
    private static final ConcurrentHashMap<String, CachedResponse> HTTP_CACHE = new ConcurrentHashMap<>();

    private static int parseHttpCacheTTL() {
        return Math.max(0, Agent.getArgInt("http_cache_ttl", 3600));
    }

    static String getCachedBody(String url, String requestBody) {
        if (HTTP_CACHE_TTL_S <= 0) return null;
        CachedResponse r = HTTP_CACHE.get(url + "\0" + requestBody);
        return (r != null && !r.isExpired()) ? r.body() : null;
    }

    static void putCachedBody(String url, String requestBody, String body) {
        if (HTTP_CACHE_TTL_S <= 0) return;
        HTTP_CACHE.put(url + "\0" + requestBody, new CachedResponse(body, System.currentTimeMillis() + HTTP_CACHE_TTL_S * 1000L));
    }

    static final Duration HTTP_TIMEOUT = parseHttpTimeout();
    private static HttpClient httpClient;

    /** Built lazily: HttpClient creation opens a Selector, which can fail on some platforms (e.g. Wine/Proton) and must not break class init. */
    static synchronized HttpClient http() throws IOException {
        if (httpClient == null) {
            try {
                httpClient = HttpClient.newBuilder()
                    .connectTimeout(HTTP_TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            } catch (RuntimeException | Error e) {
                throw new IOException("Could not create HTTP client: " + e, e);
            }
        }
        return httpClient;
    }

    private static Duration parseHttpTimeout() {
        // Documented key first; retain the old undocumented key as a fallback
        // for existing launch configurations.
        int timeout = Agent.getArgInt("http_client_timeout",
            Agent.getArgInt("http.client.timeout", DEFAULT_TIMEOUT));
        return Duration.ofSeconds(Math.max(1, timeout));
    }

    private SteamWorkshop() {}

    /** Tri-state Steam ban status. */
    public enum BanStatus {
        BANNED,
        NOT_BANNED,
        UNKNOWN
    }

    /**
     * Ban status info for a Workshop item. Unlike the previous Boolean model,
     * unknown status is explicit and never treated as "not banned".
     */
    public record BanInfo(BanStatus status, String reason) {
        public BanInfo {
            status = status != null ? status : BanStatus.UNKNOWN;
            reason = reason != null ? reason : "";
        }

        /** Backward-compatible constructor for callers/tests that still use Boolean. */
        public BanInfo(Boolean legacyStatus, String reason) {
            this(
                legacyStatus == null
                    ? BanStatus.UNKNOWN
                    : (legacyStatus ? BanStatus.BANNED : BanStatus.NOT_BANNED),
                reason
            );
        }

        public static BanInfo banned(String reason) {
            return new BanInfo(BanStatus.BANNED, reason);
        }

        public static BanInfo notBanned() {
            return new BanInfo(BanStatus.NOT_BANNED, "");
        }

        public static BanInfo unknown(String reason) {
            return new BanInfo(BanStatus.UNKNOWN, reason);
        }

        public boolean isBanned() {
            return status == BanStatus.BANNED;
        }

        public boolean isUnknown() {
            return status == BanStatus.UNKNOWN;
        }
    }

    /** Details fetched from Steam API for a Workshop item. */
    public record ItemDetails(BanInfo ban, SteamID64 creatorSteamId64) {}

    /**
     * Fetch Workshop item details (ban status, creator) for the given IDs.
     * @return map of workshop ID to details; unknown items get {@code null} ban status
     */
    public static Map<WorkshopItemID, ItemDetails> fetchItemDetails(
        Set<WorkshopItemID> workshopIds
    ) {
        Map<WorkshopItemID, ItemDetails> out = new HashMap<>();
        if (Utils.isBlank(workshopIds)) return out;

        Logger.info("checking mods ban status");
        try {
            List<WorkshopItemID> ids = new ArrayList<>(workshopIds);
            for (int from = 0; from < ids.size(); from += BATCH_SIZE) {
                int to = Math.min(ids.size(), from + BATCH_SIZE);
                fetchChunk(ids.subList(from, to), out);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            setUnknownDetails(out, workshopIds, "Steam API request interrupted");
        } catch (Exception e) {
            setUnknownDetails(out, workshopIds, "Steam API request failed: " + e.getMessage());
        }
        return out;
    }

    private static void fetchChunk(
        List<WorkshopItemID> chunk,
        Map<WorkshopItemID, ItemDetails> out
    ) throws Exception {
        StringBuilder bodySb = new StringBuilder();
        bodySb.append("itemcount=").append(chunk.size());
        for (int i = 0; i < chunk.size(); i++) {
            bodySb.append("&publishedfileids[").append(i).append("]=")
                .append(URLEncoder.encode(Long.toString(chunk.get(i).value()), java.nio.charset.StandardCharsets.UTF_8));
        }
        String requestBody = bodySb.toString();
        String responseBody = getCachedBody(STEAM_GET_PUBLISHED_FILE_DETAILS_URL, requestBody);
        if (responseBody == null) {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(STEAM_GET_PUBLISHED_FILE_DETAILS_URL))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
            HttpResponse<String> resp = http().send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                setUnknownDetails(out, new HashSet<>(chunk),
                    "Steam API request failed (HTTP " + resp.statusCode() + ")");
                return;
            }
            responseBody = resp.body();
            putCachedBody(STEAM_GET_PUBLISHED_FILE_DETAILS_URL, requestBody, responseBody);
        }
        JsonElement root = JsonParser.parseString(responseBody);
        JsonElement response = root != null && root.isJsonObject() ? root.getAsJsonObject().get("response") : null;
        JsonElement details = (response != null && response.isJsonObject())
            ? response.getAsJsonObject().get("publishedfiledetails")
            : null;
        if (details == null || !details.isJsonArray()) {
            setUnknownDetails(out, new HashSet<>(chunk), "Steam API response missing publishedfiledetails");
            return;
        }
        Set<WorkshopItemID> seen = new HashSet<>();
        for (JsonElement it : details.getAsJsonArray()) {
            if (it == null || !it.isJsonObject()) continue;
            JsonObject itObj = it.getAsJsonObject();
            WorkshopItemID id = parsePublishedFileId(itObj.get("publishedfileid"));
            if (id == null) continue;
            seen.add(id);
            ItemDetails parsed = parseItemDetails(itObj);
            if (parsed != null) {
                out.put(id, parsed);
            }
        }
        for (WorkshopItemID id : chunk) {
            if (!seen.contains(id) && !out.containsKey(id)) {
                out.put(id, new ItemDetails(
                    BanInfo.unknown("Steam API response missing mod id"),
                    null
                ));
            }
        }
    }

    /**
     * Parses one {@code publishedfiledetails} object. A missing or non-1
     * {@code result}, or a missing/invalid {@code banned} field, is treated as
     * {@link BanStatus#UNKNOWN} rather than "not banned".
     */
    static ItemDetails parseItemDetails(JsonObject item) {
        if (item == null) {
            return null;
        }

        long result = parseLong(item.get("result"), Long.MIN_VALUE);
        SteamID64 creator = null;
        try {
            creator = parseCreatorSteamId64(item);
        } catch (RuntimeException ignored) {
            // A malformed creator means uploader binding is unavailable; the
            // caller still applies the ban tri-state below.
        }

        if (result != 1) {
            return new ItemDetails(
                BanInfo.unknown(result == Long.MIN_VALUE
                    ? "Steam API response missing result"
                    : "Steam API result " + result),
                creator
            );
        }

        JsonElement bannedJson = item.get("banned");
        if (bannedJson == null || !bannedJson.isJsonPrimitive() || !bannedJson.getAsJsonPrimitive().isNumber()) {
            return new ItemDetails(
                BanInfo.unknown("Steam API response missing or invalid ban status"),
                creator
            );
        }

        int banned = bannedJson.getAsInt();
        String reason = "";
        JsonElement banReasonJson = item.get("ban_reason");
        if (banReasonJson != null && banReasonJson.isJsonPrimitive() && banReasonJson.getAsJsonPrimitive().isString()) {
            reason = banReasonJson.getAsString();
        }
        return new ItemDetails(
            new BanInfo(banned != 0 ? BanStatus.BANNED : BanStatus.NOT_BANNED, reason),
            creator
        );
    }

    private static long parseLong(JsonElement value, long defaultValue) {
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return defaultValue;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        try {
            if (primitive.isNumber()) {
                return primitive.getAsLong();
            }
            if (primitive.isString()) {
                return Long.parseLong(primitive.getAsString().trim());
            }
        } catch (RuntimeException ignored) {
            // Fall through to the default.
        }
        return defaultValue;
    }

    private static void setUnknownDetails(
        Map<WorkshopItemID, ItemDetails> out,
        Set<WorkshopItemID> workshopIds,
        String reason
    ) {
        for (WorkshopItemID id : workshopIds) {
            if (id == null) continue;
            out.put(id, new ItemDetails(BanInfo.unknown(reason), null));
        }
    }

    private static WorkshopItemID parsePublishedFileId(JsonElement idJson) {
        if (idJson == null || idJson.isJsonNull()) return null;
        if (!idJson.isJsonPrimitive()) return null;
        JsonPrimitive p = idJson.getAsJsonPrimitive();
        if (p.isString()) {
            try {
                return new WorkshopItemID(Long.parseLong(p.getAsString().trim()));
            } catch (Exception e) {
                return null;
            }
        }
        if (p.isNumber()) {
            try {
                return new WorkshopItemID(p.getAsLong());
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static SteamID64 parseCreatorSteamId64(JsonElement item) {
        if (item == null || !item.isJsonObject()) return null;
        JsonElement cj = item.getAsJsonObject().get("creator");
        if (cj == null || cj.isJsonNull()) return null;
        if (!cj.isJsonPrimitive()) return null;
        JsonPrimitive p = cj.getAsJsonPrimitive();
        if (p.isString()) {
            String s = p.getAsString().trim();
            return s.isEmpty() ? null : new SteamID64(Long.parseLong(s));
        }
        if (p.isNumber()) {
            return new SteamID64(p.getAsLong());
        }
        return null;
    }

    /**
     * Get the creator SteamID64 for ZBS verification.
     * @return null if no workshop item or creator unavailable
     */
    public static SteamID64 getUploaderID(
        WorkshopItemID workshopItemId,
        Map<WorkshopItemID, ItemDetails> byId
    ) {
        if (workshopItemId == null) return null;
        ItemDetails d = byId.get(workshopItemId);
        return d != null ? d.creatorSteamId64() : null;
    }

    /** Convert WorkshopItemID to string, or null. */
    public static String idToString(WorkshopItemID wid) {
        return wid != null ? Long.toString(wid.value()) : null;
    }
}
