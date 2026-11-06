package com.solarbiscuit.atmosphera.gale;

import com.solarbiscuit.atmosphera.lib.options.AtmosConfig;
import com.solarbiscuit.atmosphera.lib.options.AtmosOption;
import com.solarbiscuit.atmosphera.lib.options.ChangeTier;

/** {@code config/SolConfig/atmosphera/gale-client.toml} and the Gale group in Video Settings > Atmosphera. */
public final class GaleConfig {
    public static AtmosConfig.FeatureSwitch waving;
    public static AtmosOption<Boolean> leaves;
    public static AtmosOption<Boolean> plants;
    public static AtmosOption<Boolean> hanging;
    public static AtmosOption<Boolean> aquatic;
    public static AtmosOption<Double> amplitude;
    public static AtmosOption<Double> speed;
    public static AtmosOption<Boolean> lodSway;
    public static AtmosOption<Double> lodSwayDistance;
    public static AtmosOption<Double> lodShimmer;
    public static AtmosOption<Boolean> hangingSwing;
    public static AtmosOption<Double> leafFlutter;

    private GaleConfig() {
    }

    static void register() {
        AtmosConfig config = AtmosConfig.create(Gale.MOD_ID, "Gale (wind)");
        config.section("waving", "Waving foliage: blocks sway in the shared Atmosphera wind.");
        waving = config.featureSwitch("enabled", true, "Waving foliage",
                "Master switch for every waving class below.");
        leaves = config.toggle("leaves", true, ChangeTier.REMESH, "Waving leaves",
                "Leaves sway gently as a whole.");
        plants = config.toggle("plants", true, ChangeTier.REMESH, "Waving plants",
                "Grass, flowers, crops and other ground plants bend from their base.");
        hanging = config.toggle("hanging", true, ChangeTier.REMESH, "Waving hanging blocks",
                "Vines, weeping and cave vines, hanging roots and hanging lanterns swing from their top.");
        aquatic = config.toggle("aquatic", true, ChangeTier.REMESH, "Waving water plants",
                "Seagrass and kelp drift slowly from their base.");
        // New keys (were amplitude / speed, where 100% meant 3.3 times today's motion), so every config starts
        // at the new middle.
        amplitude = config.percent("sway_amount", 1.0, 0.0, 2.0, ChangeTier.LIVE, "Sway amount",
                "How far blocks move. 100% is a gentle breeze; movement is always capped at 0.2 blocks.");
        speed = config.percent("sway_speed", 1.0, 0.0, 2.0, ChangeTier.LIVE, "Sway speed",
                "How fast blocks sway. 100% is a calm, natural pace; 0% stops the motion.");

        hangingSwing = config.toggle("hanging_swing", true, ChangeTier.LIVE, "Hanging swing",
                "Lanterns, chains and vines swing like hanging objects: they lag behind gusts, overshoot and settle, "
                        + "and follow the slow sway more than the quick flutter. Off: they move like ground plants.");
        leafFlutter = config.percent("leaf_flutter", 0.5, 0.0, 1.0, ChangeTier.LIVE, "Leaf flutter",
                "Leaves close to you flicker a little in the light, as if turning in the wind (0% = off).");

        config.section("lod", "Voxy LODs: foliage keeps moving past the vanilla render distance (needs AtmospheraLib's Voxy LOD shading, no shader pack).");
        lodSway = config.toggle("lod_sway", true, ChangeTier.LIVE, "Sway on LODs",
                "Distant Voxy/NeoVoxy LOD foliage sways too. Close to the render distance it moves exactly like the "
                        + "blocks nearby, further out the motion turns into light rolling over canopies and fields as gusts pass.");
        lodSwayDistance = config.percent("lod_sway_distance", 2.5, 1.0, 4.0, ChangeTier.LIVE, "LOD sway distance",
                "How far LOD foliage keeps moving, as a share of the vanilla render distance (250% = 2.5 times as far).");
        lodShimmer = config.percent("lod_canopy_shimmer", 0.05, 0.0, 0.15, ChangeTier.LIVE, "LOD canopy shimmer",
                "Strength of the wind waves of light on distant LOD canopies and fields (0% = no shading change).");
        config.register();
    }
}
