package com.slash.agent;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Optional, minimum-input network tools. Core reasoning and chat history stay local. */
public final class InternetToolExecutor {
    private static final int MAX_RESPONSE_BYTES = 192 * 1024;
    private static final Pattern SEARCH_RESULT = Pattern.compile(
            "(?s)<a[^>]*class=\\\"result__a\\\"[^>]*>(.*?)</a>.*?class=\\\"result__snippet\\\"[^>]*>(.*?)</(?:a|div)>");
    private final Context context;

    public InternetToolExecutor(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean supports(String tool) {
        return "NETWORK_STATUS".equals(tool) || "WEB_SEARCH".equals(tool) || "FETCH_URL".equals(tool);
    }

    public SlashToolExecutor.Result execute(String tool, org.json.JSONObject arguments) {
        try {
            switch (tool) {
                case "NETWORK_STATUS": return networkStatus();
                case "WEB_SEARCH": return webSearch(arguments.optString("query"));
                case "FETCH_URL": return fetchUrl(arguments.optString("url"));
                default: return new SlashToolExecutor.Result(false, "UNKNOWN_NETWORK_TOOL: " + tool);
            }
        } catch (Exception error) {
            return new SlashToolExecutor.Result(false,
                    "NETWORK_TOOL_FAILED: " + error.getClass().getSimpleName());
        }
    }

    private SlashToolExecutor.Result networkStatus() {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        Network active = manager == null ? null : manager.getActiveNetwork();
        NetworkCapabilities capabilities = active == null || manager == null
                ? null : manager.getNetworkCapabilities(active);
        boolean internet = capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        boolean validated = capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        String transport = capabilities == null ? "none"
                : capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "wifi"
                : capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "cellular"
                : capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "ethernet"
                : capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? "vpn" : "other";
        boolean metered = manager != null && manager.isActiveNetworkMetered();
        return new SlashToolExecutor.Result(true, "NETWORK_STATUS connected=" + validated
                + " internet_capability=" + internet + " transport=" + transport
                + " metered=" + metered);
    }

    private SlashToolExecutor.Result webSearch(String query) throws Exception {
        String clean = query == null ? "" : query.replaceAll("\\s+", " ").trim();
        if (clean.isEmpty()) return new SlashToolExecutor.Result(false, "WEB_SEARCH requires query");
        String endpoint = "https://html.duckduckgo.com/html/?q="
                + URLEncoder.encode(clean, StandardCharsets.UTF_8.name());
        String html = request(endpoint);
        Matcher matcher = SEARCH_RESULT.matcher(html);
        StringBuilder output = new StringBuilder("WEB_SEARCH query=").append(clean).append('\n');
        int count = 0;
        while (matcher.find() && count < 4) {
            String title = cleanHtml(matcher.group(1));
            String snippet = cleanHtml(matcher.group(2));
            if (title.isEmpty() && snippet.isEmpty()) continue;
            output.append(++count).append(". ").append(title);
            if (!snippet.isEmpty()) output.append(" — ").append(snippet);
            output.append('\n');
        }
        if (count == 0) return new SlashToolExecutor.Result(false, "WEB_SEARCH returned no readable results");
        return new SlashToolExecutor.Result(true, output.toString().trim());
    }

    private SlashToolExecutor.Result fetchUrl(String rawUrl) throws Exception {
        URI uri = new URI(rawUrl == null ? "" : rawUrl.trim());
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);
        if (!("https".equals(scheme) || "http".equals(scheme)) || uri.getHost() == null) {
            return new SlashToolExecutor.Result(false, "FETCH_URL requires an HTTP(S) URL");
        }
        if (isPrivateHost(uri.getHost())) {
            return new SlashToolExecutor.Result(false, "FETCH_URL blocks private or local network hosts");
        }
        String body = cleanHtml(request(uri.toString()));
        if (body.length() > 16_000) body = body.substring(0, 16_000) + "\n[Page truncated locally.]";
        return new SlashToolExecutor.Result(true, "FETCH_URL url=" + uri + "\n" + body);
    }

    private String request(String value) throws Exception {
        URI current = new URI(value);
        for (int redirects = 0; redirects <= 3; redirects++) {
            validatePublicUri(current);
            HttpURLConnection connection = (HttpURLConnection) current.toURL().openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "Slash-Android/0.2 (local assistant)");
            connection.setRequestProperty("Accept", "text/html,text/plain,application/json");
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || location.trim().isEmpty() || redirects == 3) {
                    throw new IllegalStateException("Unsafe or excessive redirect");
                }
                current = current.resolve(location.trim());
                continue;
            }
            if (status < 200 || status >= 300) {
                connection.disconnect();
                throw new IllegalStateException("HTTP " + status);
            }
            return readBounded(connection);
        }
        throw new IllegalStateException("Too many redirects");
    }

    private String readBounded(HttpURLConnection connection) throws Exception {
        try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int count;
            while (total < MAX_RESPONSE_BYTES
                    && (count = input.read(buffer, 0, Math.min(buffer.length, MAX_RESPONSE_BYTES - total))) >= 0) {
                output.write(buffer, 0, count);
                total += count;
            }
            return output.toString(StandardCharsets.UTF_8.name());
        } finally {
            connection.disconnect();
        }
    }

    private void validatePublicUri(URI uri) throws Exception {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);
        String host = uri.getHost();
        if (!("https".equals(scheme) || "http".equals(scheme)) || host == null
                || isPrivateHost(host)) {
            throw new IllegalArgumentException("Only public HTTP(S) destinations are allowed");
        }
        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
                throw new IllegalArgumentException("Private destination blocked");
            }
        }
    }

    private boolean isPrivateHost(String host) {
        String value = host.toLowerCase(Locale.US);
        if (value.equals("localhost") || value.equals("::1") || value.endsWith(".local")
                || value.startsWith("127.") || value.startsWith("10.") || value.startsWith("192.168.")
                || value.startsWith("169.254.") || value.startsWith("0.")) return true;
        if (!value.startsWith("172.")) return false;
        String[] parts = value.split("\\.");
        if (parts.length < 2) return false;
        try {
            int second = Integer.parseInt(parts[1]);
            return second >= 16 && second <= 31;
        } catch (NumberFormatException ignored) { return false; }
    }

    private String cleanHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#x27;", "'").replace("&lt;", "<").replace("&gt;", ">")
                .replaceAll("\\s+", " ").trim();
    }
}
