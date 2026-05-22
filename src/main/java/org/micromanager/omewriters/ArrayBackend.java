package org.micromanager.omewriters;

import java.util.List;

/**
 * Storage backend interface — prepares the on-disk structure and streams frames.
 *
 * <p>Each element of {@code advance}'s frame list encodes
 * {@code {positionIndex, storageIdx0, storageIdx1, ...}}.
 */
public interface ArrayBackend {

    /**
     * Create the on-disk structure (groups, arrays) for the given settings.
     * Called once before any writes.
     */
    void prepare(AcquisitionSettings settings) throws Exception;

    /**
     * Write a single 2D frame.
     *
     * @param positionIndex index into positions list
     * @param storageIndex  per-dimension indices within the position array
     *                      (excludes the two frame/spatial dims)
     * @param pixelData     primitive array: {@code short[]} for uint16,
     *                      {@code byte[]} for uint8, {@code float[]} for float32, etc.
     */
    void writeFrame(int positionIndex, int[] storageIndex, Object pixelData) throws Exception;

    /**
     * Resize arrays to accommodate skipped frames (no data written).
     *
     * @param frames list of {@code int[]} where {@code [0]} is positionIndex
     *               and {@code [1..n]} are the storage indices
     */
    void advance(List<int[]> frames) throws Exception;

    /** Flush all pending writes and release resources. */
    void close() throws Exception;
}
