package com.solarbiscuit.atmosphera.gale.waving;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.Vec3;

/**
 * Gale 0.3.0 entity bending: the {@link #MAX} entities nearest the camera that can push plants aside (mobs, players,
 * boats), with their feet position interpolated to this frame and a push radius from their width. Filled once per frame
 * on the render thread into fixed arrays (no allocation besides the level's entity iterator); written to the frame
 * slots {@code gale_Push0..7} camera-relative.
 */
final class EntityPush {
    static final int MAX = 8;
    /** Entities further than this from the camera are ignored (the bend is invisible past it). */
    private static final double RANGE = 32.0;

    static final double[] X = new double[MAX];
    static final double[] Y = new double[MAX];
    static final double[] Z = new double[MAX];
    static final float[] RADIUS = new float[MAX];
    private static final double[] DIST = new double[MAX];
    static int count;

    private EntityPush() {
    }

    /** Render thread, once per frame. */
    static void update(ClientLevel level, Vec3 camera, float partial) {
        count = 0;
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity || entity instanceof Boat) || entity.isSpectator() || entity.isRemoved()) {
                continue;
            }
            double x = entity.xo + (entity.getX() - entity.xo) * partial;
            double y = entity.yo + (entity.getY() - entity.yo) * partial;
            double z = entity.zo + (entity.getZ() - entity.zo) * partial;
            double dx = x - camera.x;
            double dy = y - camera.y;
            double dz = z - camera.z;
            double d = dx * dx + dy * dy + dz * dz;
            if (d > RANGE * RANGE) {
                continue;
            }
            // Insertion into the nearest MAX, sorted by distance.
            int at = count < MAX ? count : MAX;
            while (at > 0 && DIST[at - 1] > d) {
                at--;
            }
            if (at >= MAX) {
                continue;
            }
            int last = Math.min(count, MAX - 1);
            for (int i = last; i > at; i--) {
                X[i] = X[i - 1];
                Y[i] = Y[i - 1];
                Z[i] = Z[i - 1];
                RADIUS[i] = RADIUS[i - 1];
                DIST[i] = DIST[i - 1];
            }
            X[at] = x;
            Y[at] = y;
            Z[at] = z;
            RADIUS[at] = Math.min(1.5f, entity.getBbWidth() * 0.5f + 0.2f);
            DIST[at] = d;
            if (count < MAX) {
                count++;
            }
        }
    }

    /** Frame slot {@code gale_Push<i>}: xyz feet position relative to the camera, w push radius (0 = none). */
    static void write(int i, float[] out) {
        if (i >= count) {
            out[0] = out[1] = out[2] = out[3] = 0f;
            return;
        }
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        out[0] = (float) (X[i] - camera.x);
        out[1] = (float) (Y[i] - camera.y);
        out[2] = (float) (Z[i] - camera.z);
        out[3] = RADIUS[i];
    }
}
