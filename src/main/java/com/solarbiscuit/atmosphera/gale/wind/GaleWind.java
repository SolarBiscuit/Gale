package com.solarbiscuit.atmosphera.gale.wind;

import com.solarbiscuit.atmosphera.lib.wind.AtmosClock;
import com.solarbiscuit.atmosphera.lib.wind.WindProvider;
import com.solarbiscuit.atmosphera.lib.wind.WindVector;
import com.solarbiscuit.atmosphera.lib.world.Weather;
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
 *     shelter under the motion-blocking surface, a slow spatial ripple, and (0.8.0) the terrain: passes and crests
 *     speed the wind up, valleys and lee slopes slow it ({@link Terrain}).</li>
 * </ul>
 * Everything is a function of AtmosClock (the lib's smoothed world clock, which follows game time without its
 * catch-up jumps), weather, dimension and position, so clients see nearly the same wind with no networking. Safe from any thread and allocation-free after the first lookup of each biome.
 * <p>
 * 0.9.0: common code. It runs on a dedicated server too (flight and the later gameplay terms), so it touches no
 * client class: the client's options arrive through {@link Tuning} and the camera's eased openness lives in the
 * client-only {@link ClientOpenness}.
 */
public final class GaleWind implements WindProvider {
    public static final GaleWind INSTANCE = new GaleWind();
    private static final double TAU = Math.PI * 2.0;

    /** Openness per biome object (identity hash). Cleared when a level unloads, because biomes are per connection. */
    private final Map<Biome, Float> openness = new ConcurrentHashMap<>();
    private volatile Map<ResourceLocation, Float> overrides = Map.of();

    /**
     * The options only a client has (0.8.0): the TOML-only {@code wind.terrain} and {@code wind.storm_strength}.
     * The server never installs one, so it always runs the defaults (terrain on, storm strength 1).
     */
    public interface Tuning {
        boolean terrain();

        /** Multiplier on the rain and thunder terms, 0.5..2. */
        float stormStrength();
    }

    private static final Tuning DEFAULTS = new Tuning() {
        @Override
        public boolean terrain() {
            return true;
        }

        @Override
        public float stormStrength() {
            return 1f;
        }
    };
    private volatile Tuning tuning = DEFAULTS;

    /** What rain means to the wind at ordinary rain (the weather service's 0.8). */
    private static final float ORDINARY_RAIN = 0.8f;

    /** Per-thread scratch for the weather reading: {@code global} is called from several threads at once. */
    private static final ThreadLocal<Weather.Sample> WEATHER = ThreadLocal.withInitial(Weather.Sample::new);

    private GaleWind() {
    }

    /** Client, at start-up: connects the options only a client has. */
    public void setTuning(Tuning value) {
        tuning = value == null ? DEFAULTS : value;
    }

    @Override
    public String name() {
        return "Gale";
    }

    @Override
    public void global(Level level, float partialTick, WindVector out) {
        double t = AtmosClock.worldSeconds(level, partialTick);
        // 0.8.0: the storm terms. With vanilla weather and storm_strength 1 the factors are exactly 1, so this is
        // the wind of 0.7.x. A weather mod's state scales the rain term by how heavy its spell is (0.8 is
        // ordinary rain) and both terms by its own wind factor; storm_strength is the player's or pack's multiplier.
        Weather.Sample weather = WEATHER.get();
        Weather.current(level, partialTick, weather);
        float stormScale = tuning.stormStrength() * weather.windFactor;
        float rainScale = stormScale;
        if (weather.modelled) {
            rainScale *= Mth.clamp(weather.intensity / ORDINARY_RAIN, 0.4f, 1.6f);
        }
        float rain = level.getRainLevel(partialTick) * rainScale;
        float thunder = level.getThunderLevel(partialTick) * stormScale;

        double g = 0.5 * Math.sin(t * 0.29) + 0.3 * Math.sin(t * 0.113 + 1.1) + 0.2 * Math.sin(t * 0.71 + 2.3);
        double threshold = Math.max(0.35 - 0.25 * rain - 0.15 * thunder, -0.6);
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
        double shelter = deepSheltered(level, x, y, z) ? 0.35 : 1.0;
        double ripple = 1.0 + 0.15 * Math.sin(x * 0.031 + t * 0.5) * Math.cos(z * 0.027 - t * 0.4);
        double terrain = tuning.terrain() ? Terrain.factor(level, bx, bz, out.dirX, out.dirZ) : 1.0;
        out.strength = Mth.clamp((float) (out.strength * open * (1.0 + altitude) * shelter * ripple * terrain), 0f,
                1f);
    }

    /**
     * The shelter term of {@link #sample}: more than 3 blocks below the motion-blocking surface (the wind there is
     * 35%). Coarse on purpose (any thread, heightmap only), so a roof just above a position does not count; render
     * thread users add the column field's closer test ({@code weather.LocalShelter}, 0.7.1).
     */
    public static boolean deepSheltered(Level level, double x, double y, double z) {
        return y < level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z)) - 3;
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

    /** From the waving JSON files, at resource reload. */
    public void setOpennessOverrides(Map<ResourceLocation, Float> byBiome) {
        overrides = byBiome;
        openness.clear();
    }

    public void clearCaches() {
        openness.clear();
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
