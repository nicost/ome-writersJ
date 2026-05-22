package org.micromanager.omewriters;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Top-level acquisition settings: defines what to write and how.
 *
 * <p>Build with {@link #builder()} and pass to stream creation. Validation
 * runs at {@link Builder#build()}.
 *
 * <pre>{@code
 * AcquisitionSettings settings = AcquisitionSettings.builder()
 *     .rootPath("data.ome.zarr")
 *     .dimensions(List.of(
 *         Dimension.builder("t").count(100).type(DimensionType.TIME).unit("second").build(),
 *         Dimension.builder("c").channelNames("DAPI", "GFP").build(),
 *         Dimension.builder("y").count(512).type(DimensionType.SPACE).unit("micrometer").scale(0.1).build(),
 *         Dimension.builder("x").count(512).type(DimensionType.SPACE).unit("micrometer").scale(0.1).build()
 *     ))
 *     .dtype("uint16")
 *     .compression(Compression.BLOSC_ZSTD)
 *     .build();
 * }</pre>
 */
public final class AcquisitionSettings {

    private final String rootPath;
    private final List<Dimension> dimensions;
    private final String dtype;
    private final OmeZarrFormat format;
    private final Compression compression;
    private final Object storageOrder; // String ("ome"|"acquisition") or List<String>
    private final Plate plate;
    private final boolean overwrite;

    private AcquisitionSettings(Builder b) {
        this.rootPath     = b.rootPath;
        this.dimensions   = List.copyOf(b.dimensions);
        this.dtype        = b.dtype;
        this.format       = b.format;
        this.compression  = b.compression;
        this.storageOrder = b.storageOrder;
        this.plate        = b.plate;
        this.overwrite    = b.overwrite;
    }

    // -------------------------------------------------------------------------
    // Basic getters
    // -------------------------------------------------------------------------

    public String getRootPath()           { return rootPath; }
    public List<Dimension> getDimensions(){ return dimensions; }
    public String getDtype()              { return dtype; }
    public OmeZarrFormat getFormat()      { return format; }
    public Compression getCompression()   { return compression; }
    public Object getStorageOrder()       { return storageOrder; }
    public Plate getPlate()               { return plate; }
    public boolean isOverwrite()          { return overwrite; }

    // -------------------------------------------------------------------------
    // Computed properties (mirror Python AcquisitionSettings properties)
    // -------------------------------------------------------------------------

    public String getOutputPath() {
        return format.getOutputPath(rootPath);
    }

    /** Shape of the array (count per dimension; null = unbounded). */
    public Integer[] getShape() {
        return dimensions.stream()
                .map(Dimension::getCount)
                .toArray(Integer[]::new);
    }

    public boolean isUnbounded() {
        return !dimensions.isEmpty() && dimensions.get(0).getCount() == null;
    }

    /** Total number of frames, or {@code null} if any dimension is unbounded. */
    public Integer getNumFrames() {
        List<Dimension> nonFrame = dimensions.subList(0, Math.max(0, dimensions.size() - 2));
        int total = 1;
        for (Dimension d : nonFrame) {
            if (d.isPositionDim()) continue;
            if (d.getCount() == null) return null;
            total *= d.getCount();
        }
        return total;
    }

    /** The last two dimensions (in-frame, usually Y and X). */
    public List<Dimension> getFrameDimensions() {
        int n = dimensions.size();
        return dimensions.subList(n - 2, n);
    }

    /**
     * All non-frame, non-position dimensions (the axes indexed within a position array).
     */
    public List<Dimension> getIndexDimensions() {
        return dimensions.subList(0, dimensions.size() - 2).stream()
                .filter(d -> !d.isPositionDim())
                .collect(Collectors.toList());
    }

    /**
     * All dimensions excluding position (the shape of each per-position array).
     */
    public List<Dimension> getArrayDimensions() {
        return dimensions.stream()
                .filter(d -> !d.isPositionDim())
                .collect(Collectors.toList());
    }

    /**
     * Index dimensions sorted into storage order.
     */
    public List<Dimension> getStorageIndexDimensions() {
        return sortToStorageOrder(getIndexDimensions());
    }

    /**
     * All array dimensions (no position) in storage order.
     */
    public List<Dimension> getArrayStorageDimensions() {
        List<Dimension> result = new ArrayList<>(getStorageIndexDimensions());
        result.addAll(getFrameDimensions());
        return result;
    }

    /**
     * Permutation array: {@code perm[storageSlot] = acquisitionSlot}.
     * Returns {@code null} when storage order matches acquisition order.
     */
    public int[] getStorageIndexPermutation() {
        List<Dimension> acq     = getIndexDimensions();
        List<Dimension> storage = getStorageIndexDimensions();
        List<String> acqNames   = acq.stream().map(Dimension::getName).collect(Collectors.toList());
        int[] perm = new int[acq.size()];
        for (int i = 0; i < storage.size(); i++) {
            perm[i] = acqNames.indexOf(storage.get(i).getName());
        }
        // Return null if it's the identity permutation
        for (int i = 0; i < perm.length; i++) {
            if (perm[i] != i) return perm;
        }
        return null;
    }

    /**
     * Position objects in acquisition order. Returns a single default position
     * when no position dimension is defined.
     */
    public List<Position> getPositions() {
        for (Dimension dim : dimensions.subList(0, dimensions.size() - 2)) {
            if (!dim.isPositionDim()) continue;
            List<Object> coords = dim.getCoords();
            if (coords != null) {
                return coords.stream()
                        .filter(c -> c instanceof Position)
                        .map(c -> (Position) c)
                        .collect(Collectors.toList());
            }
            return List.of(new Position("0"));
        }
        return List.of(new Position("0"));
    }

    /**
     * Index of the position dimension in {@link #getDimensions()}, or {@code -1}.
     */
    public int getPositionDimensionIndex() {
        List<Dimension> nonFrame = dimensions.subList(0, dimensions.size() - 2);
        for (int i = 0; i < nonFrame.size(); i++) {
            if (nonFrame.get(i).isPositionDim()) return i;
        }
        return -1;
    }

    // -------------------------------------------------------------------------
    // Storage-order sorting
    // -------------------------------------------------------------------------

    private List<Dimension> sortToStorageOrder(List<Dimension> indexDims) {
        if (storageOrder instanceof String) {
            String s = (String) storageOrder;
            if ("acquisition".equals(s)) return new ArrayList<>(indexDims);
            if ("ome".equals(s))         return sortedNgff(indexDims);
            throw new IllegalStateException("Unknown storage_order: " + s);
        }
        if (storageOrder instanceof List) {
            List<?> names = (List<?>) storageOrder;
            Map<String, Dimension> byName = indexDims.stream()
                    .collect(Collectors.toMap(Dimension::getName, d -> d));
            return names.stream()
                    .map(Object::toString)
                    .filter(byName::containsKey)
                    .map(byName::get)
                    .collect(Collectors.toList());
        }
        throw new IllegalStateException("storageOrder must be String or List<String>");
    }

    /** Sort index dims by NGFF canonical order: time → channel → space (z, y, x). */
    private static List<Dimension> sortedNgff(List<Dimension> dims) {
        List<Dimension> sorted = new ArrayList<>(dims);
        sorted.sort(Comparator.comparingInt(AcquisitionSettings::ngffSortKey0)
                              .thenComparingInt(AcquisitionSettings::ngffSortKey1));
        return sorted;
    }

    private static int ngffSortKey0(Dimension d) {
        DimensionType t = d.getType() == null ? DimensionType.OTHER : d.getType();
        if (t == DimensionType.TIME)    return 0;
        if (t == DimensionType.CHANNEL) return 1;
        if (t == DimensionType.SPACE)   return 2;
        return 3;
    }

    private static int ngffSortKey1(Dimension d) {
        if (d.getType() == DimensionType.SPACE) {
            switch (d.getName().toLowerCase()) {
                case "z": return 0;
                case "y": return 1;
                case "x": return 2;
                default:  return -1;
            }
        }
        return 0;
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static Builder builder() { return new Builder(); }

    public static final class Builder {

        private String rootPath = "";
        private List<Dimension> dimensions = new ArrayList<>();
        private String dtype = "";
        private OmeZarrFormat format = new OmeZarrFormat();
        private Compression compression = null;
        private Object storageOrder = "ome";
        private Plate plate = null;
        private boolean overwrite = false;

        public Builder rootPath(String path)          { this.rootPath = path;        return this; }
        public Builder dimensions(List<Dimension> d)  { this.dimensions = new ArrayList<>(d); return this; }
        public Builder dtype(String dtype)            { this.dtype = dtype;          return this; }
        public Builder format(OmeZarrFormat fmt)      { this.format = fmt;           return this; }
        public Builder compression(Compression c)     { this.compression = c;        return this; }
        /** {@code "ome"}, {@code "acquisition"}, or an explicit ordered list of axis names. */
        public Builder storageOrder(String order)     { this.storageOrder = order;   return this; }
        public Builder storageOrder(List<String> ord) { this.storageOrder = new ArrayList<>(ord); return this; }
        public Builder plate(Plate plate)             { this.plate = plate;          return this; }
        public Builder overwrite(boolean ow)          { this.overwrite = ow;         return this; }

        public AcquisitionSettings build() {
            // --- require at least 2 dimensions set ---
            if (dimensions.size() < 2)
                throw new IllegalArgumentException(
                        "At least 2 dimensions required (usually Y and X)");

            // --- enforce last-2-must-be-spatial, no-position-in-last-2 ---
            for (Dimension d : dimensions.subList(dimensions.size() - 2, dimensions.size())) {
                if (d.getType() == DimensionType.POSITION)
                    throw new IllegalArgumentException(
                            "Last two dimensions must not be position dimensions");
            }

            // --- unique names ---
            Set<String> seen = new HashSet<>();
            for (Dimension d : dimensions) {
                if (!seen.add(d.getName()))
                    throw new IllegalArgumentException("Duplicate dimension name: " + d.getName());
            }

            // --- at most one position dimension ---
            long posDims = dimensions.stream().filter(Dimension::isPositionDim).count();
            if (posDims > 1)
                throw new IllegalArgumentException("Only one position dimension is allowed");

            // --- only first dim may be unbounded ---
            for (int i = 1; i < dimensions.size(); i++) {
                if (dimensions.get(i).getCount() == null)
                    throw new IllegalArgumentException(
                            "Only the first dimension may be unbounded (count=null)");
            }

            // --- at most 5 non-position dims ---
            long nonPos = dimensions.stream().filter(d -> !d.isPositionDim()).count();
            if (nonPos < 2 || nonPos > 5)
                throw new IllegalArgumentException(
                        "Need 2–5 non-position dimensions, got " + nonPos);

            // --- validate storage_order list doesn't permute last 2 dims ---
            if (storageOrder instanceof List) {
                List<?> names = (List<?>) storageOrder;
                int n = dimensions.size();
                Set<String> last2 = new HashSet<>(Arrays.asList(
                        dimensions.get(n - 2).getName(), dimensions.get(n - 1).getName()));
                List<?> last2InOrder = names.subList(Math.max(0, names.size() - 2), names.size());
                Set<String> last2Given = last2InOrder.stream().map(Object::toString).collect(Collectors.toSet());
                if (!last2Given.equals(last2))
                    throw new IllegalArgumentException(
                            "storage_order may not permute the last two dimensions");
            }

            return new AcquisitionSettings(this);
        }
    }
}

