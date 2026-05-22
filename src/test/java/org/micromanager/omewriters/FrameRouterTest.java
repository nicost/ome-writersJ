package org.micromanager.omewriters;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FrameRouterTest {

    private static AcquisitionSettings simpleTcyx(int t, int c) {
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

    @Test
    void singlePosition_iteratesAllFrames() {
        // T=2, C=3 → 6 frames in acquisition order; storage_order=acquisition
        AcquisitionSettings s = simpleTcyx(2, 3);
        List<FrameRoute> routes = new ArrayList<>();
        for (FrameRoute r : new FrameRouter(s)) routes.add(r);

        assertEquals(6, routes.size());
        // all go to position 0
        assertTrue(routes.stream().allMatch(r -> r.positionIndex() == 0));
        // storage indices: [0,0], [0,1], [0,2], [1,0], [1,1], [1,2]
        assertArrayEquals(new int[]{0, 0}, routes.get(0).storageIndex());
        assertArrayEquals(new int[]{0, 1}, routes.get(1).storageIndex());
        assertArrayEquals(new int[]{0, 2}, routes.get(2).storageIndex());
        assertArrayEquals(new int[]{1, 0}, routes.get(3).storageIndex());
        assertArrayEquals(new int[]{1, 1}, routes.get(4).storageIndex());
        assertArrayEquals(new int[]{1, 2}, routes.get(5).storageIndex());
    }

    @Test
    void storageOrderOme_sortsTczyx() {
        // T=2, C=2 with storage_order="ome" — NGFF sort: T(0)→C(1)→Z→Y→X
        // acquisition order is already T,C so permutation should be identity
        AcquisitionSettings s = AcquisitionSettings.builder()
                .rootPath("test.ome.zarr")
                .dimensions(List.of(
                        Dimension.builder("t").count(2).type(DimensionType.TIME).build(),
                        Dimension.builder("c").count(2).type(DimensionType.CHANNEL).build(),
                        Dimension.builder("y").count(64).type(DimensionType.SPACE).build(),
                        Dimension.builder("x").count(64).type(DimensionType.SPACE).build()))
                .dtype("uint16")
                .storageOrder("ome")
                .build();

        assertNull(s.getStorageIndexPermutation(), "T,C acquisition = T,C storage → identity permutation");
    }

    @Test
    void storageOrderOme_permutesCtyx() {
        // Acquisition: C, T — OME storage wants T, C → permutation needed
        AcquisitionSettings s = AcquisitionSettings.builder()
                .rootPath("test.ome.zarr")
                .dimensions(List.of(
                        Dimension.builder("c").count(3).type(DimensionType.CHANNEL).build(),
                        Dimension.builder("t").count(2).type(DimensionType.TIME).build(),
                        Dimension.builder("y").count(64).type(DimensionType.SPACE).build(),
                        Dimension.builder("x").count(64).type(DimensionType.SPACE).build()))
                .dtype("uint16")
                .storageOrder("ome")
                .build();

        int[] perm = s.getStorageIndexPermutation();
        assertNotNull(perm, "C,T acquisition ≠ T,C storage → permutation expected");
        // perm[storageSlot] = acquisitionSlot
        // storage[0]=T which is acq[1] → perm[0]=1
        // storage[1]=C which is acq[0] → perm[1]=0
        assertArrayEquals(new int[]{1, 0}, perm);

        // Check that routes visit in C-varies-fastest order (acquisition) but
        // output storage indices are T-first
        List<FrameRoute> routes = new ArrayList<>();
        for (FrameRoute r : new FrameRouter(s)) routes.add(r);
        assertEquals(6, routes.size());
        // frame 0: acq C=0,T=0 → storage [t=0, c=0]
        assertArrayEquals(new int[]{0, 0}, routes.get(0).storageIndex());
        // frame 1: acq C=0,T=1 → storage [t=1, c=0]
        assertArrayEquals(new int[]{1, 0}, routes.get(1).storageIndex());
        // frame 2: acq C=1,T=0 → storage [t=0, c=1]
        assertArrayEquals(new int[]{0, 1}, routes.get(2).storageIndex());
    }

    @Test
    void multiPosition_routesToCorrectPositions() {
        // T=2, P=2, C=2 — from the Python docstring example
        AcquisitionSettings s = AcquisitionSettings.builder()
                .rootPath("test.ome.zarr")
                .dimensions(List.of(
                        Dimension.builder("t").count(2).type(DimensionType.TIME).build(),
                        Dimension.builder("p").positionNames("A1", "B2").build(),
                        Dimension.builder("c").count(2).type(DimensionType.CHANNEL).build(),
                        Dimension.builder("y").count(64).type(DimensionType.SPACE).build(),
                        Dimension.builder("x").count(64).type(DimensionType.SPACE).build()))
                .dtype("uint16")
                .storageOrder("acquisition")
                .build();

        List<FrameRoute> routes = new ArrayList<>();
        for (FrameRoute r : new FrameRouter(s)) routes.add(r);

        // 2T * 2P * 2C = 8 frames
        assertEquals(8, routes.size());

        // Expected sequence (Python docstring):
        // pos=0:A1 idx=(0,0), pos=0:A1 idx=(0,1),
        // pos=1:B2 idx=(0,0), pos=1:B2 idx=(0,1),
        // pos=0:A1 idx=(1,0), pos=0:A1 idx=(1,1),
        // pos=1:B2 idx=(1,0), pos=1:B2 idx=(1,1)
        assertEquals(0, routes.get(0).positionIndex()); assertArrayEquals(new int[]{0, 0}, routes.get(0).storageIndex());
        assertEquals(0, routes.get(1).positionIndex()); assertArrayEquals(new int[]{0, 1}, routes.get(1).storageIndex());
        assertEquals(1, routes.get(2).positionIndex()); assertArrayEquals(new int[]{0, 0}, routes.get(2).storageIndex());
        assertEquals(1, routes.get(3).positionIndex()); assertArrayEquals(new int[]{0, 1}, routes.get(3).storageIndex());
        assertEquals(0, routes.get(4).positionIndex()); assertArrayEquals(new int[]{1, 0}, routes.get(4).storageIndex());
        assertEquals(1, routes.get(6).positionIndex()); assertArrayEquals(new int[]{1, 0}, routes.get(6).storageIndex());
    }

    @Test
    void reset_allowsReIteration() {
        AcquisitionSettings s = simpleTcyx(2, 2);
        FrameRouter router = new FrameRouter(s);
        long count1 = 0; for (FrameRoute ignored : router) count1++;
        long count2 = 0; for (FrameRoute ignored : router) count2++;
        assertEquals(4, count1);
        assertEquals(4, count2);
    }

    @Test
    void noNonFrameDims_twoFrameDimsOnly() {
        // Just Y, X — one frame total, position 0, storage index []
        AcquisitionSettings s = AcquisitionSettings.builder()
                .rootPath("test.ome.zarr")
                .dimensions(List.of(
                        Dimension.builder("y").count(64).type(DimensionType.SPACE).build(),
                        Dimension.builder("x").count(64).type(DimensionType.SPACE).build()))
                .dtype("uint16")
                .build();

        List<FrameRoute> routes = new ArrayList<>();
        for (FrameRoute r : new FrameRouter(s)) routes.add(r);

        assertEquals(1, routes.size());
        assertEquals(0, routes.get(0).positionIndex());
        assertArrayEquals(new int[]{}, routes.get(0).storageIndex());
    }
}

