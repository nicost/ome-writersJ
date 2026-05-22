package org.micromanager.omewriters;

import java.util.*;

/**
 * {@link CoordTracker} for acquisitions where all dimensions are bounded.
 *
 * <p>Pre-computes the full high-water-mark table at construction time, so
 * each call to {@link #update()} is O(1).
 */
final class BoundedCoordTracker extends CoordTracker {

    private final Map<Integer, int[]> hwmTable;   // frameIndex → maxIndices
    private final int[] sortedHwmKeys;            // for binary search in skip()
    private final int[] strides;                  // row-major strides for frameToIndices

    BoundedCoordTracker(AcquisitionSettings settings, int initialFrameCount) {
        super(settings, initialFrameCount);

        int n = nonFrameDims.size();
        int[] counts = new int[n];
        for (int i = 0; i < n; i++) {
            Integer c = nonFrameDims.get(i).getCount();
            counts[i] = (c == null) ? 1 : c; // bounded by contract
        }

        this.hwmTable = highWaterMarks(counts);
        this.strides  = computeStrides(counts);

        int[] sorted = hwmTable.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
        this.sortedHwmKeys = sorted;

        this.currentMaxIndices = new int[n];
        Arrays.fill(currentMaxIndices, -1);

        // Catch up to initialFrameCount
        if (framesWritten > 0) {
            for (int key : sortedHwmKeys) {
                if (key < framesWritten) {
                    currentMaxIndices = hwmTable.get(key).clone();
                } else {
                    break;
                }
            }
        }
    }

    @Override
    public CoordUpdate update() {
        int frameNum = framesWritten++;
        int[] hwm = hwmTable.get(frameNum);
        boolean isHwm = hwm != null;
        if (isHwm) currentMaxIndices = hwm.clone();
        return buildUpdate(frameNum, isHwm);
    }

    @Override
    public CoordUpdate skip(int frames) {
        int startIdx = framesWritten;
        int endIdx   = startIdx + frames;
        framesWritten = endIdx;

        // Binary search for the highest HWM key in [startIdx, endIdx)
        int lo = lowerBound(sortedHwmKeys, startIdx);
        int hi = lowerBound(sortedHwmKeys, endIdx);

        if (lo < hi) {
            int highestKey = sortedHwmKeys[hi - 1];
            currentMaxIndices = hwmTable.get(highestKey).clone();
            return buildUpdate(endIdx - 1, true);
        }
        return null;
    }

    @Override
    protected int[] frameToIndices(int frameNum) {
        int n = strides.length;
        int[] indices  = new int[n];
        int remaining  = frameNum;
        for (int i = 0; i < n; i++) {
            indices[i] = remaining / strides[i];
            remaining  = remaining % strides[i];
        }
        return indices;
    }

    // leftmost position in sorted where sorted[pos] >= target
    private static int lowerBound(int[] sorted, int target) {
        int lo = 0, hi = sorted.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted[mid] < target) lo = mid + 1; else hi = mid;
        }
        return lo;
    }
}

