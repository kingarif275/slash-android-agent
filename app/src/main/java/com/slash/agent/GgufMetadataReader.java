package com.slash.agent;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/** Minimal bounded GGUF metadata reader used before a downloaded model can be activated. */
final class GgufMetadataReader {
    static final long Q4_K_M_FILE_TYPE = 15L;

    static final class Metadata {
        final String architecture;
        final long fileType;

        Metadata(String architecture, long fileType) {
            this.architecture = architecture;
            this.fileType = fileType;
        }
    }

    private GgufMetadataReader() { }

    static Metadata read(File file) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            byte[] magic = new byte[4];
            input.readFully(magic);
            if (!"GGUF".equals(new String(magic, StandardCharsets.US_ASCII))) {
                throw new IOException("Not a GGUF file");
            }
            long version = u32(input);
            if (version < 2 || version > 3) throw new IOException("Unsupported GGUF version " + version);
            u64(input); // tensor count
            long metadataCount = u64(input);
            if (metadataCount < 0 || metadataCount > 100_000) throw new IOException("Invalid GGUF metadata count");
            String architecture = "";
            long fileType = -1;
            for (long index = 0; index < metadataCount; index++) {
                String key = string(input);
                int type = (int) u32(input);
                if ("general.architecture".equals(key) && type == 8) architecture = string(input);
                else if ("general.file_type".equals(key) && (type == 4 || type == 10)) {
                    fileType = type == 4 ? u32(input) : u64(input);
                } else skipValue(input, type, 0);
            }
            return new Metadata(architecture, fileType);
        }
    }

    private static void skipValue(RandomAccessFile input, int type, int depth) throws IOException {
        if (depth > 4) throw new IOException("GGUF metadata nesting is too deep");
        switch (type) {
            case 0:
            case 1:
            case 7: skip(input, 1); return;
            case 2:
            case 3: skip(input, 2); return;
            case 4:
            case 5:
            case 6: skip(input, 4); return;
            case 8: string(input); return;
            case 9: {
                int itemType = (int) u32(input);
                long count = u64(input);
                if (count < 0 || count > 10_000_000) throw new IOException("Invalid GGUF array length");
                for (long index = 0; index < count; index++) skipValue(input, itemType, depth + 1);
                return;
            }
            case 10:
            case 11:
            case 12: skip(input, 8); return;
            default: throw new IOException("Unknown GGUF metadata type " + type);
        }
    }

    private static void skip(RandomAccessFile input, long bytes) throws IOException {
        long next = input.getFilePointer() + bytes;
        if (bytes < 0 || next < 0 || next > input.length()) throw new IOException("Truncated GGUF metadata");
        input.seek(next);
    }

    private static String string(RandomAccessFile input) throws IOException {
        long length = u64(input);
        if (length < 0 || length > 16L * 1024 * 1024 || length > input.length() - input.getFilePointer()) {
            throw new IOException("Invalid GGUF string length");
        }
        byte[] bytes = new byte[(int) length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static long u32(RandomAccessFile input) throws IOException {
        return ((long) input.readUnsignedByte())
                | ((long) input.readUnsignedByte() << 8)
                | ((long) input.readUnsignedByte() << 16)
                | ((long) input.readUnsignedByte() << 24);
    }

    private static long u64(RandomAccessFile input) throws IOException {
        long low = u32(input);
        long high = u32(input);
        if ((high & 0x80000000L) != 0) throw new IOException("GGUF value exceeds signed range");
        return low | (high << 32);
    }
}
