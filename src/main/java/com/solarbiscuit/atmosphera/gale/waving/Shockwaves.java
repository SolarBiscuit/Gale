package com.solarbiscuit.atmosphera.gale.waving;

import net.minecraft.world.phys.Vec3;

/**
 * Gale 0.6.0 explosion shockwaves: the last {@link #MAX} explosions the client was told about, each sending a ring
 * outward that flattens plants and shakes leaves for {@link #LIFE_SECONDS}. Fixed arrays, no allocation; client
 * thread only (packets are handled on it, frames are drawn on it).
 * <p>
 * Ages run on real time, not the world clock, so the wave also plays out while the lib's dev clock override holds
 * the world clock for test shots.
 */
public final class Shockwaves {
    public static final int MAX = 4;
    private static final double LIFE_SECONDS = 1.2;
    /** The ring stops at 2.5 x the explosion power (TNT 4: 10 blocks), at most this far (the packing needs < 32). */
    private static final double MAX_RADIUS = 24.0;

    private static final double[] X = new double[MAX];
    private static final double[] Y = new double[MAX];
    private static final double[] Z = new double[MAX];
    private static final double[] RADIUS = new double[MAX];
    private static final long[] START = new long[MAX];
    private static int next;

    /** Per frame: the live waves, packed for the shader (camera-relative). */
    private static final float[] OUT = new float[MAX * 4];
    static int count;

    private Shockwaves() {
    }

    /** An explosion at (x, y, z) with this power (client thread, from the explosion packet). */
    public static void add(double x, double y, double z, float power) {
        double radius = Math.min(MAX_RADIUS, 2.5 * Math.max(0.0, power));
        if (radius < 1.0) {
            return;
        }
        int i = next;
        next = (next + 1) % MAX;
        X[i] = x;
        Y[i] = y;
        Z[i] = z;
        RADIUS[i] = radius;
        START[i] = System.nanoTime();
    }

    /** Render thread, once per frame: packs the live waves into the first {@code count} slots. */
    static void step(boolean enabled, Vec3 camera) {
        count = 0;
        if (!enabled) {
            return;
        }
        long now = System.nanoTime();
        for (int i = 0; i < MAX; i++) {
            if (START[i] == 0L) {
                continue;
            }
            double t = (now - START[i]) / 1.0e9 / LIFE_SECONDS;
            if (t < 0.0 || t >= 1.0) {
                continue;
            }
            double finalRadius = Math.floor(RADIUS[i]);
            double front = Math.min(31.9, RADIUS[i] * Math.sqrt(t));
            int o = count * 4;
            OUT[o] = (float) (X[i] - camera.x);
            OUT[o + 1] = (float) (Y[i] - camera.y);
            OUT[o + 2] = (float) (Z[i] - camera.z);
            OUT[o + 3] = (float) (finalRadius * 32.0 + front);
            count++;
        }
    }

    /** Frame slot {@code gale_Blast<i>}: xyz centre relative to the camera, w floor(R) * 32 + front (0 = none). */
    static void write(int i, float[] out) {
        if (i >= count) {
            out[0] = out[1] = out[2] = out[3] = 0f;
            return;
        }
        System.arraycopy(OUT, i * 4, out, 0, 4);
    }
}
