package org.micromanager.omewriters;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Stateful iterator mapping acquisition-order frame indices to storage locations.
 *
 * <p>This is the central routing component: it knows both acquisition order
 * (dimension order in {@link AcquisitionSettings#getDimensions()}) and storage
 * order (from {@link AcquisitionSettings#getStorageIndexPermutation()}), and
 * translates between them on each call to {@link #next()}.
 *
 * <p>The iterator may be restarted by calling {@link #reset()} or by creating a
 * new for-each loop (calling {@link #iterator()} resets automatically).
 *
 * <p>For unbounded (unlimited) first dimensions the iterator never terminates;
 * callers must break out of the loop themselves.
 *
 * <h2>Example — simple TCZYX acquisition</h2>
 * <pre>{@code
 * AcquisitionSettings s = AcquisitionSettings.builder()
 *     .rootPath("data.ome.zarr")
 *     .dimensions(List.of(
 *         Dimension.builder("t").count(2).type(DimensionType.TIME).build(),
 *         Dimension.builder("c").count(3).type(DimensionType.CHANNEL).build(),
 *         Dimension.builder("y").count(64).type(DimensionType.SPACE).build(),
 *         Dimension.builder("x").count(64).type(DimensionType.SPACE).build()))
 *     .dtype("uint16").build();
 *
 * for (FrameRoute route : new FrameRouter(s)) {
 *     // route.positionIndex() == 0 always (no position dim)
 *     // route.storageIndex()  == [t, c]
 *     writeFrame(route.positionIndex(), route.storageIndex(), acquireFrame());
 * }
 * }</pre>
 */
public final class FrameRouter implements Iterable<FrameRoute>, Iterator<FrameRoute> {

    /** Sizes of all non-frame dimensions in acquisition order (null = unbounded). */
    private final Integer[] nonFrameSizes;
    /** Which slot in nonFrameSizes is the position dimension; -1 if absent. */
    private final int positionSlot;
    /** Slots to include in the storage index (all non-frame, non-position slots). */
    private final int[] acqIdxSlots;
    /** Permutation from acquisition index order to storage index order; null = identity. */
    private final int[] permutation;

    /** Mutable iteration state. */
    private int[] dimIndices;
    private boolean finished;

    public FrameRouter(AcquisitionSettings settings) {
        int totalDims    = settings.getDimensions().size();
        int nonFrameCount = totalDims - 2;

        this.nonFrameSizes = new Integer[nonFrameCount];
        for (int i = 0; i < nonFrameCount; i++) {
            this.nonFrameSizes[i] = settings.getDimensions().get(i).getCount();
        }

        this.positionSlot = settings.getPositionDimensionIndex();

        // slots for index dimensions (all non-frame except position)
        int idxCount = 0;
        for (int i = 0; i < nonFrameCount; i++) {
            if (i != positionSlot) idxCount++;
        }
        this.acqIdxSlots = new int[idxCount];
        int j = 0;
        for (int i = 0; i < nonFrameCount; i++) {
            if (i != positionSlot) acqIdxSlots[j++] = i;
        }

        this.permutation = settings.getStorageIndexPermutation();
        reset();
    }

    // -------------------------------------------------------------------------
    // Iterable / Iterator
    // -------------------------------------------------------------------------

    /** Resets the router to the first frame and returns {@code this}. */
    @Override
    public Iterator<FrameRoute> iterator() {
        reset();
        return this;
    }

    public void reset() {
        dimIndices = new int[nonFrameSizes.length];
        finished   = false;
    }

    @Override
    public boolean hasNext() {
        return !finished;
    }

    @Override
    public FrameRoute next() {
        if (finished) throw new NoSuchElementException("FrameRouter exhausted");

        int posIdx = (positionSlot >= 0) ? dimIndices[positionSlot] : 0;

        // Build storage index
        int[] storageIdx = new int[acqIdxSlots.length];
        for (int i = 0; i < acqIdxSlots.length; i++) {
            storageIdx[i] = dimIndices[acqIdxSlots[i]];
        }
        if (permutation != null) {
            int[] permuted = new int[storageIdx.length];
            for (int i = 0; i < permutation.length; i++) {
                permuted[i] = storageIdx[permutation[i]];
            }
            storageIdx = permuted;
        }

        incrementIndices();
        return new FrameRoute(posIdx, storageIdx);
    }

    // -------------------------------------------------------------------------
    // Index increment (nested-loop carry logic, rightmost varies fastest)
    // -------------------------------------------------------------------------

    private void incrementIndices() {
        int n = nonFrameSizes.length;
        if (n == 0) { finished = true; return; }

        int last = n - 1;
        dimIndices[last]++;

        Integer sizeLimit = nonFrameSizes[last];
        if (sizeLimit == null) return;           // rightmost is unbounded
        if (dimIndices[last] < sizeLimit) return; // common path: still in bounds

        // carry
        dimIndices[last] = 0;
        for (int i = last - 1; i >= 0; i--) {
            dimIndices[i]++;
            sizeLimit = nonFrameSizes[i];
            if (sizeLimit == null) return;        // unbounded dim
            if (dimIndices[i] < sizeLimit) return;
            dimIndices[i] = 0;
        }
        // wrapped all dimensions
        finished = true;
    }
}

