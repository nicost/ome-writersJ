package org.micromanager.omewriters;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zarr.zarrjava.store.FilesystemStore;
import dev.zarr.zarrjava.v3.Array;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class OMEStreamTest {

    @TempDir
    Path tmp;

    private static final int H = 4, W = 4;

    private static short[] frame(short value) {
        short[] f = new short[H * W];
        for (int i = 0; i < f.length; i++) f[i] = value;
        return f;
    }

    private AcquisitionSettings tcyx(Path root) {
        return AcquisitionSettings.builder()
                .rootPath(root.toString())
                .dtype("uint16")
                .dimensions(List.of(
                        Dimension.builder("t").count(3).type(DimensionType.TIME).build(),
                        Dimension.builder("c").channelNames("DAPI", "GFP").build(),
                        Dimension.builder("y").count(H).type(DimensionType.SPACE).build(),
                        Dimension.builder("x").count(W).type(DimensionType.SPACE).build()))
                .overwrite(true)
                .build();
    }

    // -------------------------------------------------------------------------
    // Basic open / close
    // -------------------------------------------------------------------------

    @Test
    void openAndClose_withResources() throws Exception {
        Path root = tmp.resolve("basic.ome.zarr");
        try (OMEStream stream = OMEStream.open(tcyx(root))) {
            assertFalse(stream.isClosed());
        }
        // After try-with-resources the stream is closed
        OMEStream stream = OMEStream.open(tcyx(tmp.resolve("basic2.ome.zarr")));
        assertFalse(stream.isClosed());
        stream.close();
        assertTrue(stream.isClosed());
    }

    @Test
    void closeTwice_isIdempotent() throws Exception {
        try (OMEStream stream = OMEStream.open(tcyx(tmp.resolve("idem.ome.zarr")))) {
            stream.close(); // first close (via explicit call)
        }                   // second close (via try-with-resources) — must not throw
    }

    @Test
    void appendAfterClose_throws() throws Exception {
        Path root = tmp.resolve("postclosed.ome.zarr");
        OMEStream stream = OMEStream.open(tcyx(root));
        stream.close();
        assertThrows(IllegalStateException.class, () -> stream.append(frame((short) 1)));
    }

    // -------------------------------------------------------------------------
    // append
    // -------------------------------------------------------------------------

    @Test
    void append_allFrames_producesCorrectZarrShape() throws Exception {
        Path root = tmp.resolve("append.ome.zarr");
        try (OMEStream stream = OMEStream.open(tcyx(root))) {
            for (int t = 0; t < 3; t++) {
                for (int c = 0; c < 2; c++) {
                    stream.append(frame((short) (t * 2 + c)));
                }
            }
        }
        FilesystemStore store = new FilesystemStore(root);
        Array arr = Array.open(store.resolve("0"));
        assertArrayEquals(new long[]{3, 2, H, W}, arr.metadata.shape);
    }

    @Test
    void append_readBackPixelValues() throws Exception {
        Path root = tmp.resolve("pixelcheck.ome.zarr");
        try (OMEStream stream = OMEStream.open(tcyx(root))) {
            for (int t = 0; t < 3; t++) {
                for (int c = 0; c < 2; c++) {
                    stream.append(frame((short) (t * 10 + c)));
                }
            }
        }
        FilesystemStore store = new FilesystemStore(root);
        Array arr = Array.open(store.resolve("0"));
        // t=2, c=1 frame should be all 21s
        ucar.ma2.Array data = arr.read(new long[]{2, 1, 0, 0}, new int[]{1, 1, H, W});
        assertEquals(21, data.getShort(0));
    }

    @Test
    void append_tooManyFrames_throws() throws Exception {
        Path root = tmp.resolve("overflow.ome.zarr");
        try (OMEStream stream = OMEStream.open(tcyx(root))) {
            for (int i = 0; i < 6; i++) stream.append(frame((short) 0)); // 3*2=6
            assertThrows(IndexOutOfBoundsException.class,
                    () -> stream.append(frame((short) 0)));
        }
    }

    // -------------------------------------------------------------------------
    // skip
    // -------------------------------------------------------------------------

    @Test
    void skip_oneFrame_backendReceivesAdvance() throws Exception {
        Path root = tmp.resolve("skip1.ome.zarr");
        try (OMEStream stream = OMEStream.open(tcyx(root))) {
            stream.append(frame((short) 42));  // t=0, c=0
            stream.skip();                      // t=0, c=1  (skipped)
            stream.append(frame((short) 99));  // t=1, c=0
            // remaining frames
            stream.skip(3);
        }
        FilesystemStore store = new FilesystemStore(root);
        Array arr = Array.open(store.resolve("0"));
        ucar.ma2.Array d = arr.read(new long[]{0, 0, 0, 0}, new int[]{1, 1, H, W});
        assertEquals(42, d.getShort(0));
        ucar.ma2.Array d2 = arr.read(new long[]{1, 0, 0, 0}, new int[]{1, 1, H, W});
        assertEquals(99, d2.getShort(0));
    }

    @Test
    void skip_negativeFrames_throws() throws Exception {
        try (OMEStream stream = OMEStream.open(tcyx(tmp.resolve("skipneg.ome.zarr")))) {
            assertThrows(IllegalArgumentException.class, () -> stream.skip(-1));
            assertThrows(IllegalArgumentException.class, () -> stream.skip(0));
        }
    }

    @Test
    void skip_tooManyFrames_throws() throws Exception {
        Path root = tmp.resolve("skipover.ome.zarr");
        try (OMEStream stream = OMEStream.open(tcyx(root))) {
            assertThrows(IndexOutOfBoundsException.class, () -> stream.skip(7));
        }
    }

    // -------------------------------------------------------------------------
    // Unbounded outer dimension
    // -------------------------------------------------------------------------

    @Test
    void unboundedStream_appendBeyondInitialShape() throws Exception {
        Path root = tmp.resolve("unbounded.ome.zarr");
        AcquisitionSettings settings = AcquisitionSettings.builder()
                .rootPath(root.toString())
                .dtype("uint16")
                .dimensions(List.of(
                        Dimension.builder("t").count(null).type(DimensionType.TIME).build(),
                        Dimension.builder("c").count(2).type(DimensionType.CHANNEL).build(),
                        Dimension.builder("y").count(H).type(DimensionType.SPACE).build(),
                        Dimension.builder("x").count(W).type(DimensionType.SPACE).build()))
                .overwrite(true)
                .build();

        try (OMEStream stream = OMEStream.open(settings)) {
            // Write 5 timepoints (10 frames total) to grow the array
            for (int i = 0; i < 10; i++) stream.append(frame((short) i));
        }

        FilesystemStore store = new FilesystemStore(root);
        Array arr = Array.open(store.resolve("0"));
        assertTrue(arr.metadata.shape[0] >= 5, "array should have been resized along t");
        assertEquals(2L, arr.metadata.shape[1]);
    }

    // -------------------------------------------------------------------------
    // Event system
    // -------------------------------------------------------------------------

    @Test
    void on_coordsExpanded_firesAtHighWaterMarks() throws Exception {
        Path root = tmp.resolve("events.ome.zarr");
        List<CoordUpdate> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1); // at least 1 event expected

        try (OMEStream stream = OMEStream.open(tcyx(root))) {
            stream.on("coords_expanded", update -> {
                received.add(update);
                latch.countDown();
            });
            for (int i = 0; i < 6; i++) stream.append(frame((short) i));
        }

        // Wait for async dispatch (generous timeout)
        assertTrue(latch.await(3, TimeUnit.SECONDS), "no coords_expanded event fired");
        assertFalse(received.isEmpty());
        // Every update that was a HWM is flagged
        for (CoordUpdate u : received) {
            assertTrue(u.isHighWaterMark());
        }
    }

    @Test
    void on_coordsChanged_firesOnEveryFrame() throws Exception {
        Path root = tmp.resolve("changed.ome.zarr");
        int totalFrames = 6; // 3t × 2c
        CountDownLatch latch = new CountDownLatch(totalFrames);
        List<CoordUpdate> received = new CopyOnWriteArrayList<>();

        try (OMEStream stream = OMEStream.open(tcyx(root))) {
            stream.on("coords_changed", update -> {
                received.add(update);
                latch.countDown();
            });
            for (int i = 0; i < totalFrames; i++) stream.append(frame((short) i));
        }

        assertTrue(latch.await(3, TimeUnit.SECONDS),
                "expected " + totalFrames + " events, got " + received.size());
        assertEquals(totalFrames, received.size());
    }

    @Test
    void on_unknownEvent_throws() throws Exception {
        try (OMEStream stream = OMEStream.open(tcyx(tmp.resolve("badEvent.ome.zarr")))) {
            assertThrows(IllegalArgumentException.class,
                    () -> stream.on("does_not_exist", u -> {}));
        }
    }

    // -------------------------------------------------------------------------
    // Multi-position layout via OMEStream
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void multiPosition_streamed_bf2RawLayout() throws Exception {
        Path root = tmp.resolve("mpstream.ome.zarr");
        AcquisitionSettings settings = AcquisitionSettings.builder()
                .rootPath(root.toString())
                .dtype("uint16")
                .dimensions(List.of(
                        Dimension.builder("p").positionNames("pos0", "pos1").build(),
                        Dimension.builder("t").count(2).type(DimensionType.TIME).build(),
                        Dimension.builder("y").count(H).type(DimensionType.SPACE).build(),
                        Dimension.builder("x").count(W).type(DimensionType.SPACE).build()))
                .overwrite(true)
                .build();

        try (OMEStream stream = OMEStream.open(settings)) {
            for (int i = 0; i < 4; i++) stream.append(frame((short) i));
        }

        ObjectMapper mapper = new ObjectMapper();
        Map<?, ?> rootDoc = mapper.readValue(root.resolve("zarr.json").toFile(), Map.class);
        Map<?, ?> rootAttrs = (Map<?, ?>) rootDoc.get("attributes");
        assertEquals(3, rootAttrs.get("bioformats2raw.layout"));
    }
}
