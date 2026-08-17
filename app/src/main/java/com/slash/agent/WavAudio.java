package com.slash.agent;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Minimal PCM WAV reader and linear 24 kHz mono resampler for speaker conditioning. */
final class WavAudio {
    private static final int TARGET_RATE = 24_000;

    static float[] read24kMono(File file) throws Exception {
        byte[] bytes;
        try (FileInputStream input = new FileInputStream(file)) {
            bytes = new byte[(int) file.length()];
            int offset = 0;
            while (offset < bytes.length) {
                int read = input.read(bytes, offset, bytes.length - offset);
                if (read < 0) break;
                offset += read;
            }
        }
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.length < 44 || data.getInt(0) != 0x46464952 || data.getInt(8) != 0x45564157) {
            throw new IllegalArgumentException("Speaker reference must be a WAV file");
        }
        int channels = 0, sampleRate = 0, bits = 0, format = 0, pcmOffset = -1, pcmLength = 0;
        int cursor = 12;
        while (cursor + 8 <= bytes.length) {
            int id = data.getInt(cursor);
            int size = data.getInt(cursor + 4);
            int body = cursor + 8;
            if (body + size > bytes.length) break;
            if (id == 0x20746d66 && size >= 16) {
                format = data.getShort(body) & 0xffff;
                channels = data.getShort(body + 2) & 0xffff;
                sampleRate = data.getInt(body + 4);
                bits = data.getShort(body + 14) & 0xffff;
            } else if (id == 0x61746164) {
                pcmOffset = body;
                pcmLength = size;
                break;
            }
            cursor = body + size + (size & 1);
        }
        if (format != 1 || bits != 16 || channels < 1 || channels > 2 || sampleRate < 8_000 || pcmOffset < 0) {
            throw new IllegalArgumentException("Speaker reference must be 16-bit PCM mono/stereo WAV");
        }
        int frames = pcmLength / (channels * 2);
        float[] mono = new float[frames];
        for (int frame = 0; frame < frames; frame++) {
            float sum = 0;
            for (int channel = 0; channel < channels; channel++) {
                sum += data.getShort(pcmOffset + (frame * channels + channel) * 2) / 32768f;
            }
            mono[frame] = sum / channels;
        }
        if (sampleRate == TARGET_RATE) return mono;
        int targetFrames = Math.max(1, (int) Math.round(frames * (TARGET_RATE / (double) sampleRate)));
        float[] output = new float[targetFrames];
        double scale = sampleRate / (double) TARGET_RATE;
        for (int index = 0; index < targetFrames; index++) {
            double source = index * scale;
            int left = Math.min(frames - 1, (int) source);
            int right = Math.min(frames - 1, left + 1);
            float fraction = (float) (source - left);
            output[index] = mono[left] + (mono[right] - mono[left]) * fraction;
        }
        return output;
    }
}
