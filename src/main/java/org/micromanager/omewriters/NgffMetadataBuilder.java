package org.micromanager.omewriters;

import java.util.*;

/** Builds NGFF v0.5 attribute maps for OME-Zarr groups. */
public final class NgffMetadataBuilder {

    private NgffMetadataBuilder() {}

    /**
     * Build the full attributes map for an OME-Zarr v0.5 image group.
     * The result is suitable for {@code Group.create(handle, attrs)} or
     * {@code Group.setAttributes(attrs)}.
     *
     * @param storageDims ordered storage dimensions (no position dim)
     * @param dtype       numpy-style dtype string, e.g. {@code "uint16"}
     */
    public static Map<String, Object> buildImageAttributes(
            List<Dimension> storageDims, String dtype) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("multiscales", buildMultiscales(storageDims));
        Map<String, Object> omero = buildOmero(storageDims, dtype);
        if (omero != null) {
            attrs.put("omero", omero);
        }
        return attrs;
    }

    /** Attributes for the bf2raw layout root group. */
    public static Map<String, Object> buildBf2RawRootAttributes() {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("bioformats2raw.layout", 3);
        return attrs;
    }

    /** Attributes for a plate root group. */
    public static Map<String, Object> buildPlateAttributes(Plate plate,
            List<Position> positions) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String r : plate.getRowNames()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", r);
            rows.add(row);
        }
        List<Map<String, Object>> cols = new ArrayList<>();
        for (String c : plate.getColumnNames()) {
            Map<String, Object> col = new LinkedHashMap<>();
            col.put("name", c);
            cols.add(col);
        }

        Set<String> seenWells = new LinkedHashSet<>();
        List<Map<String, Object>> wells = new ArrayList<>();
        for (Position pos : positions) {
            String row = pos.getPlateRow();
            String col = pos.getPlateColumn();
            if (row == null || col == null) continue;
            String wellKey = row + "/" + col;
            if (!seenWells.add(wellKey)) continue;
            Map<String, Object> well = new LinkedHashMap<>();
            well.put("path", wellKey);
            well.put("rowIndex", plate.getRowNames().indexOf(row));
            well.put("columnIndex", plate.getColumnNames().indexOf(col));
            wells.add(well);
        }

        Map<String, Object> plateDef = new LinkedHashMap<>();
        if (plate.getName() != null) plateDef.put("name", plate.getName());
        plateDef.put("version", "0.5");
        plateDef.put("rows", rows);
        plateDef.put("columns", cols);
        plateDef.put("wells", wells);

        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("plate", plateDef);
        return attrs;
    }

    /** Attributes for a well group (lists contained images). */
    public static Map<String, Object> buildWellAttributes(List<String> imagePaths) {
        List<Map<String, Object>> images = new ArrayList<>();
        for (String p : imagePaths) {
            Map<String, Object> img = new LinkedHashMap<>();
            img.put("path", p);
            images.add(img);
        }
        Map<String, Object> well = new LinkedHashMap<>();
        well.put("version", "0.5");
        well.put("images", images);
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("well", well);
        return attrs;
    }

    // -------------------------------------------------------------------------

    private static List<Map<String, Object>> buildMultiscales(List<Dimension> dims) {
        Map<String, Object> ms = new LinkedHashMap<>();
        ms.put("version", "0.5");
        ms.put("axes", buildAxes(dims));
        ms.put("datasets", buildDatasets(dims));
        ms.put("coordinateTransformations", buildIdentityScale(dims.size()));
        return Collections.singletonList(ms);
    }

    private static List<Map<String, Object>> buildAxes(List<Dimension> dims) {
        List<Map<String, Object>> axes = new ArrayList<>();
        for (Dimension d : dims) {
            Map<String, Object> axis = new LinkedHashMap<>();
            axis.put("name", d.getName());
            DimensionType type = d.getType();
            if (type != null && type != DimensionType.OTHER && type != DimensionType.POSITION) {
                axis.put("type", type.name().toLowerCase());
            }
            if (d.getUnit() != null) {
                axis.put("unit", d.getUnit());
            }
            axes.add(axis);
        }
        return axes;
    }

    private static List<Map<String, Object>> buildDatasets(List<Dimension> dims) {
        Map<String, Object> dataset = new LinkedHashMap<>();
        dataset.put("path", "0");
        dataset.put("coordinateTransformations", buildScaleTransform(dims));
        return Collections.singletonList(dataset);
    }

    private static List<Map<String, Object>> buildScaleTransform(List<Dimension> dims) {
        Map<String, Object> xform = new LinkedHashMap<>();
        xform.put("type", "scale");
        List<Double> scales = new ArrayList<>();
        for (Dimension d : dims) {
            scales.add(d.getScale() != null ? d.getScale() : 1.0);
        }
        xform.put("scale", scales);
        return Collections.singletonList(xform);
    }

    private static List<Map<String, Object>> buildIdentityScale(int ndims) {
        Map<String, Object> xform = new LinkedHashMap<>();
        xform.put("type", "scale");
        List<Double> ones = new ArrayList<>(Collections.nCopies(ndims, 1.0));
        xform.put("scale", ones);
        return Collections.singletonList(xform);
    }

    private static Map<String, Object> buildOmero(List<Dimension> dims, String dtype) {
        Dimension channelDim = null;
        for (Dimension d : dims) {
            if (d.isChannelDim()) {
                channelDim = d;
                break;
            }
        }
        if (channelDim == null || channelDim.getCoords() == null) {
            return null;
        }

        long[] range = dtypeRange(dtype);
        List<Map<String, Object>> channels = new ArrayList<>();
        for (Object coord : channelDim.getCoords()) {
            if (!(coord instanceof Channel)) continue;
            Channel ch = (Channel) coord;
            Map<String, Object> chanMap = new LinkedHashMap<>();
            chanMap.put("label", ch.getName());
            String hex = ch.getColor() != null
                    ? ch.getColor().replaceAll("^#", "").toUpperCase() : "FFFFFF";
            chanMap.put("color", hex);
            Map<String, Object> window = new LinkedHashMap<>();
            window.put("min", range[0]);
            window.put("max", range[1]);
            window.put("start", range[0]);
            window.put("end", range[1]);
            chanMap.put("window", window);
            chanMap.put("active", true);
            channels.add(chanMap);
        }
        if (channels.isEmpty()) return null;

        Map<String, Object> omero = new LinkedHashMap<>();
        omero.put("channels", channels);
        return omero;
    }

    private static long[] dtypeRange(String dtype) {
        if (dtype == null) return new long[]{0, 65535};
        switch (dtype) {
            case "uint8":  return new long[]{0L, 255L};
            case "uint16": return new long[]{0L, 65535L};
            case "uint32": return new long[]{0L, 4294967295L};
            case "int8":   return new long[]{-128L, 127L};
            case "int16":  return new long[]{-32768L, 32767L};
            case "int32":  return new long[]{-2147483648L, 2147483647L};
            default:       return new long[]{0L, 65535L};
        }
    }
}
