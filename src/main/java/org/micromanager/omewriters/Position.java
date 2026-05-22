package org.micromanager.omewriters;

/**
 * A single acquisition position (field of view).
 *
 * <p>Used in {@link Dimension#getCoords()} for dimensions of type
 * {@link DimensionType#POSITION}. Optional plate/grid fields enable
 * well-plate or grid-layout hierarchies in OME-Zarr.
 */
public final class Position {

    private final String name;
    private final String plateRow;
    private final String plateColumn;
    private final Integer gridRow;
    private final Integer gridColumn;
    private final Double xCoord;
    private final Double yCoord;
    private final Double zCoord;

    private Position(Builder b) {
        if (b.name == null || b.name.isEmpty())
            throw new IllegalArgumentException("Position name must not be empty");
        if ((b.plateRow == null) != (b.plateColumn == null))
            throw new IllegalArgumentException("plateRow and plateColumn must both be set or both null");
        if ((b.gridRow == null) != (b.gridColumn == null))
            throw new IllegalArgumentException("gridRow and gridColumn must both be set or both null");
        this.name        = b.name;
        this.plateRow    = b.plateRow;
        this.plateColumn = b.plateColumn;
        this.gridRow     = b.gridRow;
        this.gridColumn  = b.gridColumn;
        this.xCoord      = b.xCoord;
        this.yCoord      = b.yCoord;
        this.zCoord      = b.zCoord;
    }

    /** Convenience constructor for name-only positions. */
    public Position(String name) {
        this(new Builder(name));
    }

    public String getName()        { return name; }
    public String getPlateRow()    { return plateRow; }
    public String getPlateColumn() { return plateColumn; }
    public Integer getGridRow()    { return gridRow; }
    public Integer getGridColumn() { return gridColumn; }
    public Double getXCoord()      { return xCoord; }
    public Double getYCoord()      { return yCoord; }
    public Double getZCoord()      { return zCoord; }

    @Override
    public String toString() {
        return "Position{name='" + name + "'}";
    }

    public static Builder builder(String name) { return new Builder(name); }

    public static final class Builder {
        private final String name;
        private String plateRow, plateColumn;
        private Integer gridRow, gridColumn;
        private Double xCoord, yCoord, zCoord;

        public Builder(String name) { this.name = name; }

        public Builder plateRow(String r)    { this.plateRow = r;    return this; }
        public Builder plateColumn(String c) { this.plateColumn = c; return this; }
        public Builder gridRow(int r)        { this.gridRow = r;     return this; }
        public Builder gridColumn(int c)     { this.gridColumn = c;  return this; }
        public Builder xCoord(double x)      { this.xCoord = x;      return this; }
        public Builder yCoord(double y)      { this.yCoord = y;      return this; }
        public Builder zCoord(double z)      { this.zCoord = z;      return this; }

        public Position build() { return new Position(this); }
    }
}

