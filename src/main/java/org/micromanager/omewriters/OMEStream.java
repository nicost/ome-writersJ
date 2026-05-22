package org.micromanager.omewriters;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Public streaming API for writing OME-Zarr data frame by frame.
 *
 * <p>Use {@link #open(AcquisitionSettings)} to create a stream, then call
 * {@link #append} for each frame in acquisition order.  The stream implements
 * {@link AutoCloseable} and should be used in a try-with-resources block:
 *
 * <pre>{@code
 * AcquisitionSettings settings = AcquisitionSettings.builder()
 *     .rootPath("output.ome.zarr")
 *     .dimensions(List.of(
 *         Dimension.builder("t").count(10).type(DimensionType.TIME).build(),
 *         Dimension.builder("y").count(512).type(DimensionType.SPACE).build(),
 *         Dimension.builder("x").count(512).type(DimensionType.SPACE).build()))
 *     .dtype("uint16")
 *     .build();
 *
 * try (OMEStream stream = OMEStream.open(settings)) {
 *     for (short[] frame : acquireFrames()) {
 *         stream.append(frame);
 *     }
 * }
 * }</pre>
 *
 * <p>The {@code pixelData} argument to {@link #append} must be a Java primitive
 * array whose element type matches the configured dtype:
 * <ul>
 *   <li>{@code "uint8" / "int8"}   → {@code byte[]}</li>
 *   <li>{@code "uint16" / "int16"} → {@code short[]}</li>
 *   <li>{@code "uint32" / "int32"} → {@code int[]}</li>
 *   <li>{@code "float32"}          → {@code float[]}</li>
 *   <li>{@code "float64"}          → {@code double[]}</li>
 * </ul>
 */
public final class OMEStream implements AutoCloseable {

    private final AcquisitionSettings settings;
    private final ArrayBackend backend;
    private final FrameRouter router;
    private final Integer expectedFrames;

    private int framesWritten;
    private volatile boolean closed;

    // Lazy-initialised event system
    private CoordTracker coordTracker;
    private final Map<String, List<Consumer<CoordUpdate>>> eventHandlers = new LinkedHashMap<>();
    private ExecutorService callbackExecutor;

    // -------------------------------------------------------------------------
    // Construction (package-private; users call open())
    // -------------------------------------------------------------------------

    OMEStream(AcquisitionSettings settings, ArrayBackend backend, FrameRouter router) {
        this.settings       = settings;
        this.backend        = backend;
        this.router         = router;
        this.expectedFrames = settings.getNumFrames();
        this.framesWritten  = 0;
        this.closed         = false;
    }

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /**
     * Create and open a stream backed by an {@link OmeZarrBackend}.
     *
     * @param settings fully configured acquisition settings
     * @return an open {@link OMEStream} ready for writing
     * @throws Exception if the backend cannot be prepared (e.g. disk error)
     */
    public static OMEStream open(AcquisitionSettings settings) throws Exception {
        OmeZarrBackend backend = new OmeZarrBackend();
        backend.prepare(settings);
        FrameRouter router = new FrameRouter(settings);
        return new OMEStream(settings, backend, router);
    }

    /**
     * Create a stream with a custom backend (for testing or alternative backends).
     *
     * @param settings fully configured acquisition settings
     * @param backend  prepared backend (caller must call {@code backend.prepare()} first)
     * @return an open {@link OMEStream} ready for writing
     */
    public static OMEStream withBackend(AcquisitionSettings settings, ArrayBackend backend) {
        return new OMEStream(settings, backend, new FrameRouter(settings));
    }

    // -------------------------------------------------------------------------
    // Core write API
    // -------------------------------------------------------------------------

    /**
     * Write the next frame in acquisition order.
     *
     * @param pixelData primitive array matching the stream's configured dtype
     * @throws IndexOutOfBoundsException if all expected frames have already been written
     * @throws Exception                 if the underlying write fails
     */
    public void append(Object pixelData) throws Exception {
        checkOpen();
        if (!router.hasNext()) {
            throwOverflow("append", 1);
        }
        FrameRoute route = router.next();
        backend.writeFrame(route.positionIndex(), route.storageIndex(), pixelData);
        framesWritten++;
        fireCoordEvents(false);
    }

    /**
     * Skip one frame without writing any data.
     *
     * @throws IndexOutOfBoundsException if skipping would exceed total expected frames
     * @throws Exception                 if the underlying advance fails
     */
    public void skip() throws Exception {
        skip(1);
    }

    /**
     * Skip {@code frames} frames without writing data.
     *
     * @param frames number of frames to skip (must be &gt; 0)
     * @throws IllegalArgumentException  if {@code frames} &le; 0
     * @throws IndexOutOfBoundsException if skipping would exceed total expected frames
     * @throws Exception                 if the underlying advance fails
     */
    public void skip(int frames) throws Exception {
        checkOpen();
        if (frames <= 0) {
            throw new IllegalArgumentException("frames must be positive, got: " + frames);
        }
        List<int[]> indices = new ArrayList<>(frames);
        for (int i = 0; i < frames; i++) {
            if (!router.hasNext()) {
                throwOverflow("skip", frames);
            }
            FrameRoute route = router.next();
            int[] entry = new int[1 + route.storageIndex().length];
            entry[0] = route.positionIndex();
            System.arraycopy(route.storageIndex(), 0, entry, 1, route.storageIndex().length);
            indices.add(entry);
        }
        backend.advance(indices);
        framesWritten += frames;
        fireCoordEventsSkip(frames);
    }

    // -------------------------------------------------------------------------
    // Event system
    // -------------------------------------------------------------------------

    /**
     * Register a callback for frame events.
     *
     * <p>Callbacks are dispatched asynchronously in a background thread pool
     * so they never block the acquisition thread.
     *
     * @param event    {@code "coords_expanded"} (fired at every new high water mark) or
     *                 {@code "coords_changed"} (fired on every frame)
     * @param handler  callback that receives a {@link CoordUpdate}; must be thread-safe
     * @throws IllegalArgumentException if {@code event} is not a recognised name
     */
    public void on(String event, Consumer<CoordUpdate> handler) {
        if (!"coords_expanded".equals(event) && !"coords_changed".equals(event)) {
            throw new IllegalArgumentException(
                    "Unknown event: '" + event + "'. "
                    + "Use \"coords_expanded\" or \"coords_changed\".");
        }
        if (coordTracker == null) {
            coordTracker = CoordTracker.create(settings, framesWritten);
        }
        if (callbackExecutor == null) {
            callbackExecutor = Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "ome-stream-events");
                t.setDaemon(true);
                return t;
            });
        }
        eventHandlers.computeIfAbsent(event, k -> new ArrayList<>()).add(handler);
        if ("coords_changed".equals(event)) {
            coordTracker.setNeedsCurrentIndices(true);
        }
    }

    // -------------------------------------------------------------------------
    // AutoCloseable
    // -------------------------------------------------------------------------

    @Override
    public void close() throws Exception {
        if (closed) return;
        closed = true;
        try {
            backend.close();
        } finally {
            if (callbackExecutor != null) {
                callbackExecutor.shutdown();
                callbackExecutor = null;
            }
        }
    }

    /** Returns {@code true} if {@link #close()} has been called. */
    public boolean isClosed() {
        return closed;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("Stream is already closed.");
        }
    }

    private void throwOverflow(String operation, int requestedFrames) {
        String detail = requestedFrames > 1 ? " (tried to skip " + requestedFrames + ")" : "";
        if (expectedFrames != null) {
            throw new IndexOutOfBoundsException(
                    "Cannot " + operation + ": would exceed total of " + expectedFrames
                    + " frames" + detail + ". Check AcquisitionSettings.dimensions. "
                    + "For an unbounded stream set the first dimension count to null.");
        }
        throw new IndexOutOfBoundsException(
                "Cannot " + operation + ": iteration finished unexpectedly.");
    }

    private void fireCoordEvents(boolean isSkip) {
        if (coordTracker == null) return;
        CoordUpdate update = coordTracker.update();
        if (update != null) dispatchCoordEvents(update);
    }

    private void fireCoordEventsSkip(int frames) {
        if (coordTracker == null) return;
        CoordUpdate update = coordTracker.skip(frames);
        if (update != null) dispatchCoordEvents(update);
    }

    private void dispatchCoordEvents(CoordUpdate update) {
        if (callbackExecutor == null) return;

        List<Consumer<CoordUpdate>> changedHandlers = eventHandlers.get("coords_changed");
        if (changedHandlers != null) {
            for (Consumer<CoordUpdate> h : changedHandlers) {
                callbackExecutor.submit(() -> h.accept(update));
            }
        }
        if (update.isHighWaterMark()) {
            List<Consumer<CoordUpdate>> expandedHandlers = eventHandlers.get("coords_expanded");
            if (expandedHandlers != null) {
                for (Consumer<CoordUpdate> h : expandedHandlers) {
                    callbackExecutor.submit(() -> h.accept(update));
                }
            }
        }
    }
}
