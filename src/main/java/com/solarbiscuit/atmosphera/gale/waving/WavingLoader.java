package com.solarbiscuit.atmosphera.gale.waving;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ByteMap;
import it.unimi.dsi.fastutil.objects.Reference2ByteOpenHashMap;
import com.solarbiscuit.atmosphera.gale.Gale;
import com.solarbiscuit.atmosphera.gale.GaleConfig;
import com.solarbiscuit.atmosphera.gale.wind.GaleWind;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Classifies block states for the {@link WavingTable} at every resource reload, off the render thread, and again when
 * the tags or the blacklist change.
 * <p>
 * Sources, lowest precedence first (later wins): class heuristics; {@code #namespace:tag} entries of the JSON files;
 * explicit block entries of the JSON files ({@code assets/<namespace>/waving/*.json} from the mods and resource
 * packs, lowest pack first); the user blacklist ({@code waving.blacklist}), which stops any block.
 * <p>
 * Tags are not bound during the resource reload, but the server sends them to every client at login (client-only
 * mods read them like any other client). So the reload keeps its parsed rules and classifies with whatever tags are
 * bound then (none at startup), and {@link #onTagsUpdated()} classifies again when the tags arrive. A server that
 * lacks a mod's tags only loses those entries.
 * File format (every key optional):
 * <pre>{@code
 * { "replace": false,                       // true: ignore lower packs' copies of this same file
 *   "leaves":  ["minecraft:oak_leaves", "somemod:*", "#minecraft:leaves"],
 *   "plants":  ["minecraft:wheat"],
 *   "hanging": ["minecraft:lantern[hanging=true]"],
 *   "chains":  ["somemod:rope[up=true]"],   // hanging, and marked as a chain (chain_sway, no crack with lanterns)
 *   "aquatic": ["minecraft:kelp"],
 *   "none":    ["minecraft:sea_pickle"],      // never waves
 *   "biome_openness": { "minecraft:plains": 1.3 } }   // wind factor per biome (1 = neutral)
 * }</pre>
 */
public final class WavingLoader extends SimplePreparableReloadListener<WavingLoader.Result> {
    /** The last key, {@code chains}, is hanging plus the chain mark (see {@link #valueOf}). */
    private static final String[] KEYS = {"none", "leaves", "plants", "hanging", "aquatic", "chains"};
    private static final ResourceLocation BLACKLIST_SOURCE = new ResourceLocation(Gale.MOD_ID, "blacklist");

    /** A {@code #tag} entry, kept for {@link #classify}, because the tag's blocks are only known once tags are bound. */
    private record TagRule(String entry, byte value, ResourceLocation file) {
    }

    /**
     * Everything a reclassification needs, independent of the tags and the blacklist: the heuristic's waving states,
     * the explicit block entries (already per state) and the tag entries, both in file order.
     */
    record Rules(BlockState[] heuristicStates, byte[] heuristicKinds, Reference2ByteOpenHashMap<BlockState> explicit,
                 List<TagRule> tags, int files) {
    }

    record Result(Rules rules, WavingTable.Classification classification, List<? extends String> blacklist,
                  Map<ResourceLocation, Float> openness) {
    }

    /** Render thread (and the reload's apply): the rules of the last reload. */
    private static volatile Rules rules;
    /** The blacklist the current classification was built with. */
    private static volatile List<? extends String> appliedBlacklist = List.of();

    /** The table value of a JSON key: its class, and for {@code chains} also the chain mark. */
    private static byte valueOf(int key) {
        return key == KEYS.length - 1 ? WavingTable.CHAIN_HANGING : (byte) key;
    }

    @Override
    protected Result prepare(ResourceManager manager, ProfilerFiller profiler) {
        // JSON entries per state (later files win), then one pass over every registered state. Nothing here uses
        // block-state ids: WavingTable.index maps the result to ids once they are assigned.
        Reference2ByteOpenHashMap<BlockState> overrides = new Reference2ByteOpenHashMap<>();
        List<TagRule> tagRules = new ArrayList<>();
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
                            String text = entry.getAsString();
                            if (text.trim().startsWith("#")) {
                                tagRules.add(new TagRule(text, valueOf(kind), file.getKey()));
                            } else {
                                apply(overrides, text, valueOf(kind), file.getKey());
                            }
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

        // The heuristic only depends on the block classes, so it runs once per reload; tags and the blacklist are
        // layered on top of it by classify, which is cheap enough to repeat at login.
        List<BlockState> states = new ArrayList<>();
        ByteArrayList kinds = new ByteArrayList();
        for (Block block : BuiltInRegistries.BLOCK) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                byte kind = heuristic(state);
                if (kind != WavingTable.NONE) {
                    states.add(state);
                    kinds.add(kind);
                }
            }
        }
        // Tags overlap (Croptopia's fruit leaves are in both #minecraft:crops and #minecraft:leaves), so a tag's class
        // does not depend on the order of the keys: leaves beat the other classes, "none" beats everything (stable
        // sort: file order is kept within each rank). Explicit block entries keep plain file order.
        tagRules.sort(Comparator.comparingInt(rule -> rule.value() == WavingTable.NONE ? 2
                : rule.value() == WavingTable.LEAVES ? 1 : 0));
        Rules built = new Rules(states.toArray(new BlockState[0]), kinds.toByteArray(), overrides, List.copyOf(tagRules), files);
        List<? extends String> blacklist = readBlacklist();
        return new Result(built, classify(built, blacklist), blacklist, Map.copyOf(openness));
    }

    /** The blacklist option; empty while the config is not loaded yet (the next refresh then picks it up). */
    private static List<? extends String> readBlacklist() {
        try {
            List<? extends String> list = GaleConfig.blacklist.get();
            return list == null ? List.of() : list;
        } catch (IllegalStateException notLoaded) {
            return List.of();
        }
    }

    /**
     * Layers the sources over the heuristic, lowest precedence first: tag entries, explicit block entries, then the
     * blacklist, which removes its states altogether. Tags are read as bound at this moment.
     */
    private static WavingTable.Classification classify(Rules r, List<? extends String> blacklist) {
        Reference2ByteOpenHashMap<BlockState> all = new Reference2ByteOpenHashMap<>(r.heuristicStates().length + 256);
        for (int i = 0; i < r.heuristicStates().length; i++) {
            all.put(r.heuristicStates()[i], r.heuristicKinds()[i]);
        }
        if (!r.tags().isEmpty()) {
            Reference2ByteOpenHashMap<BlockState> fromTags = new Reference2ByteOpenHashMap<>();
            for (TagRule rule : r.tags()) {
                apply(fromTags, rule.entry(), rule.value(), rule.file());
            }
            all.putAll(fromTags);
        }
        all.putAll(r.explicit());
        if (!blacklist.isEmpty()) {
            Reference2ByteOpenHashMap<BlockState> stopped = new Reference2ByteOpenHashMap<>();
            for (String entry : blacklist) {
                apply(stopped, entry, WavingTable.NONE, BLACKLIST_SOURCE);
            }
            for (BlockState state : stopped.keySet()) {
                all.removeByte(state);
            }
        }
        List<BlockState> states = new ArrayList<>();
        ByteArrayList kinds = new ByteArrayList();
        int[] counts = new int[WavingTable.AQUATIC + 1];
        for (Reference2ByteMap.Entry<BlockState> e : all.reference2ByteEntrySet()) {
            byte value = e.getByteValue();
            if (value != WavingTable.NONE) {
                states.add(e.getKey());
                kinds.add(value);
                counts[value & WavingTable.KIND_MASK]++;
            }
        }
        return new WavingTable.Classification(states.toArray(new BlockState[0]), kinds.toByteArray(), counts, r.files());
    }

    /**
     * Render thread, on Forge's {@code TagsUpdatedEvent} from the server's packet: the tag entries can resolve now.
     * Classifies again and rebuilds the table, which remeshes once if anything changed.
     */
    public static void onTagsUpdated() {
        reclassify("tags", readBlacklist());
    }

    /**
     * Render thread, after each Atmosphera settings refresh: when {@code waving.blacklist} differs from the list the
     * classification was built with, classifies again. Nothing is allocated while the list is unchanged (Forge hands
     * out the same cached list until the file is reloaded).
     */
    public static void onSettingsRefresh() {
        List<? extends String> now = readBlacklist();
        if (!now.equals(appliedBlacklist)) {
            reclassify("blacklist", now);
        }
    }

    private static void reclassify(String when, List<? extends String> blacklist) {
        Rules r = rules;
        if (r == null) {
            return;
        }
        appliedBlacklist = blacklist;
        WavingTable.setClassification(classify(r, blacklist));
        WavingTable.index(when);
    }

    @Override
    protected void apply(Result result, ResourceManager manager, ProfilerFiller profiler) {
        GaleWind.INSTANCE.setOpennessOverrides(result.openness());
        rules = result.rules();
        appliedBlacklist = result.blacklist();
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
            // chains are rigid links. 0.7.3: the chain mark is part of the class now (the "chains" key sets it too).
            return state.hasProperty(ChainBlock.AXIS) && state.getValue(ChainBlock.AXIS) == net.minecraft.core.Direction.Axis.Y
                    ? WavingTable.CHAIN_HANGING : WavingTable.NONE;
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

    /**
     * {@code id}, {@code id[prop=value,...]}, {@code namespace:*} or {@code #namespace:tag}. Unknown blocks and empty
     * or unbound tags are skipped silently (mod not installed, tags not received yet).
     */
    private static void apply(Reference2ByteOpenHashMap<BlockState> kinds, String entry, byte kind, ResourceLocation file) {
        String spec = entry.trim();
        String props = null;
        int bracket = spec.indexOf('[');
        if (bracket >= 0 && spec.endsWith("]")) {
            props = spec.substring(bracket + 1, spec.length() - 1);
            spec = spec.substring(0, bracket);
        }
        if (spec.startsWith("#")) {
            ResourceLocation tagId = ResourceLocation.tryParse(spec.substring(1));
            if (tagId != null) {
                for (Holder<Block> holder : BuiltInRegistries.BLOCK.getTagOrEmpty(TagKey.create(Registries.BLOCK, tagId))) {
                    applyBlock(kinds, holder.value(), props, kind, entry, file);
                }
            }
            return;
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
