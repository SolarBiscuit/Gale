package com.solarbiscuit.atmosphera.gale;

import com.solarbiscuit.atmosphera.lib.options.AtmosConfig;
import com.solarbiscuit.atmosphera.lib.options.AtmosOption;
import com.solarbiscuit.atmosphera.lib.options.ChangeTier;

import java.util.List;

/** {@code config/Atmosphera/gale-client.toml} and the Gale group in Video Settings > Atmosphera. */
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
    public static AtmosOption<Boolean> entityBendingOn;
    public static AtmosOption<Double> entityBending;
    public static AtmosOption<Boolean> shelterOn;
    public static AtmosOption<Double> shelter;
    public static AtmosOption<Boolean> chainSwing;
    public static AtmosOption<Double> chainSway;
    public static AtmosOption<Boolean> banners;
    public static AtmosOption<Boolean> connectibleChains;
    public static AtmosOption<Boolean> plantBend;
    public static AtmosOption<Boolean> explosionWave;
    public static AtmosOption<Boolean> gustPatches;
    public static AtmosOption<Boolean> windSheen;
    public static AtmosOption<Boolean> stormTempo;
    /** 0.7.3: TOML only; applied by {@code WavingLoader} (it reclassifies the blocks, not only a remesh). */
    public static AtmosOption<List<? extends String>> blacklist;
    public static AtmosOption<Boolean> rainTiltOn;
    public static AtmosOption<Double> rainTilt;
    /** 0.8.0: TOML only; the channel and ridge speed-up of the wind ({@code wind.GaleWind}). */
    public static AtmosOption<Boolean> terrain;
    /** 0.8.0: TOML only; multiplies the rain and thunder terms of the wind. */
    public static AtmosOption<Double> stormStrength;
    /** 0.10.0: TOML only; lets the player decline the elytra wind the server announced. */
    public static AtmosOption<Boolean> elytra;

    private GaleConfig() {
    }

    static void register() {
        AtmosConfig config = AtmosConfig.create(Gale.MOD_ID, "Gale");
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
        entityBendingOn = config.toggle("entity_bending_enabled", true, ChangeTier.LIVE, "Plants bend around you",
                "Grass and flowers bend away from players, mobs and boats moving through them (the 8 nearest within "
                        + "32 blocks).");
        entityBending = config.percent("entity_bending", 1.0, 0.0, 1.5, ChangeTier.LIVE, "Bending amount",
                "How far plants bend away.");
        shelterOn = config.toggle("shelter_enabled", true, ChangeTier.LIVE, "Shelter from the wind",
                "Plants, leaves, hanging blocks, banners and strung chains under a roof, a canopy or an overhang sway "
                        + "less.");
        shelter = config.percent("shelter", 1.0, 0.0, 1.0, ChangeTier.LIVE, "Shelter amount",
                "How much calmer sheltered blocks are (100%: down to 35% of the sway)."); 
        chainSwing = config.toggle("chain_swing", true, ChangeTier.REMESH, "Longer chains swing further",
                "Chains, vines and lanterns hanging under other hanging blocks swing further the longer the chain "
                        + "above them (up to two blocks of lever), like a pendulum.");

        // 0.7.3: from here to storm_tempo the options are TOML only (all on by default), to keep the page short.
        config.fileOnly(true);
        chainSway = config.percent("chain_sway", 1.0, 0.0, 2.0, ChangeTier.LIVE, "Chain sway",
                "How far hanging chains sway in the wind (vanilla and modded chains, e.g. Additional Lanterns). "
                        + "0% = chains hold still; lanterns under them still swing from the chain's end.");
        banners = config.toggle("banners", true, ChangeTier.LIVE, "Banners in the wind",
                "Banner flags blow downwind and flutter more as the wind rises; banners under a roof or canopy stay "
                        + "calmer (\"Shelter amount\"). "
                        + "How far follows \"Sway amount\".");
        connectibleChains = config.toggle("connectible_chains", true, ChangeTier.LIVE, "Connectible Chains swing",
                "Chains strung between fences with Connectible Chains (if installed) swing sideways in the wind, with "
                        + "everything hung on them. How far follows \"Chain sway\".");
        plantBend = config.toggle("plant_bend", true, ChangeTier.REMESH, "Tall plants bend from the root",
                "Tall grass, sunflowers, sugar cane and kelp curve over their first two blocks like a stem, instead of "
                        + "tilting stiffly at the top of the first block; bent stems get a little shorter.");
        explosionWave = config.toggle("explosion_wave", true, ChangeTier.LIVE, "Explosion shockwaves",
                "Explosions send a short ring outward that flattens plants and shakes leaves (scaled by \"Bending amount\").");
        gustPatches = config.toggle("gust_patches", true, ChangeTier.LIVE, "Gust patches",
                "Gusts sweep over fields and canopies in irregular patches that drift downwind and change shape. "
                        + "Off: the older, regular rolling bands.");
        windSheen = config.toggle("wind_sheen", true, ChangeTier.LIVE, "Wind sheen up close",
                "Grass, crops and leaves near you brighten and darken as gusts pass, like wind rolling over a wheat "
                        + "field. Strength: lod.lod_canopy_shimmer, \"Wind sheen\" in Video Settings (shared with distant foliage).");
        stormTempo = config.toggle("storm_tempo", true, ChangeTier.LIVE, "Storm tempo",
                "Foliage sways faster in strong wind and storms (up to 1.6 times), not only further.");
        blacklist = config.stringList("blacklist", List.of(), ChangeTier.REMESH, "Waving blacklist",
                "Blocks that never wave, whatever the tags, the built-in rules or a resource pack say (vanilla "
                        + "blocks too). Entries: a block id, id[property=value,...], namespace:* or #namespace:tag. "
                        + "Example: [\"croptopia:*\", \"minecraft:grass\", \"minecraft:tall_grass[half=upper]\", "
                        + "\"#minecraft:flowers\"].");

        config.fileOnly(false);

        config.section("weather","Weather: rain and snow follow the wind (works with shader packs too).");
        rainTiltOn = config.toggle("rain_tilt_enabled", true, ChangeTier.LIVE, "Rain follows the wind",
                "Rain and snow streaks lean with the wind: more in storms and gusts and in open land, less in "
                        + "forests and caves. Snow drifts twice as far as rain.");
        rainTilt = config.percent("rain_tilt", 1.0, 0.0, 2.0, ChangeTier.LIVE, "Rain tilt amount",
                "How far the streaks lean. 100%: about 15 degrees in steady rain on open land, up to about 35 degrees "
                        + "in thunderstorm gusts.");

        // 0.8.0 / 0.10.0: the wind itself. All TOML only, so the Video Settings page stays as short as it was.
        config.section("wind", "Wind: how the shared wind field behaves.");
        config.fileOnly(true);
        terrain = config.toggle("terrain", true, ChangeTier.LIVE, "Wind follows the terrain",
                "The wind speeds up through narrow passes between hills and over crests and ridges, and eases in "
                        + "valleys and in the lee of slopes (up to 1.8 times and down to 0.6 times).");
        stormStrength = config.percent("storm_strength", 1.0, 0.5, 2.0, ChangeTier.LIVE, "Storm strength",
                "Multiplies what rain and thunder add to the wind's strength and to how often it gusts. 100% is the "
                        + "default; more makes wilder storms.");
        elytra = config.toggle("elytra", true, ChangeTier.LIVE, "Wind on the elytra",
                "While gliding, the wind pushes you (headwind slows, tailwind speeds you up, crosswind drifts you), "
                        + "when the server runs it (always in singleplayer). Off: you decline it on this client.");
        config.fileOnly(false);

        config.section("lod","Voxy LODs: foliage keeps moving past the vanilla render distance (needs AtmospheraLib's Voxy LOD shading, no shader pack).");
        lodSway = config.toggle("lod_sway", true, ChangeTier.LIVE, "Sway on LODs",
                "Distant Voxy/NeoVoxy LOD foliage sways too. Close to the render distance it moves exactly like the "
                        + "blocks nearby, further out the motion turns into light rolling over canopies and fields as gusts pass.");
        lodSwayDistance = config.percent("lod_sway_distance", 2.5, 1.0, 4.0, ChangeTier.LIVE, "LOD sway distance",
                "How far LOD foliage keeps moving, as a share of the vanilla render distance (250% = 2.5 times as far).");
        lodShimmer = config.percent("lod_canopy_shimmer", 0.05, 0.0, 0.15, ChangeTier.LIVE, "Wind sheen",
                "Strength of the waves of light that gusts send over foliage: on distant LOD canopies and fields, and "
                        + "up close while waving.wind_sheen is on in the file (0% = no shading change).");
        config.register();
    }
}
