package org.micromanager.omewriters;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Tracks coordinate visibility and fires "high water mark" events.
 *
 * <p>A high water mark (HWM) occurs when the maximum observed index along
 * any dimension increases — i.e. a previously-unseen row/channel/timepoint
 * becomes visible for the first time.
 *
 * <p>Use {@link #create(AcquisitionSettings)} to obtain the correct subclass.
 * Call {@link #update()} after each frame and {@link #skip(int)} when frames
 * are omitted.
 */
public abstract class CoordTracker {

    protected final List<Dimension> nonFrameDims;
    protected final List<String> nonFrameNames;
    protected final Map<String, Integer> frameDimZeros;

    protected int framesWritten;
    protected boolean needsCurrentIndices;

    /** Current maximum index reached for each non-frame dimension. */
    protected int[] currentMaxIndices;

    protected CoordTracker(AcquisitionSettings settings, int initialFrameCount) {
        List<Dimension> dims = settings.getDimensions();
        this.nonFrameDims  = List.copyOf(dims.subList(0, dims.size() - 2));
        this.framesWritten = initialFrameCount;
        this.needsCurrentIndices = false;

        this.nonFrameNames = nonFrameDims.stream().map(Dimension::getName).collect(Collectors.toList());

        // frame dims always have index 0 in the coord map
        Map<String, Integer> zeros = new LinkedHashMap<>();
        for (Dimension d : dims.subList(dims.size() - 2, dims.size())) {
            zeros.put(d.getName(), 0);
        }
        this.frameDimZeros = Collections.unmodifiableMap(zeros);
    }

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /**
     * Return the appropriate tracker subclass for the given settings.
     * Uses {@link UnboundedCoordTracker} when the first non-frame dimension is
     * unbounded; otherwise {@link BoundedCoordTracker}.
     */
    public static CoordTracker create(AcquisitionSettings settings) {
        return create(settings, 0);
    }

    public static CoordTracker create(AcquisitionSettings settings, int initialFrameCount) {
        List<Dimension> dims = settings.getDimensions();
        List<Dimension> nonFrame = dims.subList(0, dims.size() - 2);
        if (!nonFrame.isEmpty() && nonFrame.get(0).getCount() == null) {
            return new UnboundedCoordTracker(settings, initialFrameCount);
        }
        return new BoundedCoordTracker(settings, initialFrameCount);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Configure whether {@link CoordUpdate#currentIndices()} must be populated
     * on every frame (not just HWMs). Default {@code false} (populate only on HWMs).
     */
    public void setNeedsCurrentIndices(boolean value) {
        this.needsCurrentIndices = value;
    }

    /**
     * Increment the frame counter and return a {@link CoordUpdate} if needed.
     *
     * @return non-null when this frame is a HWM or {@code needsCurrentIndices} is set;
     *         otherwise {@code null}
     */
    public abstract CoordUpdate update();

    /**
     * Fast-forward {@code frames} frames (skipped/zero-filled).
     *
     * @return non-null if any HWM was crossed in the skipped range; otherwise {@code null}
     */
    public abstract CoordUpdate skip(int frames);

    /**
     * Get the current maximum visible coordinate ranges, keyed by dimension name.
     * Values are either {@code List<String>} (named coords) or {@code Integer} (range end exclusive).
     */
    public Map<String, Object> getCoords() {
        Map<String, Object> result = new LinkedHashMap<>();
        // frame dims: full range
        for (Dimension d : nonFrameDims) {
            result.put(d.getName(), 1); // placeholder, updated below
        }
        for (int i = 0; i < nonFrameDims.size(); i++) {
            Dimension dim = nonFrameDims.get(i);
            int maxIdx = currentMaxIndices[i] + 1;
            List<Object> coords = dim.getCoords();
            if (coords != null) {
                List<String> names = new ArrayList<>(maxIdx);
                for (int k = 0; k < maxIdx && k < coords.size(); k++) {
                    Object c = coords.get(k);
                    names.add(c instanceof Channel ? ((Channel) c).getName()
                            : c instanceof Position ? ((Position) c).getName()
                            : String.valueOf(c));
                }
                result.put(dim.getName(), names);
            } else {
                result.put(dim.getName(), maxIdx);
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    protected abstract int[] frameToIndices(int frameNum);

    protected CoordUpdate buildUpdate(int frameNum, boolean isHwm) {
        if (!needsCurrentIndices && !isHwm) return null;
        int[] indices = frameToIndices(frameNum);
        Map<String, Integer> current = new LinkedHashMap<>(frameDimZeros);
        for (int i = 0; i < nonFrameNames.size(); i++) {
            current.put(nonFrameNames.get(i), indices[i]);
        }
        return new CoordUpdate(getCoords(), Collections.unmodifiableMap(current), frameNum, isHwm);
    }

    // -------------------------------------------------------------------------
    // Static utility: high-water-mark table
    // -------------------------------------------------------------------------

    /**
     * Pre-compute the table of frame indices where the maximum coordinate
     * index increases for any dimension.
     *
     * <p>Returns a map: {@code frameIndex → int[] maxIndices} where
     * {@code maxIndices[i]} is the new maximum index for dimension {@code i}
     * at that frame.
     *
     * <p>For an empty shape the map is empty. For shape {@code {2, 3}}:
     * <pre>
     *   {0: [0,0],  1: [0,1],  2: [0,2],  3: [1,2]}
     * </pre>
     */
    static Map<Integer, int[]> highWaterMarks(int[] shape) {
        if (shape.length == 0) return Map.of();

        int a0Hi = shape[0];
        // allSizes = [a0Hi, shape[1], shape[2], ...]
        int[] allSizes = new int[shape.length];
        allSizes[0] = a0Hi;
        System.arraycopy(shape, 1, allSizes, 1, shape.length - 1);

        // strides[i] = {strideValue, maxVal}  where maxVal = allSizes[i] - 1
        int[][] strides = new int[shape.length][2];
        int stride = 1;
        for (int i = allSizes.length - 1; i >= 0; i--) {
            strides[i][0] = stride;
            strides[i][1] = allSizes[i] - 1;
            stride *= allSizes[i];
        }

        // hi = total frame count = product of all sizes
        int hi = stride; // stride after the loop above = full product

        // Collect bump indices: union of arithmetic progressions [0, st, 2*st, ... up to vHi*st]
        TreeSet<Integer> bumpSet = new TreeSet<>();
        for (int[] sm : strides) {
            int st = sm[0];
            int mx = sm[1];
            // vLo = ceil(0/st) = 0 always (since lo=0)
            int vHi = Math.min((hi - 1) / st, mx);
            for (int v = 0; v <= vHi; v++) {
                bumpSet.add(v * st);
            }
        }

        if (bumpSet.isEmpty()) return Map.of();

        Map<Integer, int[]> result = new LinkedHashMap<>();
        for (int bumpIdx : bumpSet) {
            int[] vals = new int[strides.length];
            for (int i = 0; i < strides.length; i++) {
                vals[i] = Math.min(bumpIdx / strides[i][0], strides[i][1]);
            }
            result.put(bumpIdx, vals);
        }
        return result;
    }

    /** Standard row-major strides for {@code shape}. stride[i] = product(shape[i+1:]). */
    static int[] computeStrides(int[] shape) {
        int[] strides = new int[shape.length];
        int s = 1;
        for (int i = shape.length - 1; i >= 0; i--) {
            strides[i] = s;
            s *= shape[i];
        }
        return strides;
    }
}

