package org.micromanager.omewriters;

import java.util.Arrays;

/**
 * A single routing result from {@link FrameRouter}: where to write the next frame.
 */
public final class FrameRoute {

    private final int positionIndex;
    private final int[] storageIndex;

    public FrameRoute(int positionIndex, int[] storageIndex) {
        this.positionIndex = positionIndex;
        this.storageIndex  = storageIndex;
    }

    /** Index into {@link AcquisitionSettings#getPositions()} — which array to write to. */
    public int positionIndex() { return positionIndex; }

    /** N-dimensional index in <em>storage</em> order (excludes Y/X in-frame dimensions). */
    public int[] storageIndex() { return storageIndex; }

    @Override
    public String toString() {
        return "FrameRoute{pos=" + positionIndex + ", idx=" + Arrays.toString(storageIndex) + "}";
    }
}

