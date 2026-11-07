package com.screenshotutilities.image;

/**
 * The colours a filter can keep, as a hue range in degrees with a soft edge on each side.
 */
public enum KeepColour {
    RED("red", 0.0f, 14.0f, 8.0f),
    ORANGE("orange", 30.0f, 12.0f, 7.0f),
    YELLOW("yellow", 55.0f, 15.0f, 8.0f),
    GREEN("green", 110.0f, 35.0f, 10.0f),
    BLUE("blue", 215.0f, 35.0f, 10.0f),
    PURPLE("purple", 275.0f, 18.0f, 8.0f),
    PINK("pink", 325.0f, 18.0f, 8.0f);

    private final String id;
    private final float centerDegrees;
    private final float halfWidthDegrees;
    private final float edgeDegrees;

    KeepColour(String id, float centerDegrees, float halfWidthDegrees, float edgeDegrees) {
        this.id = id;
        this.centerDegrees = centerDegrees;
        this.halfWidthDegrees = halfWidthDegrees;
        this.edgeDegrees = edgeDegrees;
    }

    public String translationKey() {
        return "screenshotutilities.filter." + id;
    }

    /** How much of this colour a hue counts as, from 0 to 1. Handles red wrapping across 0 degrees. */
    float hueMatch(float hue) {
        float dist = Math.abs(hue - centerDegrees);
        if (dist > 180.0f) {
            dist = 360.0f - dist;
        }
        if (dist <= halfWidthDegrees) {
            return 1.0f;
        }
        return Math.max(0.0f, 1.0f - (dist - halfWidthDegrees) / edgeDegrees);
    }
}
