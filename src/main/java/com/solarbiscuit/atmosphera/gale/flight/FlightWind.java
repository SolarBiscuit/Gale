package com.solarbiscuit.atmosphera.gale.flight;

/**
 * The arithmetic of the wind on a glider (0.10.0), with no Minecraft types, so it can be tested on its own.
 * <p>
 * Vanilla's glide loses 1% of its horizontal speed a tick to drag, computed against still air. In wind the drag acts
 * on the speed relative to the air, {@code v - W}, so the correction is the drag on the wind alone: each tick
 * {@code dv = 0.01 * strength * W}. Over a glide that settles the ground speed at the airspeed plus the wind: a
 * tailwind adds speed, a headwind costs it, a crosswind drifts the glider sideways. {@code |dv|} is clamped to
 * {@code 0.0015 * strength * max(|v|, 0.5)}, which bounds the steady-state change at about 15% of the airspeed.
 * (The first version pulled {@code v} towards {@code W}, so a tailwind slower than the glider, the usual case,
 * slowed it down.)
 */
public final class FlightWind {
    /** Wind speed at full strength (1.0), in blocks per tick, at the top of the height profile. */
    public static final double MAX_WIND = 0.9;
    /** Height above the surface where the profile reaches 1, in blocks. */
    public static final double PROFILE_HEIGHT = 30.0;
    private static final double LOG_TOP = Math.log(1.0 + PROFILE_HEIGHT);

    private FlightWind() {
    }

    /**
     * The log profile of the wind with height above the ground: 0.5 at the ground, rising steeply near it and slowly
     * after, to 1.0 at 30 blocks and above.
     */
    public static double heightProfile(double heightAboveGround) {
        double h = Math.min(Math.max(heightAboveGround, 0.0), PROFILE_HEIGHT);
        return 0.5 + 0.5 * Math.log(1.0 + h) / LOG_TOP;
    }

    /** The wind's speed in blocks per tick for a wind strength (0..1) at a height above the ground. */
    public static double windSpeed(double strength, double heightAboveGround) {
        return MAX_WIND * strength * heightProfile(heightAboveGround);
    }

    /**
     * The velocity change for one tick.
     *
     * @param vx       horizontal velocity of the glider, blocks per tick
     * @param vz       horizontal velocity of the glider
     * @param wx       wind velocity (direction times {@link #windSpeed}), blocks per tick
     * @param wz       wind velocity
     * @param strength the server's {@code flight_strength}, 0..2
     * @param out      receives dv: {@code out[0]} to add to the x velocity, {@code out[1]} to the z velocity
     */
    public static void nudge(double vx, double vz, double wx, double wz, double strength, double[] out) {
        double k = 0.01 * strength;
        double dx = k * wx;
        double dz = k * wz;
        double limit = 0.0015 * strength * Math.max(Math.sqrt(vx * vx + vz * vz), 0.5);
        double size = Math.sqrt(dx * dx + dz * dz);
        if (size > limit && size > 0.0) {
            double scale = limit / size;
            dx *= scale;
            dz *= scale;
        }
        out[0] = dx;
        out[1] = dz;
    }
}
