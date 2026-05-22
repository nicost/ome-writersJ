package org.micromanager.omewriters;

/** Supported compression algorithms. */
public enum Compression {
    BLOSC_ZSTD("blosc-zstd"),
    BLOSC_LZ4("blosc-lz4"),
    ZSTD("zstd"),
    LZW("lzw"),
    NONE("none");

    private final String id;

    Compression(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public static Compression fromId(String id) {
        for (Compression c : values()) {
            if (c.id.equalsIgnoreCase(id)) return c;
        }
        throw new IllegalArgumentException("Unknown compression: " + id);
    }

    @Override
    public String toString() {
        return id;
    }
}

