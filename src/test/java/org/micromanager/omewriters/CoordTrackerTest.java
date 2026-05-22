package org.micromanager.omewriters;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CoordTrackerTest {

    private static AcquisitionSettings tcSettings(int t, int c) {
        return AcquisitionSettings.builder()
                .rootPath("test.ome.zarr")
                .dimensions(List.of(
                        Dimension.builder("t").count(t).type(DimensionType.TIME).build(),
                        Dimension.builder("c").count(c).type(DimensionType.CHANNEL).build(),
                        Dimension.builder("y").count(64).type(DimensionType.SPACE).build(),
                        Dimension.builder("x").count(64).type(DimensionType.SPACE).build()))
                .dtype("uint16")
                .storageOrder("acquisition")
                .build();
    }

    // -------------------------------------------------------------------------
    // highWaterMarks unit tests
    // -------------------------------------------------------------------------

    @Test
    void hwm_emptyShape() {
        assertTrue(CoordTracker.highWaterMarks(new int[]{}).isEmpty());
    }

    @Test
    void hwm_singleDim() {
        // shape = (3,): frames 0,1,2 each reveal a new max index
        Map<Integer, int[]> hwm = CoordTracker.highWaterMarks(new int[]{3});
        assertEquals(3, hwm.size());
        assertArrayEquals(new int[]{0}, hwm.get(0));
        assertArrayEquals(new int[]{1}, hwm.get(1));
        assertArrayEquals(new int[]{2}, hwm.get(2));
    }

    @Test
    void hwm_twoDims() {
        // shape = (2, 3): matches Python docstring
        Map<Integer, int[]> hwm = CoordTracker.highWaterMarks(new int[]{2, 3});
        assertEquals(4, hwm.size());
        assertArrayEquals(new int[]{0, 0}, hwm.get(0));
        assertArrayEquals(new int[]{0, 1}, hwm.get(1));
        assertArrayEquals(new int[]{0, 2}, hwm.get(2));
        assertArrayEquals(new int[]{1, 2}, hwm.get(3));
    }

    @Test
    void hwm_singleElement() {
        // shape = (1,): only frame 0
        Map<Integer, int[]> hwm = CoordTracker.highWaterMarks(new int[]{1});
        assertEquals(1, hwm.size());
        assertArrayEquals(new int[]{0}, hwm.get(0));
    }

    // -------------------------------------------------------------------------
    // BoundedCoordTracker
    // -------------------------------------------------------------------------

    @Test
    void bounded_hwmsFiredAtCorrectFrames() {
        // T=2, C=3: HWMs at frames 0 (T0C0), 1 (T0C1), 2 (T0C2=full C), 3 (T1=full T)
        AcquisitionSettings s = tcSettings(2, 3);
        CoordTracker tracker = CoordTracker.create(s);
        tracker.setNeedsCurrentIndices(true);

        int hwmCount = 0;
        for (int f = 0; f < 6; f++) {
            CoordUpdate u = tracker.update();
            assertNotNull(u, "update() should never return null when needsCurrentIndices=true");
            if (u.isHighWaterMark()) hwmCount++;
        }
        assertEquals(4, hwmCount, "Expected HWMs at frames 0,1,2,3");
    }

    @Test
    void bounded_noUpdateOnNonHwmFramesWhenNotNeeded() {
        // With needsCurrentIndices=false (default), non-HWM frames return null
        AcquisitionSettings s = tcSettings(2, 3);
        CoordTracker tracker = CoordTracker.create(s);

        // frame 4 (T1,C1) is NOT a HWM → null
        for (int i = 0; i < 4; i++) tracker.update();
        CoordUpdate u4 = tracker.update();
        assertNull(u4, "frame 4 is not a HWM, should return null");
    }

    @Test
    void bounded_skipCrossesHwm() {
        AcquisitionSettings s = tcSettings(3, 4);
        CoordTracker tracker = CoordTracker.create(s);
        // skip 5 frames: crosses HWMs at 0,1,2,3,4
        CoordUpdate u = tracker.skip(5);
        assertNotNull(u);
        assertTrue(u.isHighWaterMark());
        assertEquals(4, u.frameNumber());
    }

    @Test
    void bounded_skipWithNoHwm_returnsNull() {
        AcquisitionSettings s = tcSettings(3, 4);
        CoordTracker tracker = CoordTracker.create(s);
        // Advance past all HWMs first
        for (int i = 0; i < 12; i++) tracker.update();
        // Now skip 0 additional HWMs
        CoordUpdate u = tracker.skip(0);
        assertNull(u);
    }

    // -------------------------------------------------------------------------
    // UnboundedCoordTracker
    // -------------------------------------------------------------------------

    @Test
    void unbounded_usesCorrectSubclass() {
        AcquisitionSettings s = AcquisitionSettings.builder()
                .rootPath("test.ome.zarr")
                .dimensions(List.of(
                        Dimension.builder("t").count(null).type(DimensionType.TIME).build(),
                        Dimension.builder("c").count(3).type(DimensionType.CHANNEL).build(),
                        Dimension.builder("y").count(64).type(DimensionType.SPACE).build(),
                        Dimension.builder("x").count(64).type(DimensionType.SPACE).build()))
                .dtype("uint16")
                .storageOrder("acquisition")
                .build();

        CoordTracker tracker = CoordTracker.create(s);
        assertInstanceOf(UnboundedCoordTracker.class, tracker);
    }

    @Test
    void unbounded_outerDimHwmFiresEachCycle() {
        // T=unbounded, C=2: inner product=2
        // Frame 0: outer→0 (HWM), C=0 (HWM)
        // Frame 1: C=1 (HWM)
        // Frame 2: outer→1 (HWM), C=0 already seen → no inner HWM
        // Frame 3: nothing new
        AcquisitionSettings s = AcquisitionSettings.builder()
                .rootPath("test.ome.zarr")
                .dimensions(List.of(
                        Dimension.builder("t").count(null).type(DimensionType.TIME).build(),
                        Dimension.builder("c").count(2).type(DimensionType.CHANNEL).build(),
                        Dimension.builder("y").count(64).type(DimensionType.SPACE).build(),
                        Dimension.builder("x").count(64).type(DimensionType.SPACE).build()))
                .dtype("uint16")
                .storageOrder("acquisition")
                .build();

        CoordTracker tracker = CoordTracker.create(s);
        CoordUpdate u0 = tracker.update(); assertNotNull(u0); assertTrue(u0.isHighWaterMark());
        CoordUpdate u1 = tracker.update(); assertNotNull(u1); assertTrue(u1.isHighWaterMark());
        CoordUpdate u2 = tracker.update(); assertNotNull(u2); assertTrue(u2.isHighWaterMark());
        CoordUpdate u3 = tracker.update(); assertNull(u3, "frame 3: T=1,C=1 — no new max");
    }

    // -------------------------------------------------------------------------
    // Factory routing
    // -------------------------------------------------------------------------

    @Test
    void factory_boundedForFullyBoundedSettings() {
        CoordTracker t = CoordTracker.create(tcSettings(3, 2));
        assertInstanceOf(BoundedCoordTracker.class, t);
    }
}

