package com.solarbiscuit.atmosphera.gale.waving;

import com.solarbiscuit.atmosphera.gale.Gale;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
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

    /** Waving states found at the last resource reload, with their classes. */
    record Classification(BlockState[] states, byte[] kinds, int[] counts, int files) {
    }

    private static final WavingTable EMPTY = new WavingTable(new byte[0], false);
    private static volatile WavingTable current = EMPTY;
    private static volatile Classification classification;
    /** Per chunk-builder thread: the last state looked up, and a scratch position for neighbour reads. */
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private final byte[] kinds;
    /** False when no classified state can occlude, so the hot path skips every full cube with one field read. */
    private final boolean occludingEntries;

    private WavingTable(byte[] kinds, boolean occludingEntries) {
        this.kinds = kinds;
        this.occludingEntries = occludingEntries;
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
        publish(new WavingTable(kinds, occluding), when);
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
        return id >= 0 && id < kinds.length ? kinds[id] : NONE;
    }

    /** The waving class of a state. Any thread; remembers the last state per thread (one block = many calls). */
    public static int kind(BlockState state) {
        WavingTable table = current;
        if (state.canOcclude() && !table.occludingEntries) {
            return NONE;
        }
        Scratch scratch = SCRATCH.get();
        if (scratch.state != state || scratch.table != table) {
            scratch.kind = table.lookup(state);
            scratch.state = state;
            scratch.table = table;
        }
        return scratch.kind;
    }

    /** Waving class of the block at {@code pos + (0, dy, 0)}. */
    static int kindAt(BlockGetter level, BlockPos pos, int dy) {
        Scratch scratch = SCRATCH.get();
        return current.lookup(level.getBlockState(scratch.pos.setWithOffset(pos, 0, dy, 0)));
    }

    private static final class Scratch {
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockState state;
        WavingTable table;
        int kind;
    }
}
