package dev.verity.core.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.verity.core.net.Http;
import dev.verity.core.util.Json;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Microsoft → Xbox Live → XSTS → Minecraft sign-in using the OAuth device-code flow (the user opens a URL and
 * types a short code; nothing is embedded in Verity). Supports the legacy live.com client id and Azure AD app ids.
 */
public final class MicrosoftAuth {
    public static final String LIVE_CLIENT_ID = "00000000402b5328";

    private final Http http;
    private final String clientId;

    public MicrosoftAuth(Http http, String clientId) {
        this.http = http;
        this.clientId = clientId == null || clientId.isBlank() ? LIVE_CLIENT_ID : clientId;
    }

    public record DeviceCode(String userCode, String deviceCode, String verificationUri, int intervalSec, int expiresInSec) {
    }

    private boolean live() {
        return clientId.equals(LIVE_CLIENT_ID);
    }

    private String deviceCodeUrl() {
        return live() ? "https://login.live.com/oauth20_connect.srf"
                : "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    }

    private String tokenUrl() {
        return live() ? "https://login.live.com/oauth20_token.srf" : "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    }

    private String scope() {
        return live() ? "service::user.auth.xboxlive.com::MBI_SSL" : "XboxLive.signin offline_access";
    }

    public DeviceCode requestDeviceCode() throws IOException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", clientId);
        form.put("scope", scope());
        if (live()) form.put("response_type", "device_code");
        JsonNode n = postForm(deviceCodeUrl(), form, true);
        return new DeviceCode(n.path("user_code").asText(), n.path("device_code").asText(),
                n.path("verification_uri").asText("https://www.microsoft.com/link"), n.path("interval").asInt(5),
                n.path("expires_in").asInt(900));
    }

    /** Polls until the user approved the code, then completes the Minecraft sign-in. */
    public StoredAccount awaitDeviceCode(DeviceCode code) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + code.expiresInSec() * 1000L;
        int interval = Math.max(1, code.intervalSec());
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(interval * 1000L);
            Map<String, String> form = new LinkedHashMap<>();
            form.put("client_id", clientId);
            form.put("device_code", code.deviceCode());
            form.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
            JsonNode n = postForm(tokenUrl(), form, false);
            String error = n.path("error").asText("");
            if (error.equals("authorization_pending")) continue;
            if (error.equals("slow_down")) {
                interval += 5;
                continue;
            }
            if (!error.isEmpty()) throw new IOException("Microsoft sign-in failed: " + error + " " + n.path("error_description").asText(""));
            return completeSignIn(n.path("access_token").asText(), n.path("refresh_token").asText());
        }
        throw new IOException("Device code expired before sign-in completed");
    }

    /** Gets a fresh Minecraft token from the stored Microsoft refresh token. */
    public StoredAccount refresh(StoredAccount account) throws IOException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", clientId);
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", account.msRefreshToken);
        form.put("scope", scope());
        JsonNode n = postForm(tokenUrl(), form, true);
        return completeSignIn(n.path("access_token").asText(), n.path("refresh_token").asText(account.msRefreshToken));
    }

    StoredAccount completeSignIn(String msAccessToken, String msRefreshToken) throws IOException {
        // Xbox Live
        ObjectNode xblReq = Json.MAPPER.createObjectNode();
        ObjectNode props = xblReq.putObject("Properties");
        props.put("AuthMethod", "RPS");
        props.put("SiteName", "user.auth.xboxlive.com");
        props.put("RpsTicket", live() ? msAccessToken : "d=" + msAccessToken);
        xblReq.put("RelyingParty", "http://auth.xboxlive.com");
        xblReq.put("TokenType", "JWT");
        JsonNode xbl = postJson("https://user.auth.xboxlive.com/user/authenticate", xblReq, Map.of());
        String xblToken = xbl.path("Token").asText();
        String uhs = xbl.path("DisplayClaims").path("xui").path(0).path("uhs").asText();

        // XSTS for Minecraft services
        ObjectNode xstsReq = Json.MAPPER.createObjectNode();
        ObjectNode xp = xstsReq.putObject("Properties");
        xp.put("SandboxId", "RETAIL");
        xp.putArray("UserTokens").add(xblToken);
        xstsReq.put("RelyingParty", "rp://api.minecraftservices.com/");
        xstsReq.put("TokenType", "JWT");
        JsonNode xsts = postJson("https://xsts.auth.xboxlive.com/xsts/authorize", xstsReq, Map.of());
        if (xsts.has("XErr")) {
            throw new IOException("Xbox Live refused sign-in (XErr " + xsts.path("XErr").asText()
                    + "): the account may have no Xbox profile, be a child account, or be region restricted");
        }
        String xstsToken = xsts.path("Token").asText();
        String xuid = xsts.path("DisplayClaims").path("xui").path(0).path("xid").asText(null);

        ObjectNode mcReq = Json.MAPPER.createObjectNode();
        mcReq.put("identityToken", "XBL3.0 x=" + uhs + ";" + xstsToken);
        JsonNode mc = postJson("https://api.minecraftservices.com/authentication/login_with_xbox", mcReq, Map.of());
        String mcToken = mc.path("access_token").asText();
        long expires = System.currentTimeMillis() + mc.path("expires_in").asLong(86400) * 1000L;

        HttpResponse<String> profile = http.get(URI.create("https://api.minecraftservices.com/minecraft/profile"),
                Map.of("Authorization", "Bearer " + mcToken));
        if (profile.statusCode() == 404) throw new IOException("This Microsoft account does not own Minecraft: Java Edition");
        if (profile.statusCode() / 100 != 2) throw new IOException("Minecraft profile request failed: HTTP " + profile.statusCode());
        JsonNode p = Json.tree(profile.body());

        StoredAccount a = new StoredAccount();
        a.id = dashed(p.path("id").asText());
        a.name = p.path("name").asText();
        a.clientId = clientId;
        a.msRefreshToken = msRefreshToken;
        a.minecraftToken = mcToken;
        a.minecraftTokenExpiresAt = expires;
        a.xuid = xuid;
        return a;
    }

    static String dashed(String id) {
        if (id.length() != 32) return id;
        return id.substring(0, 8) + "-" + id.substring(8, 12) + "-" + id.substring(12, 16) + "-" + id.substring(16, 20) + "-" + id.substring(20);
    }

    private JsonNode postForm(String url, Map<String, String> form, boolean failOnError) throws IOException {
        String body = form.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
        HttpResponse<String> r = http.post(URI.create(url), "application/x-www-form-urlencoded", body, Map.of());
        JsonNode n = r.body() == null || r.body().isBlank() ? Json.MAPPER.createObjectNode() : Json.tree(r.body());
        if (failOnError && r.statusCode() / 100 != 2) {
            throw new IOException("Microsoft sign-in request failed: HTTP " + r.statusCode() + " " + n.path("error").asText("")
                    + " " + n.path("error_description").asText(""));
        }
        return n;
    }

    private JsonNode postJson(String url, JsonNode body, Map<String, String> headers) throws IOException {
        HttpResponse<String> r = http.post(URI.create(url), "application/json", Json.MAPPER.writeValueAsString(body), headers);
        JsonNode n = r.body() == null || r.body().isBlank() ? Json.MAPPER.createObjectNode() : Json.tree(r.body());
        if (r.statusCode() / 100 != 2 && !n.has("XErr")) throw new IOException("Request to " + url + " failed: HTTP " + r.statusCode());
        return n;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
