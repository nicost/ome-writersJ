package org.micromanager.omewriters;

import java.util.Map;

/**
 * Coordinate state snapshot delivered to event listeners by {@link CoordTracker}.
 */
public final class CoordUpdate {

    private final Map<String, Object>  maxCoords;
    private final Map<String, Integer> currentIndices;
    private final int  frameNumber;
    private final boolean isHighWaterMark;

    public CoordUpdate(
            Map<String, Object>  maxCoords,
            Map<String, Integer> currentIndices,
            int  frameNumber,
            boolean isHighWaterMark) {
        this.maxCoords       = maxCoords;
        this.currentIndices  = currentIndices;
        this.frameNumber     = frameNumber;
        this.isHighWaterMark = isHighWaterMark;
    }

    /** Dimension name → visible coordinate range (List&lt;String&gt; or Integer). */
    public Map<String, Object>  maxCoords()       { return maxCoords; }
    /** Dimension name → index of the most-recently written frame. */
    public Map<String, Integer> currentIndices()  { return currentIndices; }
    /** 0-based global frame counter. */
    public int frameNumber()                      { return frameNumber; }
    /** {@code true} when {@code maxCoords} expanded on this frame. */
    public boolean isHighWaterMark()              { return isHighWaterMark; }
}

