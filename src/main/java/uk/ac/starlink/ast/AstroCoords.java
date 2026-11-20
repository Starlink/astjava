package uk.ac.starlink.ast;

/**
 * Represents an {@code AstroCoords} element from the Space-Time Coordinate (STC) metadata. This
 * class is used in place of AST's {@link KeyMap} for user-visible cases where the KeyMap's purpose
 * is to represent an {@code AstroCoords} element, since it is easier for programmers and more
 * typesafe. Note however that it does not have all the complexity of an STC {@code AstroCoords}
 * element, only those parts which are required for its use with the AST {@link Stc} classes.
 *
 * <p>An AstroCoords holds the arrays and Regions it is given, not copies of them; an {@link Stc}
 * made from it holds copies.
 *
 * <p>Ported from jniast's {@code AstroCoords}.
 */
public class AstroCoords {

    private String[] name;
    private Region value;
    private Region error;
    private Region resolution;
    private Region size;
    private Region pixSize;

    /** Constructor. */
    public AstroCoords() {}

    /**
     * Constructor which allows specification of axis names.
     *
     * @param axisNames array of names for each axis in the coordinate system of the corresponding
     *     {@code Region}
     */
    public AstroCoords(String[] axisNames) {
        this.name = axisNames;
    }

    /**
     * Sets the axis names.
     *
     * @param axisNames array of names for each axis in the coordinate system of the corresponding
     *     {@code Region}
     */
    public void setName(String[] axisNames) {
        this.name = axisNames;
    }

    /**
     * Returns the axis names.
     *
     * @return array of names for each axis in the coordinate system of the corresponding {@code
     *     Region}
     */
    public String[] getName() {
        return name;
    }

    /**
     * Sets the value of this coordinate.
     *
     * @param value value region
     */
    public void setValue(Region value) {
        this.value = value;
    }

    /**
     * Returns the value of this coordinate.
     *
     * @return value region
     */
    public Region getValue() {
        return value;
    }

    /**
     * Sets the error region for this coordinate.
     *
     * @param error error region
     */
    public void setError(Region error) {
        this.error = error;
    }

    /**
     * Returns the error region for this coordinate.
     *
     * @return error region
     */
    public Region getError() {
        return error;
    }

    /**
     * Sets the resolution region for this coordinate.
     *
     * @param resolution resolution region
     */
    public void setResolution(Region resolution) {
        this.resolution = resolution;
    }

    /**
     * Returns the resolution region for this coordinate.
     *
     * @return resolution region
     */
    public Region getResolution() {
        return resolution;
    }

    /**
     * Sets the size region for this coordinate.
     *
     * @param size size region
     */
    public void setSize(Region size) {
        this.size = size;
    }

    /**
     * Returns the size region for this coordinate.
     *
     * @return size region
     */
    public Region getSize() {
        return size;
    }

    /**
     * Sets the pixel size for this coordinate.
     *
     * @param pixSize pixel size
     */
    public void setPixSize(Region pixSize) {
        this.pixSize = pixSize;
    }

    /**
     * Returns the pixel size for this coordinate.
     *
     * @return pixel size
     */
    public Region getPixSize() {
        return pixSize;
    }
}
