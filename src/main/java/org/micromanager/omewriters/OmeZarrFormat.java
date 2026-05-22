package org.micromanager.omewriters;

/**
 * Format configuration for OME-Zarr v0.5 output.
 *
 * <p>Pass to {@link AcquisitionSettings.Builder#format(OmeZarrFormat)} to
 * select the Zarr backend and output directory suffix.
 */
public final class OmeZarrFormat {

    public static final String FORMAT_NAME = "ome-zarr";

    private final String suffix;

    public OmeZarrFormat() {
        this(".ome.zarr");
    }

    public OmeZarrFormat(String suffix) {
        this.suffix = suffix;
    }

    public String getSuffix() { return suffix; }

    /**
     * Resolve the final output directory path.
     *
     * <p>Strips any existing {@code .ome.zarr} / {@code .zarr} suffix from
     * {@code rootPath} before appending the configured suffix.
     */
    public String getOutputPath(String rootPath) {
        return omeStem(rootPath) + suffix;
    }

    /** Strip trailing {@code [.ome].zarr} / {@code [.ome].tiff} suffix. */
    static String omeStem(String path) {
        String lower = path.toLowerCase();
        for (String ext : new String[]{".ome.zarr", ".zarr", ".ome.tiff", ".tiff", ".tif"}) {
            if (lower.endsWith(ext)) {
                return path.substring(0, path.length() - ext.length());
            }
        }
        return path;
    }
}

