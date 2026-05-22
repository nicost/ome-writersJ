package org.micromanager.omewriters;

import java.util.List;
import java.util.Map;

/** Well-plate structure for OME-Zarr plate layout. */
public final class Plate {

    private final List<String> rowNames;
    private final List<String> columnNames;
    private final String name;

    public Plate(List<String> rowNames, List<String> columnNames, String name) {
        if (rowNames == null || rowNames.isEmpty())
            throw new IllegalArgumentException("rowNames must not be empty");
        if (columnNames == null || columnNames.isEmpty())
            throw new IllegalArgumentException("columnNames must not be empty");
        this.rowNames    = List.copyOf(rowNames);
        this.columnNames = List.copyOf(columnNames);
        this.name        = name;
    }

    public Plate(List<String> rowNames, List<String> columnNames) {
        this(rowNames, columnNames, null);
    }

    public List<String> getRowNames()    { return rowNames; }
    public List<String> getColumnNames() { return columnNames; }
    public String getName()              { return name; }

    /**
     * Create a plate from a standard well-count (6, 12, 24, 48, 96, 384, 1536)
     * or from explicit (rows, columns) dimensions.
     */
    public static Plate fromStandardWells(int numWells) {
        Map<Integer, int[]> shapes = Map.of(
                6,    new int[]{2, 3},
                12,   new int[]{3, 4},
                24,   new int[]{4, 6},
                48,   new int[]{6, 8},
                96,   new int[]{8, 12},
                384,  new int[]{16, 24},
                1536, new int[]{32, 48}
        );
        int[] shape = shapes.get(numWells);
        if (shape == null)
            throw new IllegalArgumentException("Unsupported standard plate size: " + numWells);
        return fromShape(shape[0], shape[1]);
    }

    public static Plate fromShape(int nRows, int nCols) {
        List<String> rows = new java.util.ArrayList<>();
        List<String> cols = new java.util.ArrayList<>();
        for (int i = 0; i < nRows; i++) rows.add(rowName(i));
        for (int i = 0; i < nCols; i++) cols.add(String.valueOf(i + 1));
        return new Plate(rows, cols);
    }

    private static String rowName(int i) {
        char letter = (char) ('A' + (i % 26));
        int repeat  = (i / 26) + 1;
        return String.valueOf(letter).repeat(repeat);
    }
}

