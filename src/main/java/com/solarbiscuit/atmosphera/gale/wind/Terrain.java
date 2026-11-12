package com.solarbiscuit.atmosphera.gale.wind;

import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The terrain term of {@link GaleWind#sample} (0.8.0): a local speed factor from {@code MOTION_BLOCKING} heights.
 * <ul>
 *     <li><b>Channel:</b> heights 8 and 16 blocks to both sides across the wind. Walls on both sides that stand above
 *     the position squeeze the flow, up to 1.6 times in a narrow pass.</li>
 *     <li><b>Crest:</b> a position above the mean height around it gets the hill speed-up of about {@code 1 + 2H/L}
 *     (Jackson and Hunt, H the height above the surroundings, L the distance over which it falls). Valley floors and
 *     the lee side of a slope get less.</li>
 * </ul>
 * The result is clamped to 0.6..1.8.
 * <p>
 * It must cost nothing per call: the factor is worked out once per 8-block cell and wind-direction bucket (16 of
 * them) and kept for 40 ticks, in a small direct-mapped table. A table belongs to one thread (particles, the
 * chunk builders and the server thread all sample), so nothing is shared and nothing is locked. A miss costs 9
 * heightmap reads; a hit costs a hash and a compare. Only loaded chunks are read; an unloaded one gives a factor of 1.
 * Common code: no client class is touched, so it runs on a dedicated server.
 */
final class Terrain {
    private static final int CELL_SHIFT = 3;
    private static final int BUCKETS = 16;
    /** Power of two. 512 cells cover a 180 x 180 block area for one wind bucket, far more than any one thread uses. */
    private static final int SLOTS = 512;
    private static final int SLOT_BITS = 9;
    private static final long REFRESH_TICKS = 40L;
    /** Horizontal scale of a hill, in blocks: the distance over which its height falls to the surroundings. */
    private static final float HILL_LENGTH = 12f;

    /** Unit vectors at the middle of each direction bucket. */
    private static final float[] BUCKET_X = new float[BUCKETS];
    private static final float[] BUCKET_Z = new float[BUCKETS];

    static {
        for (int b = 0; b < BUCKETS; b++) {
            double angle = (b + 0.5) * (Math.PI * 2.0 / BUCKETS) - Math.PI;
            BUCKET_X[b] = (float) Math.cos(angle);
            BUCKET_Z[b] = (float) Math.sin(angle);
        }
    }

    private static final ThreadLocal<Terrain> LOCAL = ThreadLocal.withInitial(Terrain::new);

    private final long[] keys = new long[SLOTS];
    /** Wind bucket in the low 4 bits, the level's identity hash above (levels differ per connection and dimension). */
    private final int[] tags = new int[SLOTS];
    private final long[] stamps = new long[SLOTS];
    private final float[] factors = new float[SLOTS];

    private Terrain() {
        java.util.Arrays.fill(stamps, Long.MIN_VALUE / 2);
    }

    /** The speed factor at a block column for a wind blowing towards (dirX, dirZ). Any thread; no allocation. */
    static float factor(Level level, int x, int z, float dirX, float dirZ) {
        return LOCAL.get().lookup(level, x, z, dirX, dirZ);
    }

    private float lookup(Level level, int x, int z, float dirX, float dirZ) {
        int cellX = x >> CELL_SHIFT;
        int cellZ = z >> CELL_SHIFT;
        int bucket = (int) ((Mth.atan2(dirZ, dirX) + Math.PI) * (BUCKETS / (Math.PI * 2.0)));
        bucket = Mth.clamp(bucket, 0, BUCKETS - 1);
        int tag = (System.identityHashCode(level) << 4) | bucket;
        long key = ((long) cellX << 32) ^ (cellZ & 0xFFFFFFFFL);
        long mixed = (key + tag * 0x632BE59BD9B4E019L) * 0x9E3779B97F4A7C15L;
        int slot = (int) (mixed >>> (64 - SLOT_BITS));
        long now = level.getGameTime();
        long age = now - stamps[slot];
        if (keys[slot] == key && tags[slot] == tag && age >= 0L && age < REFRESH_TICKS) {
            return factors[slot];
        }
        float value = compute(level, (cellX << CELL_SHIFT) + 4, (cellZ << CELL_SHIFT) + 4, bucket);
        keys[slot] = key;
        tags[slot] = tag;
        stamps[slot] = now;
        factors[slot] = value;
        return value;
    }

    /** Height of the surface at a column, or -1 when its chunk is not loaded. */
    private static int height(Level level, int x, int z) {
        if (!level.hasChunk(x >> 4, z >> 4)) {
            return -1;
        }
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
    }

    private static float compute(Level level, int cx, int cz, int bucket) {
        float dx = BUCKET_X[bucket];
        float dz = BUCKET_Z[bucket];
        // Across the wind: the left-hand perpendicular.
        float px = -dz;
        float pz = dx;
        int h0 = height(level, cx, cz);
        int left8 = height(level, round(cx + px * 8f), round(cz + pz * 8f));
        int right8 = height(level, round(cx - px * 8f), round(cz - pz * 8f));
        int left16 = height(level, round(cx + px * 16f), round(cz + pz * 16f));
        int right16 = height(level, round(cx - px * 16f), round(cz - pz * 16f));
        int up8 = height(level, round(cx - dx * 8f), round(cz - dz * 8f));
        int down8 = height(level, round(cx + dx * 8f), round(cz + dz * 8f));
        int up16 = height(level, round(cx - dx * 16f), round(cz - dz * 16f));
        int down16 = height(level, round(cx + dx * 16f), round(cz + dz * 16f));
        if (h0 < 0 || left8 < 0 || right8 < 0 || left16 < 0 || right16 < 0 || up8 < 0 || down8 < 0 || up16 < 0
                || down16 < 0) {
            return 1f;
        }

        // Channel: both walls must stand above the position, so the lower one counts.
        float near = Mth.clamp(Math.min(left8 - h0, right8 - h0) / 10f, 0f, 1f);
        float far = Mth.clamp(Math.min(left16 - h0, right16 - h0) / 14f, 0f, 1f);
        float channel = 0.65f * near + 0.35f * far;

        // Crest: height above the mean of the eight surrounding columns.
        float mean = (left8 + right8 + left16 + right16 + up8 + down8 + up16 + down16) / 8f;
        float rise = 2f * (h0 - mean) / HILL_LENGTH;
        float crest;
        if (rise > 0f) {
            crest = 1f + Math.min(rise, 0.8f);
        } else {
            // A valley floor is calmer, unless it is a pass (the channel term already speeds that up).
            crest = 1f + Math.max(rise, -0.4f) * (1f - channel);
        }
        // Lee side: ground that rises just upwind shelters the position.
        float lee = Mth.clamp((up8 - h0 - 3f) / 8f, 0f, 1f);
        crest *= 1f - 0.3f * lee;

        return Mth.clamp((1f + 0.6f * channel) * crest, 0.6f, 1.8f);
    }

    private static int round(float v) {
        return Mth.floor(v + 0.5f);
    }
}
