package org.micromanager.omewriters;

import java.util.*;
import java.util.stream.Collectors;

/**
 * {@link CoordTracker} for acquisitions whose first (outer) dimension is unbounded.
 *
 * <p>The outer dimension grows without limit. Inner dimensions (indices 1…n-1 of the
 * non-frame dims) are bounded and their HWMs are pre-computed. The outer-dimension HWM
 * is checked on every frame via a simple division.
 */
final class UnboundedCoordTracker extends CoordTracker {

    private final int innerProduct;               // product of all bounded inner counts
    private final Map<Integer, int[]> innerHwms;  // offset within cycle → inner maxIndices
    private final int[] maxInnerHwmVals;          // element-wise max over all inner HWM vals
    private final int[] innerStrides;             // row-major strides for inner dims

    UnboundedCoordTracker(AcquisitionSettings settings, int initialFrameCount) {
        super(settings, initialFrameCount);

        // Inner dims are all non-frame dims after the first (all bounded)
        List<Dimension> innerDims = nonFrameDims.size() > 1
                ? nonFrameDims.subList(1, nonFrameDims.size())
                : Collections.emptyList();

        int[] innerCounts = innerDims.stream()
                .mapToInt(d -> d.getCount() == null ? 1 : d.getCount())
                .toArray();

        int product = 1;
        for (int c : innerCounts) product *= c;
        this.innerProduct = product;

        this.innerHwms    = highWaterMarks(innerCounts);
        this.innerStrides = computeStrides(innerCounts);

        // Element-wise max of all inner HWM values (for fast skip over full cycles)
        int innerDimCount = innerDims.size();
        if (!innerHwms.isEmpty()) {
            int[] maxVals = new int[innerDimCount];
            for (int[] vals : innerHwms.values()) {
                for (int i = 0; i < vals.length; i++) {
                    if (vals[i] > maxVals[i]) maxVals[i] = vals[i];
                }
            }
            this.maxInnerHwmVals = maxVals;
        } else {
            this.maxInnerHwmVals = new int[innerDimCount];
        }

        // currentMaxIndices: [outerMax, inner0Max, inner1Max, ...]
        this.currentMaxIndices = new int[1 + innerDimCount];
        Arrays.fill(currentMaxIndices, -1);

        if (framesWritten > 0) initMaxIndices();
    }

    private void initMaxIndices() {
        int fc = framesWritten;
        currentMaxIndices[0] = (fc - 1) / innerProduct;

        if (fc >= innerProduct) {
            // At least one full inner cycle completed → all inner dims are at their max
            for (int i = 0; i < nonFrameDims.size() - 1; i++) {
                Integer c = nonFrameDims.get(i + 1).getCount();
                currentMaxIndices[i + 1] = (c == null ? 1 : c) - 1;
            }
        } else {
            int innerOffset = (innerProduct > 0) ? (fc - 1) % innerProduct : 0;
            for (Map.Entry<Integer, int[]> e : innerHwms.entrySet()) {
                if (e.getKey() <= innerOffset) {
                    for (int i = 0; i < e.getValue().length; i++) {
                        currentMaxIndices[i + 1] = e.getValue()[i];
                    }
                }
            }
        }
    }

    @Override
    public CoordUpdate update() {
        int frameNum = framesWritten++;
        boolean isHwm = checkHwm(frameNum);
        return buildUpdate(frameNum, isHwm);
    }

    @Override
    public CoordUpdate skip(int frames) {
        int startIdx = framesWritten;
        int endIdx   = startIdx + frames;
        framesWritten = endIdx;

        boolean isHwm = false;

        // Outer dim
        int outerEnd = (endIdx - 1) / innerProduct;
        if (outerEnd > currentMaxIndices[0]) {
            currentMaxIndices[0] = outerEnd;
            isHwm = true;
        }

        if (innerHwms.isEmpty()) {
            return isHwm ? buildUpdate(endIdx - 1, true) : null;
        }

        if (endIdx - startIdx >= innerProduct) {
            // Range spans at least one full cycle → all inner HWMs hit
            for (int i = 0; i < maxInnerHwmVals.length; i++) {
                if (maxInnerHwmVals[i] > currentMaxIndices[i + 1]) {
                    currentMaxIndices[i + 1] = maxInnerHwmVals[i];
                    isHwm = true;
                }
            }
        } else {
            for (int f = startIdx; f < endIdx; f++) {
                int offset = f % innerProduct;
                int[] vals = innerHwms.get(offset);
                if (vals != null) {
                    for (int i = 0; i < vals.length; i++) {
                        if (vals[i] > currentMaxIndices[i + 1]) {
                            currentMaxIndices[i + 1] = vals[i];
                            isHwm = true;
                        }
                    }
                }
            }
        }

        return isHwm ? buildUpdate(endIdx - 1, true) : null;
    }

    @Override
    protected int[] frameToIndices(int frameNum) {
        int outerIdx    = frameNum / innerProduct;
        int innerOffset = frameNum % innerProduct;
        int[] indices = new int[1 + innerStrides.length];
        indices[0] = outerIdx;
        int remaining = innerOffset;
        for (int i = 0; i < innerStrides.length; i++) {
            indices[i + 1] = remaining / innerStrides[i];
            remaining       = remaining % innerStrides[i];
        }
        return indices;
    }

    private boolean checkHwm(int frameNum) {
        boolean isHwm = false;

        int outerIdx = frameNum / innerProduct;
        if (outerIdx > currentMaxIndices[0]) {
            currentMaxIndices[0] = outerIdx;
            isHwm = true;
        }

        int innerOffset = frameNum % innerProduct;
        int[] innerVals = innerHwms.get(innerOffset);
        if (innerVals != null) {
            for (int i = 0; i < innerVals.length; i++) {
                if (innerVals[i] > currentMaxIndices[i + 1]) {
                    currentMaxIndices[i + 1] = innerVals[i];
                    isHwm = true;
                }
            }
        }
        return isHwm;
    }
}

