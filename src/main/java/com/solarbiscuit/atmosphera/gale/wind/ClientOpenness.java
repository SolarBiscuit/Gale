package com.solarbiscuit.atmosphera.gale.wind;

import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

/**
 * Client only (0.9.0, split out of {@link GaleWind}, which now also runs on a dedicated server): the camera's biome
 * openness, eased over a few seconds so biome borders do not snap the sway amount. It keeps frame-to-frame state and
 * reads the wall clock, which the server path has no use for. The server and every other caller use the unsmoothed
 * {@link GaleWind#openness} per position.
 * <p>
 * Render thread only.
 */
public final class ClientOpenness {
    private static float cameraOpenness = 1f;
    private static long lastCameraNanos;
    private static Level lastCameraLevel;
    private static double lastCameraX;
    private static double lastCameraY;
    private static double lastCameraZ;
    /** A camera move longer than this in one frame is a teleport: the openness snaps instead of easing (0.1.9). */
    private static final double SNAP_DISTANCE_SQ = 32.0 * 32.0;

    private ClientOpenness() {
    }

    /**
     * Once per frame: openness at the camera, eased over a few seconds (gale_Wave.z). A new level (join, dimension
     * change) or a camera jump over 32 blocks (teleport, spectator switch) snaps to the new value: easing there only
     * showed the wrong sway for a few seconds.
     */
    public static float update(Level level, double x, double y, double z) {
        float target = GaleWind.INSTANCE.openness(level, Mth.floor(x), Mth.floor(y), Mth.floor(z));
        long now = System.nanoTime();
        double dx = x - lastCameraX;
        double dy = y - lastCameraY;
        double dz = z - lastCameraZ;
        boolean jump = level != lastCameraLevel || dx * dx + dy * dy + dz * dz > SNAP_DISTANCE_SQ;
        lastCameraLevel = level;
        lastCameraX = x;
        lastCameraY = y;
        lastCameraZ = z;
        if (lastCameraNanos == 0 || jump) {
            lastCameraNanos = now;
            cameraOpenness = target;
            return cameraOpenness;
        }
        double dt = Math.min((now - lastCameraNanos) / 1.0e9, 10.0);
        lastCameraNanos = now;
        cameraOpenness += (float) ((target - cameraOpenness) * (1.0 - Math.exp(-dt / 2.5)));
        return cameraOpenness;
    }

    /** When a level unloads: the next update snaps. */
    public static void clear() {
        lastCameraLevel = null;
    }
}
