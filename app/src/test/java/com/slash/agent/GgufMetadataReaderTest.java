package com.slash.agent;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;

public final class GgufMetadataReaderTest {
    @Test public void readsQwen3Q4KmMetadata() throws Exception {
        File file = File.createTempFile("slash-model", ".gguf");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write("GGUF".getBytes(StandardCharsets.US_ASCII));
            u32(output, 3);
            u64(output, 0);
            u64(output, 2);
            string(output, "general.architecture");
            u32(output, 8);
            string(output, "qwen3");
            string(output, "general.file_type");
            u32(output, 4);
            u32(output, 15);
        }
        GgufMetadataReader.Metadata metadata = GgufMetadataReader.read(file);
        assertEquals("qwen3", metadata.architecture);
        assertEquals(GgufMetadataReader.Q4_K_M_FILE_TYPE, metadata.fileType);
        file.delete();
    }

    private static void string(FileOutputStream output, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        u64(output, bytes.length);
        output.write(bytes);
    }

    private static void u32(FileOutputStream output, long value) throws Exception {
        for (int index = 0; index < 4; index++) output.write((int) (value >>> (index * 8)) & 0xff);
    }

    private static void u64(FileOutputStream output, long value) throws Exception {
        for (int index = 0; index < 8; index++) output.write((int) (value >>> (index * 8)) & 0xff);
    }
}
