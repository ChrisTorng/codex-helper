package com.christorng.codextrigger;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

final class CodexClient {
    static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    static final String AUTH_URL = "https://auth.openai.com/oauth/authorize";
    static final String TOKEN_URL = "https://auth.openai.com/oauth/token";
    static final String USAGE_URL = "https://chatgpt.com/backend-api/wham/usage";
    static final String RESPONSES_URL = "https://chatgpt.com/backend-api/codex/responses";
    static final String SCOPE = "openid profile email offline_access api.connectors.read api.connectors.invoke";

    private final SecureStore store;

    CodexClient(Context context) {
        store = new SecureStore(context);
    }

    static final class Pkce {
        final String verifier;
        final String challenge;
        final String state;
        Pkce(String verifier, String challenge, String state) {
            this.verifier = verifier;
            this.challenge = challenge;
            this.state = state;
        }
    }

    static final class Window {
        final double usedPercent;
        final long resetAt;
        Window(double usedPercent, long resetAt) {
            this.usedPercent = usedPercent;
            this.resetAt = resetAt;
        }
    }

    static final class Quota {
        final String plan;
        final boolean allowed;
        final boolean limitReached;
        final Window primary;
        final Window secondary;
        Quota(String plan, boolean allowed, boolean limitReached, Window primary, Window secondary) {
            this.plan = plan;
            this.allowed = allowed;
            this.limitReached = limitReached;
            this.primary = primary;
            this.secondary = secondary;
        }

        String summary() {
            String p = primary == null ? "5h: inactive" :
                    String.format(Locale.US, "5h used: %.0f%%, reset: %d", primary.usedPercent, primary.resetAt);
            String s = secondary == null ? "Weekly: inactive" :
                    String.format(Locale.US, "Weekly used: %.0f%%, reset: %d", secondary.usedPercent, secondary.resetAt);
            return "Plan: " + plan + "\n" + p + "\n" + s + "\nAllowed: " + allowed;
        }
    }

    static Pkce newPkce() throws Exception {
        SecureRandom random = new SecureRandom();
        byte[] verifierBytes = new byte[48];
        random.nextBytes(verifierBytes);
        String verifier = Base64.encodeToString(verifierBytes,
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        String challenge = Base64.encodeToString(md.digest(verifier.getBytes(StandardCharsets.US_ASCII)),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        byte[] stateBytes = new byte[24];
        random.nextBytes(stateBytes);
        String state = Base64.encodeToString(stateBytes,
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        return new Pkce(verifier, challenge, state);
    }

    static String authorizeUrl(Pkce pkce, String redirectUri) throws Exception {
        return AUTH_URL +
                "?response_type=code" +
                "&client_id=" + enc(CLIENT_ID) +
                "&redirect_uri=" + enc(redirectUri) +
                "&scope=" + enc(SCOPE) +
                "&code_challenge=" + enc(pkce.challenge) +
                "&code_challenge_method=S256" +
                "&state=" + enc(pkce.state) +
                "&id_token_add_organizations=true" +
                "&codex_cli_simplified_flow=true" +
                "&originator=codex_quota_trigger_android";
    }

    void exchangeCode(String code, String verifier, String redirectUri) throws Exception {
        String body = "grant_type=authorization_code" +
                "&client_id=" + enc(CLIENT_ID) +
                "&code=" + enc(code) +
                "&redirect_uri=" + enc(redirectUri) +
                "&code_verifier=" + enc(verifier);
        JSONObject json = new JSONObject(httpForm(TOKEN_URL, body));
        saveTokens(json);
    }

    synchronized String validAccessToken() throws Exception {
        String access = store.get("access_token");
        String expiryText = store.get("expires_at_ms");
        long expiry = expiryText == null ? 0 : Long.parseLong(expiryText);
        if (access != null && System.currentTimeMillis() + 5 * 60_000L < expiry) return access;
        refresh();
        access = store.get("access_token");
        if (access == null) throw new IllegalStateException("Not signed in");
        return access;
    }

    synchronized void refresh() throws Exception {
        String refresh = store.get("refresh_token");
        if (refresh == null) throw new IllegalStateException("No refresh token; sign in again");
        String body = "grant_type=refresh_token" +
                "&client_id=" + enc(CLIENT_ID) +
                "&refresh_token=" + enc(refresh);
        JSONObject json = new JSONObject(httpForm(TOKEN_URL, body));
        saveTokens(json);
    }

    Quota getQuota() throws Exception {
        String access = validAccessToken();
        String account = accountId();
        HttpURLConnection c = (HttpURLConnection) new URL(USAGE_URL).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(20_000);
        c.setReadTimeout(20_000);
        authHeaders(c, access, account);
        String text = readResponse(c);
        JSONObject root = new JSONObject(text);
        JSONObject rate = root.optJSONObject("rate_limit");
        boolean allowed = rate == null || rate.optBoolean("allowed", true);
        boolean reached = rate != null && rate.optBoolean("limit_reached", false);
        Window primary = parseWindow(rate == null ? null : rate.optJSONObject("primary_window"));
        Window secondary = parseWindow(rate == null ? null : rate.optJSONObject("secondary_window"));
        return new Quota(root.optString("plan_type", "unknown"), allowed, reached, primary, secondary);
    }

    int triggerMinimal() throws Exception {
        String access = validAccessToken();
        String account = accountId();

        JSONObject root = new JSONObject();
        root.put("model", "gpt-5.6-luna");
        root.put("store", false);
        root.put("stream", true);
        root.put("instructions", "Reply .");

        JSONObject textContent = new JSONObject();
        textContent.put("type", "input_text");
        textContent.put("text", ".");
        JSONArray content = new JSONArray();
        content.put(textContent);
        JSONObject inputItem = new JSONObject();
        inputItem.put("role", "user");
        inputItem.put("content", content);
        JSONArray input = new JSONArray();
        input.put(inputItem);
        root.put("input", input);

        JSONObject reasoning = new JSONObject();
        reasoning.put("effort", "none");
        root.put("reasoning", reasoning);

        JSONObject text = new JSONObject();
        text.put("verbosity", "low");
        root.put("text", text);

        byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new URL(RESPONSES_URL).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(20_000);
        c.setReadTimeout(60_000);
        authHeaders(c, access, account);
        c.setRequestProperty("originator", "codex-quota-trigger-android");
        c.setRequestProperty("OpenAI-Beta", "responses=experimental");
        c.setRequestProperty("Accept", "text/event-stream");
        c.setRequestProperty("Content-Type", "application/json");
        c.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream os = c.getOutputStream()) {
            os.write(bytes);
        }
        int status = c.getResponseCode();
        readAll(status >= 400 ? c.getErrorStream() : c.getInputStream());
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("Trigger HTTP " + status);
        }
        return status;
    }

    String accountId() {
        return store.get("account_id");
    }

    boolean signedIn() {
        return store.get("refresh_token") != null && store.get("account_id") != null;
    }

    void signOut() {
        store.clear();
    }

    private void saveTokens(JSONObject json) throws Exception {
        String access = json.optString("access_token", null);
        String refresh = json.optString("refresh_token", null);
        String id = json.optString("id_token", null);
        long expiresIn = json.optLong("expires_in", 3600);

        if (access != null) store.put("access_token", access);
        if (refresh != null) store.put("refresh_token", refresh);
        if (id != null) {
            store.put("id_token", id);
            String account = extractAccountId(id);
            if (account != null && !account.isEmpty()) store.put("account_id", account);
        }
        store.put("expires_at_ms", Long.toString(System.currentTimeMillis() + expiresIn * 1000L));
        if (store.get("account_id") == null) {
            throw new IllegalStateException("Token received but ChatGPT account id was not found");
        }
    }

    private static String extractAccountId(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            String payload = new String(Base64.decode(parts[1],
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(payload);
            JSONObject auth = root.optJSONObject("https://api.openai.com/auth");
            if (auth != null) {
                String id = auth.optString("chatgpt_account_id", null);
                if (id != null && !id.isEmpty()) return id;
            }
            String direct = root.optString("chatgpt_account_id", null);
            if (direct != null && !direct.isEmpty()) return direct;
        } catch (Exception ignored) {}
        return null;
    }

    private static Window parseWindow(JSONObject w) {
        if (w == null) return null;
        return new Window(w.optDouble("used_percent", 0), w.optLong("reset_at", 0));
    }

    private static void authHeaders(HttpURLConnection c, String access, String account) {
        c.setRequestProperty("Authorization", "Bearer " + access);
        c.setRequestProperty("ChatGPT-Account-Id", account);
        c.setRequestProperty("User-Agent", "codex-quota-trigger-android/0.1");
    }

    private static String httpForm(String url, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(20_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.setRequestProperty("Accept", "application/json");
        c.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream os = c.getOutputStream()) {
            os.write(bytes);
        }
        return readResponse(c);
    }

    private static String readResponse(HttpURLConnection c) throws Exception {
        int status = c.getResponseCode();
        String text = readAll(status >= 400 ? c.getErrorStream() : c.getInputStream());
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("HTTP " + status + ": " + text);
        }
        return text;
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }
}
