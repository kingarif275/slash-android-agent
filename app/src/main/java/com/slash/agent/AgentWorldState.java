package com.slash.agent;

import android.app.KeyguardManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.PowerManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.content.ComponentName;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.List;

/** Fresh fused world state from Accessibility, Android APIs, and optional vision. */
public final class AgentWorldState {
    private static final AtomicLong IDS = new AtomicLong();
    public final String id;
    public final long capturedAt;
    public final String accessibilityObservation;
    public final boolean accessibilityAvailable;
    public final boolean interactive;
    public final boolean keyguardLocked;
    public final boolean networkConnected;
    public final boolean visionAvailable;
    public final MediaState media;

    public static final class MediaState {
        public final boolean available;
        public final boolean playing;
        public final String packageName;
        public final String title;
        public final String artist;

        MediaState(boolean available, boolean playing, String packageName, String title,
                String artist) {
            this.available = available;
            this.playing = playing;
            this.packageName = packageName;
            this.title = title;
            this.artist = artist;
        }

        JSONObject toJson() {
            try {
                return new JSONObject().put("available", available).put("playing", playing)
                        .put("package", packageName).put("title", title).put("artist", artist);
            } catch (Exception ignored) { return new JSONObject(); }
        }
    }

    AgentWorldState(String id, long capturedAt, String observation,
            boolean accessibilityAvailable, boolean interactive, boolean keyguardLocked,
            boolean networkConnected, boolean visionAvailable, MediaState media) {
        this.id = id;
        this.capturedAt = capturedAt;
        this.accessibilityObservation = observation;
        this.accessibilityAvailable = accessibilityAvailable;
        this.interactive = interactive;
        this.keyguardLocked = keyguardLocked;
        this.networkConnected = networkConnected;
        this.visionAvailable = visionAvailable;
        this.media = media;
    }

    public static AgentWorldState capture(Context context) {
        String observation;
        FutureTask<String> read = new FutureTask<>(SlashAccessibilityService::readScreenSafe);
        Thread observer = new Thread(read, "slash-world-observer");
        observer.start();
        try {
            observation = read.get(1500, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            observer.interrupt();
            observation = "SCREEN_UNAVAILABLE";
        } catch (Exception unavailable) {
            observation = "SCREEN_UNAVAILABLE";
        }
        boolean accessibility = !observation.contains("ACCESSIBILITY_DISABLED")
                && !observation.contains("SCREEN_UNAVAILABLE");
        PowerManager power = context.getSystemService(PowerManager.class);
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        boolean connected = false;
        if (connectivity != null) {
            Network active = connectivity.getActiveNetwork();
            NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(active);
            connected = capabilities != null
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        }
        long now = System.currentTimeMillis();
        // MediaSession can represent remote/cast playback from another device. It is deliberately
        // excluded from agent reasoning; the active app's fresh accessibility state is authoritative.
        MediaState media = new MediaState(false, false, "", "", "");
        return new AgentWorldState("world-" + now + "-" + IDS.incrementAndGet(), now,
                observation, accessibility, power != null && power.isInteractive(),
                keyguard != null && keyguard.isKeyguardLocked(), connected, false, media);
    }

    private static MediaState captureMedia(Context context) {
        try {
            MediaSessionManager manager = context.getSystemService(MediaSessionManager.class);
            ComponentName listener = new ComponentName(context, SlashNotificationListener.class);
            List<MediaController> controllers = manager.getActiveSessions(listener);
            MediaController best = null;
            for (MediaController controller : controllers) {
                PlaybackState playback = controller.getPlaybackState();
                if (playback != null && playback.getState() == PlaybackState.STATE_PLAYING) {
                    best = controller;
                    break;
                }
                if (best == null) best = controller;
            }
            if (best == null) return new MediaState(true, false, "", "", "");
            PlaybackState playback = best.getPlaybackState();
            MediaMetadata metadata = best.getMetadata();
            return new MediaState(true,
                    playback != null && playback.getState() == PlaybackState.STATE_PLAYING,
                    best.getPackageName(), metadataValue(metadata, MediaMetadata.METADATA_KEY_TITLE),
                    metadataValue(metadata, MediaMetadata.METADATA_KEY_ARTIST));
        } catch (SecurityException denied) {
            return new MediaState(false, false, "", "", "");
        } catch (Exception unavailable) {
            return new MediaState(false, false, "", "", "");
        }
    }

    private static String metadataValue(MediaMetadata metadata, String key) {
        if (metadata == null) return "";
        String value = metadata.getString(key);
        return value == null ? "" : value;
    }

    public String foregroundPackage() {
        String marker = "PACKAGE=";
        int start = accessibilityObservation.indexOf(marker);
        if (start < 0) return "";
        start += marker.length();
        int end = accessibilityObservation.indexOf(' ', start);
        if (end < 0) end = accessibilityObservation.indexOf('\n', start);
        if (end < 0) end = accessibilityObservation.length();
        return accessibilityObservation.substring(start, end).trim();
    }

    public String plannerContext() {
        JSONObject android = new JSONObject();
        try {
            android.put("interactive", interactive).put("keyguardLocked", keyguardLocked)
                    .put("networkConnected", networkConnected)
                    .put("foregroundPackage", foregroundPackage());
        } catch (Exception ignored) { }
        return "WORLD_STATE_ID=" + id + " CAPTURED_AT=" + capturedAt
                + "\nANDROID_API_STATE=" + android
                + "\nVISION_STATE=" + (visionAvailable ? "AVAILABLE" : "UNAVAILABLE")
                + "\nACCESSIBILITY_STATE:\n" + accessibilityObservation;
    }

    public String debugSummary() {
        int elements = 0;
        int index = 0;
        while ((index = accessibilityObservation.indexOf("\ne", index)) >= 0) {
            elements++;
            index += 2;
        }
        return "id=" + id + " foreground=" + foregroundPackage()
                + " screen=" + (interactive ? "ON" : "OFF") + " keyguard=" + keyguardLocked
                + " network=" + (networkConnected ? "CONNECTED" : "DISCONNECTED")
                + " interactive_elements=" + elements;
    }

    public String debugFingerprint() {
        String stableAccessibility = accessibilityObservation.replaceFirst(
                "OBSERVATION_ID=[^ ]+ ", "OBSERVATION_ID=* ");
        return foregroundPackage() + "|" + interactive + "|" + keyguardLocked + "|"
                + stableAccessibility;
    }
}
