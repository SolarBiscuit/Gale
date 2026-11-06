package com.solarbiscuit.atmosphera.gale.wind;

import com.solarbiscuit.atmosphera.lib.wind.AtmosClock;
import com.solarbiscuit.atmosphera.lib.wind.WindProvider;
import com.solarbiscuit.atmosphera.lib.wind.WindVector;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gale's wind field, registered over the library default.
 * <ul>
 *     <li><b>Direction</b> drifts slowly (periods of minutes) around a per-dimension heading and veers in gusts.</li>
 *     <li><b>Gusts</b> are sparse bursts from summed sines; rain and thunder make them stronger and more frequent.</li>
 *     <li><b>Strength</b> rises with rain and thunder; dimensions without a sky (Nether, End) are calm.</li>
 *     <li><b>{@link #sample} adds the local terms:</b> biome openness (plains and peaks windy, forests and caves
 *     calm, overridable with {@code biome_openness} in {@code assets/<ns>/waving/*.json}), altitude above sea level,
 *     shelter under the motion-blocking surface, and a slow spatial ripple.</li>
 * </ul>
 * Everything is a function of AtmosClock (the lib's smoothed world clock, which follows game time without its
 * catch-up jumps), weather, dimension and position, so clients see nearly the same wind with no networking. Safe from any thread and allocation-free after the first lookup of each biome.
 */
public final class GaleWind implements WindProvider {
    public static final GaleWind INSTANCE = new GaleWind();
    private static final double TAU = Math.PI * 2.0;

    /** Openness per biome object (identity hash). Cleared when a level unloads, because biomes are per connection. */
    private final Map<Biome, Float> openness = new ConcurrentHashMap<>();
    private volatile Map<ResourceLocation, Float> overrides = Map.of();

    // Render thread only: the camera's openness, eased so biome borders do not snap the sway amount.
    private float cameraOpenness = 1f;
    private long lastCameraNanos;
    private Level lastCameraLevel;
    private double lastCameraX;
    private double lastCameraY;
    private double lastCameraZ;
    /** A camera move longer than this in one frame is a teleport: the openness snaps instead of easing (0.1.9). */
    private static final double SNAP_DISTANCE_SQ = 32.0 * 32.0;

    private GaleWind() {
    }

    @Override
    public String name() {
        return "Gale";
    }

    @Override
    public void global(Level level, float partialTick, WindVector out) {
        double t = AtmosClock.worldSeconds(level, partialTick);
        float rain = level.getRainLevel(partialTick);
        float thunder = level.getThunderLevel(partialTick);

        double g = 0.5 * Math.sin(t * 0.29) + 0.3 * Math.sin(t * 0.113 + 1.1) + 0.2 * Math.sin(t * 0.71 + 2.3);
        double threshold = 0.35 - 0.25 * rain - 0.15 * thunder;
        float gust = (float) Mth.clamp((g - threshold) / (1.0 - threshold), 0.0, 1.0);
        gust = gust * gust * (3f - 2f * gust);

        double heading = (level.dimension().location().hashCode() & 0xFFFF) / 65536.0 * TAU;
        double angle = heading + 1.1 * Math.sin(t / 1100.0) + 0.5 * Math.sin(t / 377.0 + 1.3)
                + 0.2 * Math.sin(t / 97.0 + 4.1) + 0.25 * gust * Math.sin(t * 0.5);

        float strength = 0.22f + 0.3f * rain + 0.3f * thunder + gust * (0.25f + 0.15f * rain);
        if (!level.dimensionType().hasSkyLight()) {
            strength *= 0.4f;
        }
        out.set((float) Math.cos(angle), (float) Math.sin(angle), Mth.clamp(strength, 0f, 1f), gust);
    }

    @Override
    public void sample(Level level, double x, double y, double z, float partialTick, WindVector out) {
        global(level, partialTick, out);
        double t = AtmosClock.worldSeconds(level, partialTick);
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        float open = openness(level, bx, Mth.floor(y), bz);
        double altitude = Mth.clamp((y - level.getSeaLevel()) / 96.0, 0.0, 0.6);
        double shelter = y < level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz) - 3 ? 0.35 : 1.0;
        double ripple = 1.0 + 0.15 * Math.sin(x * 0.031 + t * 0.5) * Math.cos(z * 0.027 - t * 0.4);
        out.strength = Mth.clamp((float) (out.strength * open * (1.0 + altitude) * shelter * ripple), 0f, 1f);
    }

    /** Wind factor of the biome at a block position: about 0.35 (caves) to 1.4 (peaks); 1 is neutral. */
    public float openness(Level level, int x, int y, int z) {
        Holder<Biome> holder = level.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z));
        Biome biome = holder.value();
        Float cached = openness.get(biome);
        if (cached != null) {
            return cached;
        }
        Optional<ResourceKey<Biome>> key = holder.unwrapKey();
        float value = key.map(k -> {
            Float override = overrides.get(k.location());
            return override != null ? override : byName(k.location().getPath());
        }).orElse(1f);
        openness.put(biome, value);
        return value;
    }

    /**
     * Render thread, once per frame: openness at the camera, eased over a few seconds (gale_Wave.z) so biome borders
     * do not snap the sway amount. A new level (join, dimension change) or a camera jump over 32 blocks (teleport,
     * spectator switch) snaps to the new value: easing there only showed the wrong sway for a few seconds.
     */
    public float cameraOpenness(Level level, double x, double y, double z) {
        float target = openness(level, Mth.floor(x), Mth.floor(y), Mth.floor(z));
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

    /** From the waving JSON files, at resource reload. */
    public void setOpennessOverrides(Map<ResourceLocation, Float> byBiome) {
        overrides = byBiome;
        openness.clear();
    }

    public void clearCaches() {
        openness.clear();
        lastCameraLevel = null;
    }

    private static float byName(String path) {
        if (has(path, "cave", "underground", "deep_dark", "cavern", "dripstone", "lush")) {
            return 0.35f;
        }
        if (has(path, "peak", "windswept", "slopes", "cliff", "mountain", "highland", "plateau")) {
            return 1.4f;
        }
        if (has(path, "forest", "jungle", "taiga", "woods", "woodland", "swamp", "mangrove", "bamboo", "grove",
                "rainforest", "thicket", "orchard", "bayou")) {
            return 0.75f;
        }
        if (has(path, "plains", "meadow", "savanna", "beach", "shore", "ocean", "desert", "badlands", "mesa",
                "tundra", "steppe", "prairie", "field", "shrubland", "flats", "dune", "coast", "heath", "moor",
                "grassland", "pasture", "outback", "hills")) {
            return 1.25f;
        }
        return 1f;
    }

    private static boolean has(String path, String... words) {
        for (String word : words) {
            if (path.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
