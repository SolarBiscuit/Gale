package com.solarbiscuit.atmosphera.gale.waving;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ByteOpenHashMap;
import com.solarbiscuit.atmosphera.gale.Gale;
import com.solarbiscuit.atmosphera.gale.wind.GaleWind;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.Fluids;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Classifies block states for the {@link WavingTable} at every resource reload, off the render thread.
 * <p>
 * Sources, in order (later wins): class heuristics, then every {@code assets/<namespace>/waving/*.json} from the
 * mods and resource packs, lowest pack first. Server data tags are never used: they do not reach a client-only mod.
 * File format (every key optional):
 * <pre>{@code
 * { "replace": false,                       // true: ignore lower packs' copies of this same file
 *   "leaves":  ["minecraft:oak_leaves", "somemod:*"],
 *   "plants":  ["minecraft:wheat"],
 *   "hanging": ["minecraft:lantern[hanging=true]"],
 *   "aquatic": ["minecraft:kelp"],
 *   "none":    ["minecraft:sea_pickle"],      // never waves
 *   "biome_openness": { "minecraft:plains": 1.3 } }   // wind factor per biome (1 = neutral)
 * }</pre>
 */
public final class WavingLoader extends SimplePreparableReloadListener<WavingLoader.Result> {
    private static final String[] KEYS = {"none", "leaves", "plants", "hanging", "aquatic"};

    record Result(WavingTable.Classification classification, Map<ResourceLocation, Float> openness) {
    }

    @Override
    protected Result prepare(ResourceManager manager, ProfilerFiller profiler) {
        // JSON entries per state (later files win), then one pass over every registered state. Nothing here uses
        // block-state ids: WavingTable.index maps the result to ids once they are assigned.
        Reference2ByteOpenHashMap<BlockState> overrides = new Reference2ByteOpenHashMap<>();
        Map<ResourceLocation, Float> openness = new HashMap<>();
        int files = 0;
        for (Map.Entry<ResourceLocation, List<Resource>> file
                : manager.listResourceStacks("waving", path -> path.getPath().endsWith(".json")).entrySet()) {
            List<JsonObject> layers = new ArrayList<>();
            for (Resource resource : file.getValue()) {
                try (Reader reader = resource.openAsReader()) {
                    JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                    if (json.has("replace") && json.get("replace").getAsBoolean()) {
                        layers.clear();
                    }
                    layers.add(json);
                } catch (Exception e) {
                    Gale.LOGGER.warn("Bad waving file {} in {}: {}", file.getKey(), resource.sourcePackId(), e.toString());
                }
            }
            for (JsonObject json : layers) {
                files++;
                for (int kind = 0; kind < KEYS.length; kind++) {
                    if (json.has(KEYS[kind])) {
                        for (JsonElement entry : json.getAsJsonArray(KEYS[kind])) {
                            apply(overrides, entry.getAsString(), (byte) kind, file.getKey());
                        }
                    }
                }
                if (json.has("biome_openness")) {
                    for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("biome_openness").entrySet()) {
                        ResourceLocation biome = ResourceLocation.tryParse(e.getKey());
                        if (biome != null) {
                            openness.put(biome, e.getValue().getAsFloat());
                        }
                    }
                }
            }
        }

        List<BlockState> states = new ArrayList<>();
        ByteArrayList kinds = new ByteArrayList();
        int[] counts = new int[KEYS.length];
        for (Block block : BuiltInRegistries.BLOCK) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                byte kind = overrides.containsKey(state) ? overrides.getByte(state) : heuristic(state);
                if (kind != WavingTable.NONE) {
                    states.add(state);
                    kinds.add(kind);
                    counts[kind]++;
                }
            }
        }
        return new Result(new WavingTable.Classification(states.toArray(new BlockState[0]), kinds.toByteArray(),
                counts, files), Map.copyOf(openness));
    }

    @Override
    protected void apply(Result result, ResourceManager manager, ProfilerFiller profiler) {
        GaleWind.INSTANCE.setOpennessOverrides(result.openness());
        WavingTable.setClassification(result.classification());
        if (!WavingTable.index("resource reload")) {
            WavingTable.Classification c = result.classification();
            Gale.LOGGER.info("Waving classes: {} leaves, {} plants, {} hanging, {} aquatic block states from {} file(s); "
                            + "block-state ids not assigned yet, the table is built at login.",
                    c.counts()[WavingTable.LEAVES], c.counts()[WavingTable.PLANTS], c.counts()[WavingTable.HANGING],
                    c.counts()[WavingTable.AQUATIC], c.files());
        }
    }

    /** Built-in classes from the block class, for vanilla and for mods whose plants extend the vanilla bases. */
    private static byte heuristic(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof LeavesBlock) {
            return WavingTable.LEAVES;
        }
        if (block instanceof LanternBlock) {
            return state.hasProperty(LanternBlock.HANGING) && state.getValue(LanternBlock.HANGING)
                    ? WavingTable.HANGING : WavingTable.NONE;
        }
        if (block instanceof ChainBlock) {
            // 0.6.3: vertical chains hang (vanilla, Additional Lanterns and any other ChainBlock subclass); horizontal
            // chains are rigid links.
            return state.hasProperty(ChainBlock.AXIS) && state.getValue(ChainBlock.AXIS) == net.minecraft.core.Direction.Axis.Y
                    ? WavingTable.HANGING : WavingTable.NONE;
        }
        if (block instanceof VineBlock || block instanceof HangingRootsBlock || block instanceof CaveVines
                || block instanceof WeepingVinesBlock || block instanceof WeepingVinesPlantBlock) {
            return WavingTable.HANGING;
        }
        if (block instanceof KelpBlock || block instanceof KelpPlantBlock || block instanceof SeagrassBlock
                || block instanceof TallSeagrassBlock) {
            return WavingTable.AQUATIC;
        }
        // The block's own (uncached) answer: the state's fluid cache can still be empty during the startup reload
        // (ModernFix fills block-state caches lazily), which classified no water plant at all in RatPack.
        @SuppressWarnings("deprecation")
        boolean water = block.getFluidState(state).getType().isSame(Fluids.WATER);
        if (block instanceof GrowingPlantBlock) {
            // Twisting vines, kelp and modded column plants. Modded hanging ones are recognised by name or JSON.
            String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
            if (path.contains("weeping") || path.contains("hanging") || path.contains("drooping")) {
                return WavingTable.HANGING;
            }
            return water ? WavingTable.AQUATIC : WavingTable.PLANTS;
        }
        if (block instanceof SugarCaneBlock) {
            return WavingTable.PLANTS;
        }
        if (block instanceof BushBlock) {
            if (block instanceof MushroomBlock || block instanceof FungusBlock || block instanceof SeaPickleBlock
                    || block instanceof WaterlilyBlock) {
                return WavingTable.NONE;
            }
            return water ? WavingTable.AQUATIC : WavingTable.PLANTS;
        }
        return WavingTable.NONE;
    }

    /** {@code id}, {@code id[prop=value,...]} or {@code namespace:*}. Unknown blocks are skipped (mod not installed). */
    private static void apply(Reference2ByteOpenHashMap<BlockState> kinds, String entry, byte kind, ResourceLocation file) {
        String spec = entry.trim();
        String props = null;
        int bracket = spec.indexOf('[');
        if (bracket >= 0 && spec.endsWith("]")) {
            props = spec.substring(bracket + 1, spec.length() - 1);
            spec = spec.substring(0, bracket);
        }
        if (spec.endsWith(":*")) {
            String namespace = spec.substring(0, spec.length() - 2);
            for (Map.Entry<ResourceKey<Block>, Block> e : BuiltInRegistries.BLOCK.entrySet()) {
                if (e.getKey().location().getNamespace().equals(namespace)) {
                    applyBlock(kinds, e.getValue(), props, kind, entry, file);
                }
            }
            return;
        }
        ResourceLocation id = ResourceLocation.tryParse(spec);
        Optional<Block> block = id == null ? Optional.empty() : BuiltInRegistries.BLOCK.getOptional(id);
        if (block.isPresent()) {
            applyBlock(kinds, block.get(), props, kind, entry, file);
        }
    }

    private static void applyBlock(Reference2ByteOpenHashMap<BlockState> kinds, Block block, String props, byte kind, String entry, ResourceLocation file) {
        for (BlockState state : block.getStateDefinition().getPossibleStates()) {
            if (props == null || matches(state, props, entry, file)) {
                kinds.put(state, kind);
            }
        }
    }

    private static boolean matches(BlockState state, String props, String entry, ResourceLocation file) {
        for (String pair : props.split(",")) {
            String[] kv = pair.split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            Property<?> property = state.getBlock().getStateDefinition().getProperty(kv[0].trim());
            if (property == null) {
                Gale.LOGGER.warn("{}: '{}' has no property {}", file, entry, kv[0].trim());
                return false;
            }
            if (!valueName(state, property).equals(kv[1].trim().toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }
}
