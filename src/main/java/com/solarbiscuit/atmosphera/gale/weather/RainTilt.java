package com.solarbiscuit.atmosphera.gale.weather;

import com.solarbiscuit.atmosphera.gale.GaleConfig;

/**
 * Gale 0.4.0 rain tilt: vanilla's rain and snow streaks lean with the wind. Render thread only.
 * <p>
 * Vanilla draws one camera-facing quad per column, from the column's rain blocker (or the camera height minus the
 * radius) up to the camera height plus the radius. {@code LevelRendererRainMixin} keeps each quad's bottom where the
 * rain lands and moves every vertex upwind by {@code slope x (height above that bottom)}, so the streaks come in from
 * upwind and still end on the right block. The texture scrolls along the quad, so the drops fall along the slant.
 * <p>
 * The slope follows Gale's lean and gust oscillators (smooth, so gusts swing the rain instead of snapping it) and the
 * biome openness at the camera. Snow falls slower than rain and drifts further: twice the slope.
 */
public final class RainTilt {
    /** Slope (horizontal blocks per block of fall) per unit of wind drive, at 100%. */
    private static final double SLOPE_PER_WIND = 0.45;
    /** Gusts add this share of their drive on top of the steady lean. */
    private static final double GUST_SHARE = 0.4;
    private static final double MAX_RAIN_SLOPE = 0.7;
    private static final double MAX_SNOW_SLOPE = 1.0;

    /** This frame's slopes, as x and z offsets per block of height (pointing upwind). */
    private static double rainX, rainZ, snowX, snowZ;
    /** Set per column while vanilla draws: the active slope, the camera height and the column's rain bottom. */
    private static double slopeX, slopeZ;
    private static double cameraY;
    private static double bottomRel;
    private static boolean active;

    private RainTilt() {
    }

    /**
     * Once per frame, after Gale has stepped its wind oscillators.
     *
     * @param dirX     wind heading x (the direction the wind blows towards)
     * @param dirZ     wind heading z
     * @param lean     the smoothed steady wind drive (about 0.2 calm, 0.5 rain, 0.8+ thunder)
     * @param gust     the smoothed gust drive (0..1)
     * @param openness biome openness at the camera (about 0.35 caves to 1.4 peaks)
     */
    public static void step(float dirX, float dirZ, double lean, double gust, double openness) {
        double amount = GaleConfig.rainTiltOn.get() ? GaleConfig.rainTilt.get() : 0.0;
        double drive = Math.max(0.0, lean) + GUST_SHARE * Math.max(0.0, gust);
        double slope = amount * SLOPE_PER_WIND * drive * Math.max(0.0, openness);
        double rain = Math.min(slope, MAX_RAIN_SLOPE * Math.max(1.0, amount));
        double snow = Math.min(2.0 * slope, MAX_SNOW_SLOPE * Math.max(1.0, amount));
        // Upwind: the streak's top is where the drop came from.
        rainX = -dirX * rain;
        rainZ = -dirZ * rain;
        snowX = -dirX * snow;
        snowZ = -dirZ * snow;
    }

    /** Start of vanilla's rain and snow pass. */
    public static void begin(double camY) {
        cameraY = camY;
        active = rainX != 0.0 || rainZ != 0.0 || snowX != 0.0 || snowZ != 0.0;
        slopeX = slopeZ = 0.0;
        bottomRel = 0.0;
    }

    /** A column's rain bottom (world y), before its quad is drawn. */
    public static void bottom(int y) {
        bottomRel = y - cameraY;
    }

    /** The column is rain (true) or snow (false); called after {@link #bottom}, before its four vertices. */
    public static void precipitation(boolean rain) {
        slopeX = rain ? rainX : snowX;
        slopeZ = rain ? rainZ : snowZ;
    }

    /** x offset of a camera-relative vertex at height {@code yRel}. */
    public static double dx(double yRel) {
        return active ? slopeX * (yRel - bottomRel) : 0.0;
    }

    /** z offset of a camera-relative vertex at height {@code yRel}. */
    public static double dz(double yRel) {
        return active ? slopeZ * (yRel - bottomRel) : 0.0;
    }
}
