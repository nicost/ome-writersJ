# ome-writers (Java)

A Java port of [ome-writers](https://github.com/pymmcore-plus/ome-writers) — a streaming
writer API for multi-dimensional microscopy images in OME-compliant formats. Ported from 
the original Python code (written by Talley Lamber) by Claude code, with the intent to use
it as a dependency in the Java Micro-Manager UI to write OME-Zarr.  For that to work, there 
is a need for a reader(that can operate at the same time as the writer), and also for 
pyramidal storage (to replace NDTiff storage as the only pyramidal storage currently supported 
in Java Micro-Manager).

## About

`ome-writers` provides a unified interface for writing microscopy image data to
OME-compliant formats using a streaming acquisition model: 2D camera frames
arrive one at a time and are written to multi-dimensional arrays with correct
OME metadata.

The core problem it solves:

> Map a **stream of 2D frames** (arriving in acquisition order) to **storage
> locations** in multi-dimensional arrays, while generating **OME-compliant
> metadata**.

This Java port implements the OME-Zarr v0.5 (NGFF) backend only.
The OME-TIFF backend is deferred.

### Attribution

This project is a Java port of the Python library
[ome-writers](https://github.com/pymmcore-plus/ome-writers) by the
[pymmcore-plus](https://github.com/pymmcore-plus) project.
The design — schema, frame router, and backend separation — follows the Python
original closely. See the Python project for full documentation and the
OME-TIFF backend.

## Architecture

```
AcquisitionSettings  -->  FrameRouter  -->  ArrayBackend (OmeZarrBackend)
  (declarative schema)    (acquisition       (writes zarr arrays,
                           order -> storage   generates NGFF metadata)
                           order mapping)
```

**`AcquisitionSettings`** — declarative description of the acquisition: dimensions
(T, C, Z, Y, X, positions, plates), data type, chunking, compression, and metadata.

**`FrameRouter`** — stateful iterator that maps frame numbers to storage indices.
Iterates in acquisition order and emits storage-order coordinates.

**`OmeZarrBackend`** — writes OME-Zarr v0.5 hierarchies using
[zarr-java](https://github.com/zarr-developers/zarr-java). Supports three layouts:
single-position, multi-position (bioformats2raw), and NGFF plate.

**`OMEStream`** — public `AutoCloseable` API wrapping the above. Call `append(frame)`
for each 2D frame in acquisition order.

## Requirements

- **Java 11** (Adoptium / Eclipse Temurin recommended)
- **Maven 3.6+**

## Building

```bash
JAVA_HOME=/path/to/jdk-11 mvn package -DskipTests
```

On Windows with Adoptium JDK 11:

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-11.0.27.6-hotspot"
mvn package -DskipTests
```

## Running the tests

```bash
JAVA_HOME=/path/to/jdk-11 mvn test
```

On Windows:

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-11.0.27.6-hotspot"
mvn test
```

The test suite (38 tests) covers the frame router, coordinate tracker, the Zarr
backend, and the public `OMEStream` API including unbounded dimensions, multi-position,
plate layout, and the async event system.

## Cross-language verification

A Python script validates that files written by Java are readable and correct
from Python (requires zarr >= 3.0 in the Python environment).

First run the fixture test to write the file:

```bash
JAVA_HOME=/path/to/jdk-11 mvn test -Dtest=VerificationFixtureTest
```

Then run the validator from the `ome-writersJ` directory. The example below
borrows the Python environment from a sibling `ome-writers` checkout:

```bash
uv run --directory ../ome-writers python verify_zarr.py
```

Or with any Python environment that has `zarr >= 3.0` installed:

```bash
python verify_zarr.py
```

## Basic usage

```java
AcquisitionSettings settings = AcquisitionSettings.builder()
    .rootPath("output.ome.zarr")
    .dtype("uint16")
    .dimensions(List.of(
        Dimension.builder("t").count(10).type(DimensionType.TIME).unit("second").build(),
        Dimension.builder("c").channelNames("DAPI", "GFP").build(),
        Dimension.builder("y").count(512).type(DimensionType.SPACE).unit("micrometer").scale(0.1).build(),
        Dimension.builder("x").count(512).type(DimensionType.SPACE).unit("micrometer").scale(0.1).build()))
    .overwrite(true)
    .build();

try (OMEStream stream = OMEStream.open(settings)) {
    for (short[] frame : acquireFrames()) {
        stream.append(frame);
    }
}
```

### Supported pixel types

| dtype string       | Java array type |
|--------------------|-----------------|
| `"uint8"` / `"int8"`   | `byte[]`    |
| `"uint16"` / `"int16"` | `short[]`   |
| `"uint32"` / `"int32"` | `int[]`     |
| `"float32"`            | `float[]`   |
| `"float64"`            | `double[]`  |

### Supported layouts

- **Single-position** — multiscales at root; array at `0/`
- **Multi-position (bioformats2raw)** — root has `bioformats2raw.layout=3`;
  each position is a sub-group with multiscales
- **Plate (NGFF)** — plate hierarchy at root; images at `<row>/<col>/<name>/`

### Unbounded first dimension

Set `count(null)` on the first dimension to stream an unknown number of frames.
The array is resized automatically on each write:

```java
Dimension.builder("t").count(null).type(DimensionType.TIME).build()
```

### Frame events

```java
stream.on("coords_expanded", update -> {
    // fired at each new high-water-mark coordinate
    System.out.println("new max coords: " + update);
});

stream.on("coords_changed", update -> {
    // fired on every frame
});
```

## Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| [zarr-java](https://github.com/zarr-developers/zarr-java) | 0.0.4 | Zarr v3 array I/O |
| [ome-xml](https://docs.openmicroscopy.org/ome-model/) | 6.4.0 | OME-XML metadata model |
| [jackson-databind](https://github.com/FasterXML/jackson) | 2.17.1 | JSON serialization for `zarr.json` |
| JUnit Jupiter | 5.10.2 | Testing |
