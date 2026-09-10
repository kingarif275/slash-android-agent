package com.slash.agent;

import android.content.Context;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Slash-owned localhost bridge for diagnostics and deterministic capability calls. */
public final class SlashControlBridge {
    private final Context context;
    private final SlashToolExecutor executor;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private volatile ServerSocket server;
    private volatile String token;

    public SlashControlBridge(Context context) {
        this.context = context.getApplicationContext();
        this.executor = new SlashToolExecutor(this.context);
    }

    public synchronized void start() {
        if (server != null) return;
        try {
            token = makeToken();
            ServerSocket socket = new ServerSocket(0, 8, java.net.InetAddress.getLoopbackAddress());
            server = socket;
            workers.execute(() -> acceptLoop(socket));
        } catch (Exception ignored) { server = null; }
    }

    public synchronized void stop() {
        ServerSocket socket = server;
        server = null;
        if (socket != null) try { socket.close(); } catch (Exception ignored) { }
        workers.shutdownNow();
    }

    public int port() { return server == null ? -1 : server.getLocalPort(); }
    public String token() { return token; }

    private void acceptLoop(ServerSocket socket) {
        while (server == socket) try {
            Socket client = socket.accept();
            workers.execute(() -> serve(client));
        } catch (Exception ignored) { if (server != socket) return; }
    }

    private void serve(Socket client) {
        try (Socket socket = client;
             BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             OutputStream output = socket.getOutputStream()) {
            String request = input.readLine();
            if (request == null) return;
            String[] parts = request.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String target = parts.length > 1 ? parts[1] : "/";
            int length = 0;
            String authorization = null;
            String line;
            while (!(line = input.readLine()).isEmpty()) {
                if (line.toLowerCase(Locale.US).startsWith("content-length:"))
                    length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                if (line.toLowerCase(Locale.US).startsWith("authorization:"))
                    authorization = line.substring(line.indexOf(':') + 1).trim();
            }
            char[] body = new char[Math.max(0, Math.min(length, 32_768))];
            if (body.length > 0) input.read(body);
            // Only loopback clients and the current per-launch bearer token are accepted.
            if (token == null || authorization == null || !authorization.equals("Bearer " + token)) {
                write(output, 401, new JSONObject().put("ok", false).put("code", "UNAUTHORIZED"));
                return;
            }
            JSONObject result = route(method, target, new String(body));
            write(output, 200, result);
        } catch (Exception ignored) { }
    }

    private JSONObject route(String method, String target, String body) throws Exception {
        String path = target.split("\\?", 2)[0];
        if ("GET".equals(method) && "/v1/ping".equals(path))
            return new JSONObject().put("ok", true).put("service", "slash-control").put("version", 1).put("port", port());
        if ("GET".equals(method) && "/v1/screen".equals(path))
            return new JSONObject().put("ok", true).put("observation", SlashAccessibilityService.observeScreenSafe());
        if ("POST".equals(method) && "/v1/action".equals(path)) {
            JSONObject action = body.trim().isEmpty() ? new JSONObject() : new JSONObject(body);
            String tool = action.optString("tool");
            JSONObject args = action.optJSONObject("arguments");
            SlashToolExecutor.Result result = executor.execute(tool, args == null ? new JSONObject() : args);
            return new JSONObject().put("ok", result.success).put("result", result.result).put("tool", tool);
        }
        if ("GET".equals(method) && "/v1/wait".equals(path)) {
            long until = SystemClock.elapsedRealtime() + 1_500;
            long before = SlashAccessibilityService.eventToken();
            while (SystemClock.elapsedRealtime() < until && before == SlashAccessibilityService.eventToken())
                SystemClock.sleep(40);
            return new JSONObject().put("ok", true).put("changed", before != SlashAccessibilityService.eventToken())
                    .put("observation", SlashAccessibilityService.observeScreenSafe());
        }
        return new JSONObject().put("ok", false).put("code", "NOT_FOUND");
    }

    private static void write(OutputStream output, int status, JSONObject body) throws Exception {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        String response = "HTTP/1.1 " + status + " OK\r\nContent-Type: application/json\r\nContent-Length: "
                + bytes.length + "\r\nConnection: close\r\n\r\n";
        output.write(response.getBytes(StandardCharsets.UTF_8)); output.write(bytes); output.flush();
    }

    private static String makeToken() {
        byte[] bytes = new byte[18]; new SecureRandom().nextBytes(bytes);
        StringBuilder value = new StringBuilder();
        for (byte item : bytes) value.append(String.format(Locale.US, "%02x", item));
        return value.toString();
    }
}
