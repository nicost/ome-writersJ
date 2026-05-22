package org.micromanager.omewriters;

/**
 * A single channel in an acquisition.
 *
 * <p>Used in {@link Dimension#getCoords()} for dimensions of type
 * {@link DimensionType#CHANNEL} when wavelength or colour metadata is needed.
 * Metadata is propagated to the {@code omero.channels} block in OME-Zarr.
 */
public final class Channel {

    private final String name;
    private final Integer excitationWavelengthNm;
    private final Integer emissionWavelengthNm;
    private final String fluorophore;
    /** RGB hex string e.g. {@code "#FF0000"}, or {@code null}. */
    private final String color;

    private Channel(Builder b) {
        if (b.name == null || b.name.isEmpty())
            throw new IllegalArgumentException("Channel name must not be empty");
        this.name = b.name;
        this.excitationWavelengthNm = b.excitationWavelengthNm;
        this.emissionWavelengthNm   = b.emissionWavelengthNm;
        this.fluorophore            = b.fluorophore;
        this.color                  = b.color;
    }

    /** Convenience constructor for name-only channels. */
    public Channel(String name) {
        this(new Builder(name));
    }

    public String getName()                        { return name; }
    public Integer getExcitationWavelengthNm()     { return excitationWavelengthNm; }
    public Integer getEmissionWavelengthNm()        { return emissionWavelengthNm; }
    public String getFluorophore()                 { return fluorophore; }
    public String getColor()                       { return color; }

    @Override
    public String toString() {
        return "Channel{name='" + name + "'}";
    }

    public static Builder builder(String name) { return new Builder(name); }

    public static final class Builder {
        private final String name;
        private Integer excitationWavelengthNm;
        private Integer emissionWavelengthNm;
        private String fluorophore;
        private String color;

        public Builder(String name) { this.name = name; }

        public Builder excitationWavelengthNm(int nm) { this.excitationWavelengthNm = nm; return this; }
        public Builder emissionWavelengthNm(int nm)    { this.emissionWavelengthNm = nm;   return this; }
        public Builder fluorophore(String f)           { this.fluorophore = f;             return this; }
        /** Hex RGB string, e.g. {@code "#00FF00"}. */
        public Builder color(String hex)               { this.color = hex;                 return this; }

        public Channel build() { return new Channel(this); }
    }
}

