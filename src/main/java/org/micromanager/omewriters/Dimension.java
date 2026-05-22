package org.micromanager.omewriters;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A single axis of the acquisition.
 *
 * <p>Use {@link #builder(String)} to construct instances; validation runs at
 * {@link Builder#build()} time.
 *
 * <p>The last two dimensions in an {@link AcquisitionSettings} must be spatial
 * (Y and X). Only the first dimension may be unbounded ({@code count == null}).
 */
public final class Dimension {

    private final String name;
    private final Integer count;       // null = unbounded
    private final DimensionType type;
    private final List<Object> coords; // Channel | Position | String | Double
    private final Integer chunkSize;
    private final Integer shardSizeChunks;
    private final String unit;
    private final Double scale;
    private final Double translation;

    private Dimension(Builder b) {
        this.name            = b.name;
        this.count           = b.count;
        this.type            = b.type;
        this.coords          = b.coords == null ? null : Collections.unmodifiableList(b.coords);
        this.chunkSize       = b.chunkSize;
        this.shardSizeChunks = b.shardSizeChunks;
        this.unit            = b.unit;
        this.scale           = b.scale;
        this.translation     = b.translation;
    }

    public String getName()            { return name; }
    public Integer getCount()          { return count; }
    public DimensionType getType()     { return type; }
    public List<Object> getCoords()    { return coords; }
    public Integer getChunkSize()      { return chunkSize; }
    public Integer getShardSizeChunks(){ return shardSizeChunks; }
    public String getUnit()            { return unit; }
    public Double getScale()           { return scale; }
    public Double getTranslation()     { return translation; }

    /** True if this is a position-type dimension. */
    public boolean isPositionDim() {
        return type == DimensionType.POSITION;
    }

    /** True if this is a channel-type dimension. */
    public boolean isChannelDim() {
        return type == DimensionType.CHANNEL;
    }

    @Override
    public String toString() {
        return "Dimension{name='" + name + "', count=" + count + ", type=" + type + "}";
    }

    public static Builder builder(String name) { return new Builder(name); }

    public static final class Builder {

        private final String name;
        private Integer count;
        private DimensionType type;
        private List<Object> coords;
        private Integer chunkSize;
        private Integer shardSizeChunks;
        private String unit;
        private Double scale;
        private Double translation;

        public Builder(String name) {
            if (name == null || name.isEmpty())
                throw new IllegalArgumentException("Dimension name must not be empty");
            this.name = name;
        }

        /** {@code null} = unbounded (only valid for the first dimension). */
        public Builder count(Integer count) {
            if (count != null && count <= 0)
                throw new IllegalArgumentException("count must be positive, got: " + count);
            this.count = count;
            return this;
        }

        public Builder type(DimensionType type)     { this.type = type;            return this; }
        public Builder chunkSize(int cs)            { this.chunkSize = cs;         return this; }
        public Builder shardSizeChunks(int ssc)     { this.shardSizeChunks = ssc;  return this; }
        public Builder scale(double scale)          { this.scale = scale;          return this; }
        public Builder translation(double t)        { this.translation = t;        return this; }

        /**
         * Physical unit (NGFF-compliant name or OME-XML abbreviation).
         * Validated and normalized at {@link #build()}.
         */
        public Builder unit(String unit)            { this.unit = unit;            return this; }

        /**
         * Coordinate labels for each element along this dimension.
         * Elements may be {@link Channel}, {@link Position}, {@link String}, or {@link Double}.
         * If supplied without {@link #count}, count is inferred from list length.
         */
        public Builder coords(List<?> coords) {
            this.coords = new ArrayList<>(coords);
            return this;
        }

        /** Convenience: channel-name strings become {@link Channel} objects. */
        public Builder channelNames(String... names) {
            this.coords = new ArrayList<>();
            for (String n : names) this.coords.add(new Channel(n));
            this.type = DimensionType.CHANNEL;
            return this;
        }

        /** Convenience: position-name strings become {@link Position} objects. */
        public Builder positionNames(String... names) {
            this.coords = new ArrayList<>();
            for (String n : names) this.coords.add(new Position(n));
            this.type = DimensionType.POSITION;
            return this;
        }

        public Dimension build() {
            String resolvedUnit  = unit;
            DimensionType resolvedType = type;

            // Infer type from unit
            if (resolvedType == null && resolvedUnit != null) {
                resolvedType = UnitConverter.inferDimTypeFromUnit(resolvedUnit);
            }

            // Infer type from coords
            if (coords != null && !coords.isEmpty()) {
                boolean hasChannel  = coords.stream().anyMatch(c -> c instanceof Channel);
                boolean hasPosition = coords.stream().anyMatch(c -> c instanceof Position);
                if (hasChannel && hasPosition)
                    throw new IllegalArgumentException("Cannot mix Channel and Position in coords");
                DimensionType fromCoords = hasChannel ? DimensionType.CHANNEL
                                        : hasPosition ? DimensionType.POSITION : null;
                if (fromCoords != null) {
                    if (resolvedType != null && resolvedType != fromCoords)
                        throw new IllegalArgumentException(
                                "Conflicting type inference: coords implies " + fromCoords +
                                " but explicit type is " + resolvedType);
                    resolvedType = fromCoords;
                }
            }

            // Validate/normalize unit
            if (resolvedUnit != null) {
                resolvedUnit = UnitConverter.castUnitToNgff(resolvedUnit, resolvedType);
            }

            // Auto-generate position coords from count
            if (resolvedType == DimensionType.POSITION && coords == null) {
                if (count == null)
                    throw new UnsupportedOperationException(
                            "Unbounded position dimensions are not yet supported");
                coords = new ArrayList<>();
                for (int i = 0; i < count; i++) coords.add(new Position(String.valueOf(i)));
            }

            // Reconcile count vs coords length
            Integer resolvedCount = count;
            if (coords != null && !coords.isEmpty()) {
                if (resolvedCount == null) {
                    resolvedCount = coords.size();
                } else if (coords.size() != resolvedCount) {
                    throw new IllegalArgumentException(
                            "coords length (" + coords.size() + ") != count (" + resolvedCount + ")");
                }
            }

            Builder resolved = new Builder(name);
            resolved.count           = resolvedCount;
            resolved.type            = resolvedType;
            resolved.coords          = coords;
            resolved.chunkSize       = chunkSize;
            resolved.shardSizeChunks = shardSizeChunks;
            resolved.unit            = resolvedUnit;
            resolved.scale           = scale;
            resolved.translation     = translation;
            return new Dimension(resolved);
        }
    }
}

