package org.micromanager.omewriters;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Writes a deterministic fixture to target/verify-fixture.ome.zarr for
 * cross-language validation with verify_zarr.py (Python zarr 3.x).
 *
 * Fixture: TCYX, t=3 (unit=second, scale=2.0), c=2 (DAPI/GFP),
 * y=8 (unit=micrometer, scale=0.1), x=8 (unit=micrometer, scale=0.1).
 * Pixel value at frame (t, c): all pixels = t*10 + c  (cast to short).
 */
class VerificationFixtureTest {

    private static final int H = 8, W = 8;

    @Test
    void writeVerificationFixture() throws Exception {
        Path root = Paths.get("target", "verify-fixture.ome.zarr").toAbsolutePath();

        AcquisitionSettings settings = AcquisitionSettings.builder()
                .rootPath(root.toString())
                .dtype("uint16")
                .dimensions(List.of(
                        Dimension.builder("t")
                                .count(3)
                                .type(DimensionType.TIME)
                                .unit("second")
                                .scale(2.0)
                                .build(),
                        Dimension.builder("c")
                                .coords(List.of(
                                        Channel.builder("DAPI").color("#0000FF").build(),
                                        Channel.builder("GFP").color("#00FF00").build()))
                                .build(),
                        Dimension.builder("y")
                                .count(H)
                                .type(DimensionType.SPACE)
                                .unit("micrometer")
                                .scale(0.1)
                                .build(),
                        Dimension.builder("x")
                                .count(W)
                                .type(DimensionType.SPACE)
                                .unit("micrometer")
                                .scale(0.1)
                                .build()))
                .overwrite(true)
                .build();

        try (OMEStream stream = OMEStream.open(settings)) {
            for (int t = 0; t < 3; t++) {
                for (int c = 0; c < 2; c++) {
                    short val = (short) (t * 10 + c);
                    short[] frame = new short[H * W];
                    java.util.Arrays.fill(frame, val);
                    stream.append(frame);
                }
            }
        }

        System.out.println("Fixture written to: " + root);
    }
}
