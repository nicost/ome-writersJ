package org.micromanager.omewriters;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zarr.zarrjava.store.FilesystemStore;
import dev.zarr.zarrjava.v3.Array;
import dev.zarr.zarrjava.v3.ArrayMetadata;
import dev.zarr.zarrjava.v3.DataType;
import dev.zarr.zarrjava.v3.codec.CodecBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * OME-Zarr v0.5 backend using zarr-java.
 *
 * <p>Group zarr.json files are written with Jackson (no zarr-java Group API),
 * which avoids the root-storeHandle ambiguity in zarr-java 0.0.4. Arrays are
 * created and written via zarr-java's {@code Array.create()} / {@code array.write()}.
 *
 * <p>Three layout modes:
 * <ul>
 *   <li><b>Single-position</b> — multiscales at root; array at "0"</li>
 *   <li><b>Multi-position (bf2raw)</b> — root has {@code bioformats2raw.layout=3};
 *       each position is a sub-directory with multiscales; array at
 *       "&lt;posname&gt;/0"</li>
 *   <li><b>Plate</b> — NGFF plate hierarchy at root; image groups at
 *       "&lt;row&gt;/&lt;col&gt;/&lt;name&gt;"</li>
 * </ul>
 */
public class OmeZarrBackend implements ArrayBackend {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AcquisitionSettings settings;
    /** One zarr-java Array per position, in acquisition order. */
    private List<Array> arrays;
    /** Image group path relative to root, one per position ("." for single-position). */
    private List<String> groupPaths;
    private int height;
    private int width;
    private DataType zarrDataType;
    private ucar.ma2.DataType ma2DataType;

    // -------------------------------------------------------------------------
    // prepare
    // -------------------------------------------------------------------------

    @Override
    public void prepare(AcquisitionSettings settings) throws Exception {
        this.settings   = settings;
        this.arrays     = new ArrayList<>();
        this.groupPaths = new ArrayList<>();

        Path outputPath = Paths.get(settings.getOutputPath()).toAbsolutePath();
        if (settings.isOverwrite()) {
            deleteRecursively(outputPath);
        }
        Files.createDirectories(outputPath);

        zarrDataType = DataType.valueOf(settings.getDtype().toUpperCase());
        ma2DataType  = zarrDataType.getMA2DataType();

        List<Dimension> storageDims = settings.getArrayStorageDimensions();
        List<Dimension> frameDims   = settings.getFrameDimensions();
        height = frameDims.get(0).getCount();
        width  = frameDims.get(1).getCount();

        long[] initialShape = buildInitialShape(storageDims);
        int[]  chunkShape   = buildChunkShape(storageDims);
        int[]  shardShape   = buildShardShape(storageDims, chunkShape);
        ArrayMetadata arrayMeta = buildArrayMetadata(initialShape, chunkShape, shardShape);
        Map<String, Object> imageAttrs = NgffMetadataBuilder.buildImageAttributes(
                storageDims, settings.getDtype());

        List<Position> positions = settings.getPositions();

        if (settings.getPlate() != null) {
            preparePlate(outputPath, positions, imageAttrs, arrayMeta);
        } else if (positions.size() == 1) {
            prepareSingle(outputPath, imageAttrs, arrayMeta);
        } else {
            prepareMulti(outputPath, positions, imageAttrs, arrayMeta);
        }
    }

    private void prepareSingle(Path outputPath,
            Map<String, Object> imageAttrs, ArrayMetadata arrayMeta) throws Exception {
        writeGroupZarrJson(outputPath, imageAttrs);
        arrays.add(createArray(outputPath.resolve("0"), arrayMeta));
        groupPaths.add(".");
    }

    private void prepareMulti(Path outputPath, List<Position> positions,
            Map<String, Object> imageAttrs, ArrayMetadata arrayMeta) throws Exception {
        writeGroupZarrJson(outputPath, NgffMetadataBuilder.buildBf2RawRootAttributes());
        for (Position pos : positions) {
            String name = seriesName(pos);
            Path groupDir = outputPath.resolve(name);
            Files.createDirectories(groupDir);
            writeGroupZarrJson(groupDir, imageAttrs);
            arrays.add(createArray(groupDir.resolve("0"), arrayMeta));
            groupPaths.add(name);
        }
    }

    private void preparePlate(Path outputPath, List<Position> positions,
            Map<String, Object> imageAttrs, ArrayMetadata arrayMeta) throws Exception {
        Plate plate = settings.getPlate();
        writeGroupZarrJson(outputPath,
                NgffMetadataBuilder.buildPlateAttributes(plate, positions));

        Map<String, List<String>> wellImagePaths = new LinkedHashMap<>();

        for (Position pos : positions) {
            String row = pos.getPlateRow();
            String col = pos.getPlateColumn();
            if (row == null || col == null) {
                throw new IllegalArgumentException(
                        "Plate mode requires plateRow/plateColumn on every position. "
                        + "Position '" + pos.getName() + "' is missing one or both.");
            }
            String wellKey = row + "/" + col;
            String imgName = pos.getName();
            wellImagePaths.computeIfAbsent(wellKey, k -> new ArrayList<>()).add(imgName);

            Path rowDir  = outputPath.resolve(row);
            Path wellDir = rowDir.resolve(col);
            Path imgDir  = wellDir.resolve(imgName);
            Files.createDirectories(imgDir);

            // Row group: plain group (no special attrs)
            ensureGroupZarrJson(rowDir, null);
            // Well zarr.json written after we know all images (below)
            // Image group: multiscales
            writeGroupZarrJson(imgDir, imageAttrs);
            arrays.add(createArray(imgDir.resolve("0"), arrayMeta));
            groupPaths.add(wellKey + "/" + imgName);
        }

        // Write well zarr.json files once all images per well are known
        for (Map.Entry<String, List<String>> e : wellImagePaths.entrySet()) {
            String[] parts = e.getKey().split("/", 2);
            Path wellDir = outputPath.resolve(parts[0]).resolve(parts[1]);
            writeGroupZarrJson(wellDir,
                    NgffMetadataBuilder.buildWellAttributes(e.getValue()));
        }
    }

    // -------------------------------------------------------------------------
    // writeFrame / advance / close
    // -------------------------------------------------------------------------

    @Override
    public void writeFrame(int positionIndex, int[] storageIndex, Object pixelData)
            throws Exception {
        ensureSize(positionIndex, storageIndex);
        long[] offset = buildOffset(storageIndex);

        // Full-rank data shape: [1, 1, ..., height, width]
        int rank = storageIndex.length + 2;
        int[] dataShape = new int[rank];
        for (int i = 0; i < storageIndex.length; i++) dataShape[i] = 1;
        dataShape[rank - 2] = height;
        dataShape[rank - 1] = width;

        ucar.ma2.Array frame = ucar.ma2.Array.factory(ma2DataType, dataShape, pixelData);
        arrays.get(positionIndex).write(offset, frame);
    }

    @Override
    public void advance(List<int[]> frames) throws Exception {
        Map<Integer, int[]> maxByPos = new LinkedHashMap<>();
        for (int[] entry : frames) {
            int posIdx = entry[0];
            int[] storageIdx = Arrays.copyOfRange(entry, 1, entry.length);
            int[] cur = maxByPos.get(posIdx);
            if (cur == null) {
                maxByPos.put(posIdx, Arrays.copyOf(storageIdx, storageIdx.length));
            } else {
                for (int i = 0; i < storageIdx.length; i++) {
                    if (storageIdx[i] > cur[i]) cur[i] = storageIdx[i];
                }
            }
        }
        for (Map.Entry<Integer, int[]> e : maxByPos.entrySet()) {
            ensureSize(e.getKey(), e.getValue());
        }
    }

    @Override
    public void close() {
        arrays.clear();
    }

    // -------------------------------------------------------------------------
    // Resize
    // -------------------------------------------------------------------------

    private void ensureSize(int positionIndex, int[] storageIndex) throws Exception {
        Array array = arrays.get(positionIndex);
        long[] current = array.metadata.shape;
        boolean needsResize = false;
        for (int i = 0; i < storageIndex.length; i++) {
            if (storageIndex[i] >= current[i]) {
                needsResize = true;
                break;
            }
        }
        if (needsResize) {
            long[] newShape = Arrays.copyOf(current, current.length);
            for (int i = 0; i < storageIndex.length; i++) {
                if (storageIndex[i] >= newShape[i]) {
                    newShape[i] = storageIndex[i] + 1;
                }
            }
            arrays.set(positionIndex, array.resize(newShape));
        }
    }

    // -------------------------------------------------------------------------
    // Array metadata
    // -------------------------------------------------------------------------

    private ArrayMetadata buildArrayMetadata(long[] shape, int[] chunkShape, int[] shardShape)
            throws Exception {
        final Compression compression = settings.getCompression();
        final boolean hasShard = shardShape != null;
        final int[] finalShardShape = shardShape;

        Function<CodecBuilder, CodecBuilder> codecFn = hasShard
                ? c -> c.withSharding(finalShardShape, inner -> configureCodecs(inner, compression))
                : c -> configureCodecs(c, compression);

        return Array.metadataBuilder()
                .withShape(shape)
                .withDataType(zarrDataType)
                .withChunkShape(chunkShape)
                .withFillValue(0)
                .withCodecs(codecFn)
                .build();
    }

    private static CodecBuilder configureCodecs(CodecBuilder c, Compression compression) {
        if (compression == null || compression == Compression.NONE) return c;
        if (compression == Compression.ZSTD)       return c.withZstd(3);
        if (compression == Compression.BLOSC_ZSTD) return c.withBlosc("zstd", 3);
        if (compression == Compression.BLOSC_LZ4)  return c.withBlosc("lz4", 3);
        return c;
    }

    // -------------------------------------------------------------------------
    // Zarr group/array I/O
    // -------------------------------------------------------------------------

    /**
     * Create a zarr-java Array at the given directory path.
     * Uses a fresh FilesystemStore rooted at {@code arrayDir.getParent()} so
     * the array key resolves to {@code arrayDir.getFileName()}.
     */
    private static Array createArray(Path arrayDir, ArrayMetadata meta) throws Exception {
        Files.createDirectories(arrayDir);
        FilesystemStore store = new FilesystemStore(arrayDir.getParent());
        return Array.create(store.resolve(arrayDir.getFileName().toString()), meta);
    }

    /** Write (or overwrite) a zarr.json group file at {@code groupDir/zarr.json}. */
    private static void writeGroupZarrJson(Path groupDir,
            Map<String, Object> attributes) throws Exception {
        Files.createDirectories(groupDir);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("zarr_format", 3);
        doc.put("node_type", "group");
        if (attributes != null && !attributes.isEmpty()) {
            doc.put("attributes", attributes);
        } else {
            doc.put("attributes", new LinkedHashMap<>());
        }
        byte[] json = MAPPER.writeValueAsBytes(doc);
        Files.write(groupDir.resolve("zarr.json"), json);
    }

    /**
     * Write group zarr.json only if it does not already exist.
     * Used for row-level groups in the plate hierarchy.
     */
    private static void ensureGroupZarrJson(Path groupDir,
            Map<String, Object> attributes) throws Exception {
        if (!Files.exists(groupDir.resolve("zarr.json"))) {
            writeGroupZarrJson(groupDir, attributes);
        }
    }

    // -------------------------------------------------------------------------
    // Shape helpers
    // -------------------------------------------------------------------------

    private static long[] buildInitialShape(List<Dimension> dims) {
        long[] shape = new long[dims.size()];
        for (int i = 0; i < dims.size(); i++) {
            Integer count = dims.get(i).getCount();
            shape[i] = count != null ? (long) count : 1L;
        }
        return shape;
    }

    private static int[] buildChunkShape(List<Dimension> dims) {
        int n = dims.size();
        int[] chunk = new int[n];
        for (int i = 0; i < n; i++) {
            Dimension d = dims.get(i);
            if (d.getChunkSize() != null) {
                chunk[i] = d.getChunkSize();
            } else if (i >= n - 2) {
                chunk[i] = d.getCount() != null ? d.getCount() : 256;
            } else {
                chunk[i] = 1;
            }
        }
        return chunk;
    }

    private static int[] buildShardShape(List<Dimension> dims, int[] chunkShape) {
        int n = dims.size();
        boolean hasShard = false;
        int[] shard = new int[n];
        for (int i = 0; i < n; i++) {
            Integer ssc = dims.get(i).getShardSizeChunks();
            if (ssc != null) {
                shard[i] = chunkShape[i] * ssc;
                hasShard = true;
            } else {
                shard[i] = chunkShape[i];
            }
        }
        return hasShard ? shard : null;
    }

    // -------------------------------------------------------------------------
    // Misc helpers
    // -------------------------------------------------------------------------

    private static long[] buildOffset(int[] storageIndex) {
        long[] offset = new long[storageIndex.length + 2];
        for (int i = 0; i < storageIndex.length; i++) {
            offset[i] = storageIndex[i];
        }
        return offset;
    }

    private static String seriesName(Position pos) {
        if (pos.getGridRow() != null && pos.getGridColumn() != null) {
            return pos.getName() + "_" + pos.getGridRow() + "_" + pos.getGridColumn();
        }
        return pos.getName();
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (!Files.exists(path)) return;
        Files.walk(path)
             .sorted(java.util.Comparator.reverseOrder())
             .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
    }
}
