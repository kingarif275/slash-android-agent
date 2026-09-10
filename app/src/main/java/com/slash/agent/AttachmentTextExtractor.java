package com.slash.agent;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Bounded, local-only text extraction for attachments supported by the text model. */
public final class AttachmentTextExtractor {
    public static final int MAX_PER_FILE_BYTES = 6 * 1024;
    public static final int MAX_TOTAL_BYTES = 12 * 1024;
    private static final Set<String> TEXT_EXTENSIONS = new HashSet<>(Arrays.asList(
            "txt", "md", "markdown", "csv", "tsv", "json", "xml", "yaml", "yml",
            "java", "kt", "kts", "c", "cc", "cpp", "h", "hpp", "py", "js", "jsx",
            "ts", "tsx", "html", "css", "scss", "sql", "sh", "ps1", "gradle", "properties"));

    private final ContentResolver resolver;

    public AttachmentTextExtractor(Context context) {
        resolver = context.getApplicationContext().getContentResolver();
    }

    public String extract(ChatAttachment attachment, int remainingBytes) {
        if (attachment == null) return "";
        String mime = attachment.mimeType.toLowerCase(Locale.US);
        if (mime.startsWith("image/")) {
            return "[Image attached. The active local model is text-only and cannot inspect image pixels.]";
        }
        if (!isText(mime, attachment.displayName)) {
            return "[Attachment type is not locally extractable by the active text model.]";
        }
        int limit = Math.max(0, Math.min(MAX_PER_FILE_BYTES, remainingBytes));
        if (limit == 0) return "[Attachment text omitted because the local context budget is full.]";
        try (InputStream input = resolver.openInputStream(Uri.parse(attachment.uri))) {
            if (input == null) return "[Attachment could not be opened.]";
            ByteArrayOutputStream output = new ByteArrayOutputStream(limit);
            byte[] buffer = new byte[1024];
            int total = 0;
            boolean truncated = false;
            while (total < limit) {
                int count = input.read(buffer, 0, Math.min(buffer.length, limit - total));
                if (count < 0) break;
                output.write(buffer, 0, count);
                total += count;
            }
            if (input.read() >= 0) truncated = true;
            String text = new String(output.toByteArray(), StandardCharsets.UTF_8)
                    .replace("\u0000", "").trim();
            if (text.isEmpty()) return "[Attachment contains no readable local text.]";
            return text + (truncated ? "\n[Attachment truncated to fit the local context budget.]" : "");
        } catch (Exception error) {
            return "[Attachment text could not be read locally.]";
        }
    }

    private boolean isText(String mime, String name) {
        if (mime.startsWith("text/") || mime.contains("json") || mime.contains("xml")
                || mime.contains("yaml") || mime.contains("javascript")) return true;
        String lower = name == null ? "" : name.toLowerCase(Locale.US);
        int dot = lower.lastIndexOf('.');
        return dot >= 0 && TEXT_EXTENSIONS.contains(lower.substring(dot + 1));
    }
}
