package org.micromanager.omewriters;

import java.util.HashMap;
import java.util.Map;

/**
 * Unit conversion between NGFF-compliant names and OME-XML symbols.
 *
 * <p>Users must supply NGFF-compliant unit names (e.g. {@code "micrometer"},
 * {@code "millisecond"}) in {@link Dimension#getUnit()}. This class converts
 * to/from OME-XML abbreviations when writing metadata.
 */
public final class UnitConverter {

    /** NGFF spatial unit name → OME-XML length symbol */
    public static final Map<String, String> NGFF_TO_OME_LENGTH = new HashMap<>();
    /** OME-XML length symbol (and common aliases) → NGFF spatial unit name */
    public static final Map<String, String> ANY_LENGTH_TO_NGFF = new HashMap<>();

    /** NGFF temporal unit name → OME-XML time symbol */
    public static final Map<String, String> NGFF_TO_OME_TIME = new HashMap<>();
    /** OME-XML time symbol (and common aliases) → NGFF temporal unit name */
    public static final Map<String, String> ANY_TIME_TO_NGFF = new HashMap<>();

    static {
        // Spatial
        NGFF_TO_OME_LENGTH.put("angstrom",    "Å");
        NGFF_TO_OME_LENGTH.put("attometer",   "am");
        NGFF_TO_OME_LENGTH.put("centimeter",  "cm");
        NGFF_TO_OME_LENGTH.put("decimeter",   "dm");
        NGFF_TO_OME_LENGTH.put("exameter",    "Em");
        NGFF_TO_OME_LENGTH.put("femtometer",  "fm");
        NGFF_TO_OME_LENGTH.put("foot",        "ft");
        NGFF_TO_OME_LENGTH.put("gigameter",   "Gm");
        NGFF_TO_OME_LENGTH.put("hectometer",  "hm");
        NGFF_TO_OME_LENGTH.put("inch",        "in");
        NGFF_TO_OME_LENGTH.put("kilometer",   "km");
        NGFF_TO_OME_LENGTH.put("megameter",   "Mm");
        NGFF_TO_OME_LENGTH.put("meter",       "m");
        NGFF_TO_OME_LENGTH.put("micrometer",  "µm");
        NGFF_TO_OME_LENGTH.put("mile",        "mi");
        NGFF_TO_OME_LENGTH.put("millimeter",  "mm");
        NGFF_TO_OME_LENGTH.put("nanometer",   "nm");
        NGFF_TO_OME_LENGTH.put("parsec",      "pc");
        NGFF_TO_OME_LENGTH.put("petameter",   "Pm");
        NGFF_TO_OME_LENGTH.put("picometer",   "pm");
        NGFF_TO_OME_LENGTH.put("terameter",   "Tm");
        NGFF_TO_OME_LENGTH.put("yard",        "yd");
        NGFF_TO_OME_LENGTH.put("yoctometer",  "ym");
        NGFF_TO_OME_LENGTH.put("yottameter",  "Ym");
        NGFF_TO_OME_LENGTH.put("zeptometer",  "zm");
        NGFF_TO_OME_LENGTH.put("zettameter",  "Zm");

        // Reverse spatial
        for (Map.Entry<String, String> e : NGFF_TO_OME_LENGTH.entrySet()) {
            ANY_LENGTH_TO_NGFF.put(e.getValue(), e.getKey());
            ANY_LENGTH_TO_NGFF.put(e.getKey(), e.getKey());
        }
        ANY_LENGTH_TO_NGFF.put("um",     "micrometer");
        ANY_LENGTH_TO_NGFF.put("u",      "micrometer");
        ANY_LENGTH_TO_NGFF.put("micron", "micrometer");
        ANY_LENGTH_TO_NGFF.put("A",      "angstrom");

        // Temporal
        NGFF_TO_OME_TIME.put("attosecond",   "as");
        NGFF_TO_OME_TIME.put("centisecond",  "cs");
        NGFF_TO_OME_TIME.put("day",          "d");
        NGFF_TO_OME_TIME.put("decisecond",   "ds");
        NGFF_TO_OME_TIME.put("exasecond",    "Es");
        NGFF_TO_OME_TIME.put("femtosecond",  "fs");
        NGFF_TO_OME_TIME.put("gigasecond",   "Gs");
        NGFF_TO_OME_TIME.put("hectosecond",  "hs");
        NGFF_TO_OME_TIME.put("hour",         "h");
        NGFF_TO_OME_TIME.put("kilosecond",   "ks");
        NGFF_TO_OME_TIME.put("megasecond",   "Ms");
        NGFF_TO_OME_TIME.put("microsecond",  "µs");
        NGFF_TO_OME_TIME.put("millisecond",  "ms");
        NGFF_TO_OME_TIME.put("minute",       "min");
        NGFF_TO_OME_TIME.put("nanosecond",   "ns");
        NGFF_TO_OME_TIME.put("petasecond",   "Ps");
        NGFF_TO_OME_TIME.put("picosecond",   "ps");
        NGFF_TO_OME_TIME.put("second",       "s");
        NGFF_TO_OME_TIME.put("terasecond",   "Ts");
        NGFF_TO_OME_TIME.put("yoctosecond",  "ys");
        NGFF_TO_OME_TIME.put("yottasecond",  "Ys");
        NGFF_TO_OME_TIME.put("zeptosecond",  "zs");
        NGFF_TO_OME_TIME.put("zettasecond",  "Zs");

        // Reverse temporal
        for (Map.Entry<String, String> e : NGFF_TO_OME_TIME.entrySet()) {
            ANY_TIME_TO_NGFF.put(e.getValue(), e.getKey());
            ANY_TIME_TO_NGFF.put(e.getKey(), e.getKey());
        }
        ANY_TIME_TO_NGFF.put("us",     "microsecond");
        ANY_TIME_TO_NGFF.put("usec",   "microsecond");
        ANY_TIME_TO_NGFF.put("µsec", "microsecond");
        ANY_TIME_TO_NGFF.put("msec",   "millisecond");
        ANY_TIME_TO_NGFF.put("sec",    "second");
    }

    private UnitConverter() {}

    /**
     * Infer {@link DimensionType} from a unit string; returns {@code null} when
     * the unit is not a recognized spatial or temporal unit.
     */
    public static DimensionType inferDimTypeFromUnit(String unit) {
        if (unit == null) return null;
        String key = unit.length() > 2 ? unit.toLowerCase() : unit;
        if (ANY_LENGTH_TO_NGFF.containsKey(key)) return DimensionType.SPACE;
        if (ANY_TIME_TO_NGFF.containsKey(key))   return DimensionType.TIME;
        return null;
    }

    /**
     * Normalize any recognized unit string to its NGFF-compliant form.
     * For space/time dims this validates the unit; for others it passes through as-is.
     */
    public static String castUnitToNgff(String unit, DimensionType dimType) {
        String key = unit.length() > 2 ? unit.toLowerCase() : unit;
        if (dimType == DimensionType.SPACE) {
            String ngff = ANY_LENGTH_TO_NGFF.get(key);
            if (ngff == null) throw new IllegalArgumentException(
                    "Unrecognized unit of length: '" + unit + "'. " +
                    "Recognized units: " + ANY_LENGTH_TO_NGFF.keySet());
            return ngff;
        }
        if (dimType == DimensionType.TIME) {
            String ngff = ANY_TIME_TO_NGFF.get(key);
            if (ngff == null) throw new IllegalArgumentException(
                    "Unrecognized unit of time: '" + unit + "'. " +
                    "Recognized units: " + ANY_TIME_TO_NGFF.keySet());
            return ngff;
        }
        return unit;
    }

    /** Convert an NGFF unit name to its OME-XML symbol, or {@code null} if unknown. */
    public static String ngffToOmeUnit(String unit) {
        String sym = NGFF_TO_OME_LENGTH.get(unit);
        if (sym != null) return sym;
        return NGFF_TO_OME_TIME.get(unit);
    }
}

