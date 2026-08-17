package com.slash.agent;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class WavAudioTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void readsAndResamplesPcm16MonoTo24k() throws Exception {
        int inputRate = 16_000;
        int frames = 1_600;
        ByteBuffer wav = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN);
        wav.putInt(0x46464952).putInt(36 + frames * 2).putInt(0x45564157);
        wav.putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) 1);
        wav.putInt(inputRate).putInt(inputRate * 2).putShort((short) 2).putShort((short) 16);
        wav.putInt(0x61746164).putInt(frames * 2);
        for (int index = 0; index < frames; index++) {
            wav.putShort((short) Math.round(Math.sin(index * 2 * Math.PI / 100) * 12_000));
        }
        File file = temporary.newFile("speaker.wav");
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(wav.array()); }

        float[] audio = WavAudio.read24kMono(file);
        assertEquals(2_400, audio.length);
        assertTrue(audio[38] > 0.2f);
        assertTrue(audio[38] < 0.5f);
    }
}
