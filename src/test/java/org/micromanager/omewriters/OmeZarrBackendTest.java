package org.micromanager.omewriters;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zarr.zarrjava.store.FilesystemStore;
import dev.zarr.zarrjava.v3.Array;
import dev.zarr.zarrjava.v3.Group;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class OmeZarrBackendTest {

    @TempDir
    Path tmp;

    private static final int HEIGHT = 8;
    private static final int WIDTH  = 8;

    private AcquisitionSettings basicSettings(Path root) {
        return AcquisitionSettings.builder()
                .rootPath(root.toString())
                .dtype("uint16")
                .dimensions(List.of(
                        Dimension.builder("t").count(3).type(DimensionType.TIME).unit("second").build(),
                        Dimension.builder("c").channelNames("DAPI", "GFP").build(),
                        Dimension.builder("y").count(HEIGHT).type(DimensionType.SPACE).unit("micrometer").scale(0.1).build(),
                        Dimension.builder("x").count(WIDTH).type(DimensionType.SPACE).unit("micrometer").scale(0.1).build()
                ))
                .overwrite(true)
                .build();
    }

    // -------------------------------------------------------------------------
    // Single-position
    // -------------------------------------------------------------------------

    @Test
    void singlePosition_zarrJsonExists() throws Exception {
        Path root = tmp.resolve("single.ome.zarr");
        OmeZarrBackend backend = new OmeZarrBackend();
        backend.prepare(basicSettings(root));
        backend.close();

        assertTrue(Files.exists(root.resolve("zarr.json")), "root zarr.json missing");
        assertTrue(Files.exists(root.resolve("0").resolve("zarr.json")), "array zarr.json missing");
    }

    @Test
    @SuppressWarnings("unchecked")
    void singlePosition_multiscalesInRoot() throws Exception {
        Path root = tmp.resolve("meta.ome.zarr");
        OmeZarrBackend backend = new OmeZarrBackend();
        backend.prepare(basicSettings(root));
        backend.close();

        ObjectMapper mapper = new ObjectMapper();
        Map<?, ?> doc = mapper.readValue(root.resolve("zarr.json").toFile(), Map.class);
        Map<?, ?> attrs = (Map<?, ?>) doc.get("attributes");
        assertNotNull(attrs, "attributes missing from zarr.json");
        assertTrue(attrs.containsKey("multiscales"), "multiscales missing");

        List<?> ms = (List<?>) attrs.get("multiscales");
        Map<?, ?> first = (Map<?, ?>) ms.get(0);
        assertEquals("0.5", first.get("version"));

        List<?> axes = (List<?>) first.get("axes");
        assertEquals(4, axes.size());
        assertEquals("t", ((Map<?, ?>) axes.get(0)).get("name"));
        assertEquals("time", ((Map<?, ?>) axes.get(0)).get("type"));

        // omero channels
        Map<?, ?> omero = (Map<?, ?>) attrs.get("omero");
        assertNotNull(omero, "omero missing");
        List<?> channels = (List<?>) omero.get("channels");
        assertEquals(2, channels.size());
        assertEquals("DAPI", ((Map<?, ?>) channels.get(0)).get("label"));
    }

    @Test
    void singlePosition_writeReadRoundTrip() throws Exception {
        Path root = tmp.resolve("rw.ome.zarr");
        AcquisitionSettings settings = basicSettings(root);

        OmeZarrBackend backend = new OmeZarrBackend();
        backend.prepare(settings);

        // Write one frame at t=0,c=0
        short[] frameData = new short[HEIGHT * WIDTH];
        for (int i = 0; i < frameData.length; i++) frameData[i] = (short) (i + 1);
        backend.writeFrame(0, new int[]{0, 0}, frameData);
        backend.close();

        // Read back via zarr-java
        FilesystemStore store = new FilesystemStore(root);
        Array array = Array.open(store.resolve("0"));
        ucar.ma2.Array read = array.read(new long[]{0, 0, 0, 0}, new int[]{1, 1, HEIGHT, WIDTH});
        assertNotNull(read);
        assertEquals(HEIGHT * WIDTH, (int) read.getSize());
        // First pixel should be 1
        assertEquals(1, read.getShort(0));
    }

    @Test
    void singlePosition_unboundedOuterDim_resizes() throws Exception {
        Path root = tmp.resolve("unbounded.ome.zarr");
        AcquisitionSettings settings = AcquisitionSettings.builder()
                .rootPath(root.toString())
                .dtype("uint16")
                .dimensions(List.of(
                        Dimension.builder("t").count(null).type(DimensionType.TIME).build(),
                        Dimension.builder("c").count(2).type(DimensionType.CHANNEL).build(),
                        Dimension.builder("y").count(HEIGHT).type(DimensionType.SPACE).build(),
                        Dimension.builder("x").count(WIDTH).type(DimensionType.SPACE).build()
                ))
                .overwrite(true)
                .build();

        OmeZarrBackend backend = new OmeZarrBackend();
        backend.prepare(settings);

        short[] frame = new short[HEIGHT * WIDTH];
        backend.writeFrame(0, new int[]{0, 0}, frame);  // t=0, c=0
        backend.writeFrame(0, new int[]{1, 1}, frame);  // t=1, c=1 — triggers resize

        // After writing t=1 the array must have been resized to at least t=2
        FilesystemStore store = new FilesystemStore(root);
        Array array = Array.open(store.resolve("0"));
        assertTrue(array.metadata.shape[0] >= 2, "array not resized along t");

        backend.close();
    }

    // -------------------------------------------------------------------------
    // Multi-position (bf2raw)
    // -------------------------------------------------------------------------

    @Test
    void multiPosition_bf2RawLayout() throws Exception {
        Path root = tmp.resolve("multi.ome.zarr");
        AcquisitionSettings settings = AcquisitionSettings.builder()
                .rootPath(root.toString())
                .dtype("uint16")
                .dimensions(List.of(
                        Dimension.builder("p").positionNames("pos0", "pos1").build(),
                        Dimension.builder("t").count(2).type(DimensionType.TIME).build(),
                        Dimension.builder("y").count(HEIGHT).type(DimensionType.SPACE).build(),
                        Dimension.builder("x").count(WIDTH).type(DimensionType.SPACE).build()
                ))
                .overwrite(true)
                .build();

        OmeZarrBackend backend = new OmeZarrBackend();
        backend.prepare(settings);

        short[] frame = new short[HEIGHT * WIDTH];
        backend.writeFrame(0, new int[]{0}, frame);  // pos0, t=0
        backend.writeFrame(1, new int[]{1}, frame);  // pos1, t=1
        backend.close();

        // root zarr.json should have bioformats2raw.layout
        ObjectMapper mapper = new ObjectMapper();
        Map<?, ?> doc = mapper.readValue(root.resolve("zarr.json").toFile(), Map.class);
        Map<?, ?> attrs = (Map<?, ?>) doc.get("attributes");
        assertEquals(3, attrs.get("bioformats2raw.layout"));

        // Each position should have its own zarr.json with multiscales
        assertTrue(Files.exists(root.resolve("pos0").resolve("zarr.json")));
        assertTrue(Files.exists(root.resolve("pos1").resolve("zarr.json")));

        Map<?, ?> pos0doc = mapper.readValue(
                root.resolve("pos0").resolve("zarr.json").toFile(), Map.class);
        Map<?, ?> pos0attrs = (Map<?, ?>) pos0doc.get("attributes");
        assertTrue(pos0attrs.containsKey("multiscales"), "pos0 missing multiscales");
    }

    // -------------------------------------------------------------------------
    // Plate layout
    // -------------------------------------------------------------------------

    @Test
    void plateLayout_hierarchyCreated() throws Exception {
        Path root = tmp.resolve("plate.ome.zarr");
        List<Dimension> dims = List.of(
                Dimension.builder("p").coords(List.of(
                        Position.builder("well0").plateRow("A").plateColumn("1").build(),
                        Position.builder("well1").plateRow("A").plateColumn("2").build()
                )).build(),
                Dimension.builder("t").count(1).type(DimensionType.TIME).build(),
                Dimension.builder("y").count(HEIGHT).type(DimensionType.SPACE).build(),
                Dimension.builder("x").count(WIDTH).type(DimensionType.SPACE).build()
        );
        AcquisitionSettings settings = AcquisitionSettings.builder()
                .rootPath(root.toString())
                .dtype("uint16")
                .dimensions(dims)
                .plate(Plate.fromStandardWells(96))
                .overwrite(true)
                .build();

        OmeZarrBackend backend = new OmeZarrBackend();
        backend.prepare(settings);
        backend.close();

        // Plate root zarr.json
        ObjectMapper mapper = new ObjectMapper();
        Map<?, ?> doc = mapper.readValue(root.resolve("zarr.json").toFile(), Map.class);
        Map<?, ?> attrs = (Map<?, ?>) doc.get("attributes");
        assertTrue(attrs.containsKey("plate"), "plate key missing from root zarr.json");

        // Well hierarchy
        assertTrue(Files.exists(root.resolve("A").resolve("1").resolve("zarr.json")),
                "well A/1 zarr.json missing");
        assertTrue(Files.exists(root.resolve("A").resolve("2").resolve("zarr.json")),
                "well A/2 zarr.json missing");

        // Image groups under wells
        assertTrue(Files.exists(root.resolve("A").resolve("1").resolve("well0").resolve("zarr.json")),
                "image group A/1/well0 zarr.json missing");
        assertTrue(Files.exists(root.resolve("A").resolve("1").resolve("well0").resolve("0").resolve("zarr.json")),
                "array A/1/well0/0 zarr.json missing");
    }
}
