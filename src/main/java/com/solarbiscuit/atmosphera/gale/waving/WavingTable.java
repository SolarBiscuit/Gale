package com.solarbiscuit.atmosphera.gale.waving;

import com.solarbiscuit.atmosphera.gale.Gale;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import com.solarbiscuit.atmosphera.lib.mesh.MeshHooks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;

/**
 * Immutable waving class per block state: a {@code byte[]} indexed by {@code Block.BLOCK_STATE_REGISTRY} id,
 * published through a volatile field. Chunk-builder threads read it through {@link #kind(BlockState)}, which is
 * allocation-free.
 * <p>
 * Two steps, because block-state ids are not stable: {@link WavingLoader} classifies the states at resource reload
 * (independent of ids), and {@link #index} turns that into the id table whenever the ids may have changed: after
 * the reload, and on every login (Forge reassigns ids from the registry snapshot; in RatPack the id map is still
 * empty during the startup reload).
 */
public final class WavingTable {
    public static final byte NONE = 0;
    public static final byte LEAVES = 1;
    public static final byte PLANTS = 2;
    public static final byte HANGING = 3;
    public static final byte AQUATIC = 4;
    /**
     * 0.7.3: the chain mark, a flag on top of the class in a stored entry (classification, table and per-state
     * cache): set by the ChainBlock heuristic and by the {@code chains} JSON key, only together with HANGING.
     */
    static final byte CHAIN_FLAG = (byte) 0x80;
    static final byte CHAIN_HANGING = (byte) (HANGING | CHAIN_FLAG);
    /** The class part of a stored entry. */
    static final int KIND_MASK = 0x7F;

    /** Waving states found at the last classification (reload, tags or blacklist), with their classes and chain flags. */
    record Classification(BlockState[] states, byte[] kinds, int[] counts, int files) {
    }

    private static final WavingTable EMPTY = new WavingTable(new byte[0], false, 0);
    /** Version stamped into each block state's cached class (0 never matches: states start at 0). */
    private static int nextVersion = 1;

    /** Whether block states carry Gale's cache field (the mixin applied); checked on first use. */
    private static final class StateCache {
        static final boolean ON = Blocks.AIR.defaultBlockState() instanceof WavingKindCache;
    }
    private static volatile WavingTable current = EMPTY;
    private static volatile Classification classification;
    /** Per chunk-builder thread: the last state looked up (only used if the block-state cache is missing). */
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private final byte[] kinds;
    /** False when no classified state can occlude, so the hot path skips every full cube with one field read. */
    private final boolean occludingEntries;
    /** 24-bit table version for the per-state cache. */
    private final int version;

    private WavingTable(byte[] kinds, boolean occludingEntries, int version) {
        this.kinds = kinds;
        this.occludingEntries = occludingEntries;
        this.version = version;
    }

    static void setClassification(Classification next) {
        classification = next;
    }

    /**
     * Rebuilds the id table from the last classification and the current ids. Render thread. Rebuilds the chunks
     * once if a level is loaded and the table changed. Returns false while the ids are not assigned.
     */
    public static boolean index(String when) {
        Classification c = classification;
        int size = Block.BLOCK_STATE_REGISTRY.size();
        if (c == null || size == 0) {
            if (c != null && "login".equals(when)) {
                Gale.LOGGER.warn("Block-state ids are still unassigned at login; nothing will wave.");
            }
            publish(EMPTY, when);
            return false;
        }
        byte[] kinds = new byte[size];
        boolean occluding = false;
        int missing = 0;
        int clashes = 0;
        for (int i = 0; i < c.states().length; i++) {
            BlockState state = c.states()[i];
            int id = Block.getId(state);
            if (id < 0 || id >= size || Block.stateById(id) != state) {
                missing++;
                continue;
            }
            clashes += kinds[id] != NONE ? 1 : 0;
            kinds[id] = c.kinds()[i];
            occluding |= state.canOcclude();
        }
        if (missing > 0 || clashes > 0) {
            Gale.LOGGER.warn("Waving table ({}): {} of {} states had no usable block-state id, {} id clashes.", when,
                    missing, c.states().length, clashes);
        }
        publish(new WavingTable(kinds, occluding, nextVersion++ & 0xFFFFFF), when);
        return true;
    }

    private static void publish(WavingTable next, String when) {
        WavingTable previous = current;
        current = next;
        if (Arrays.equals(previous.kinds, next.kinds)) {
            return;
        }
        Classification c = classification;
        if (c != null && next != EMPTY) {
            Gale.LOGGER.info("Waving table built ({}): {} leaves, {} plants, {} hanging, {} aquatic block states "
                            + "from {} file(s); {} ids.", when, c.counts()[LEAVES], c.counts()[PLANTS],
                    c.counts()[HANGING], c.counts()[AQUATIC], c.files(), next.kinds.length);
        }
        // Chunks meshed with the old table keep its bits until rebuilt.
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.levelRenderer != null) {
            mc.levelRenderer.allChanged();
        }
    }

    private int lookup(BlockState state) {
        if (state.canOcclude() && !occludingEntries) {
            return NONE;
        }
        int id = Block.getId(state);
        return id >= 0 && id < kinds.length ? kinds[id] & 0xFF : NONE;
    }

    /**
     * The waving class of a state. Any thread, allocation-free. The class is cached on the state itself, stamped with
     * the table version (0.1.9; one field read per call). Without the cache field, remembers the last state per thread.
     */
    public static int kind(BlockState state) {
        return entry(state) & KIND_MASK;
    }

    /** 0.7.3: whether the state is a chain (hanging, with the chain mark): vanilla and modded chains, ropes. */
    public static boolean isChain(BlockState state) {
        return (entry(state) & (CHAIN_FLAG & 0xFF)) != 0;
    }

    /** The stored entry (class and chain flag) behind {@link #kind} and {@link #isChain}; same cost as before the flag. */
    private static int entry(BlockState state) {
        WavingTable table = current;
        if (state.canOcclude() && !table.occludingEntries) {
            return NONE;
        }
        if (StateCache.ON) {
            WavingKindCache cache = (WavingKindCache) state;
            int packed = cache.gale$packedKind();
            if (packed >>> 8 == table.version) {
                return packed & 0xFF;
            }
            int entry = table.lookup(state);
            cache.gale$setPackedKind(table.version << 8 | entry);
            return entry;
        }
        Scratch scratch = SCRATCH.get();
        if (scratch.state != state || scratch.table != table) {
            scratch.kind = table.lookup(state);
            scratch.state = state;
            scratch.table = table;
        }
        return scratch.kind;
    }

    /**
     * Waving class of the block at {@code pos + (0, dy, 0)}, read through the lib's slice fast path. Anchoring only
     * ever asks for dy = +-1, which stays inside the mesher's slice margin.
     */
    static int kindAt(BlockGetter level, BlockPos pos, int dy) {
        return kind(MeshHooks.blockAt(level, pos.getX(), pos.getY() + dy, pos.getZ()));
    }

    private static final class Scratch {
        BlockState state;
        WavingTable table;
        int kind;
    }
}
