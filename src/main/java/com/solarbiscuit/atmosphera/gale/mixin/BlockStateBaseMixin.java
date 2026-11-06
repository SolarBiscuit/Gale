package com.solarbiscuit.atmosphera.gale.mixin;

import com.solarbiscuit.atmosphera.gale.waving.WavingKindCache;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * One int per block state: the waving class Gale last looked up for it, stamped with the waving table's version
 * ({@code version << 8 | kind}). Lets chunk meshing read a state's class with one field read instead of a
 * ThreadLocal memo and a block-state id lookup (0.1.9). Client only.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseMixin implements WavingKindCache {
    @Unique
    private int gale$packedKind;

    @Override
    public int gale$packedKind() {
        return this.gale$packedKind;
    }

    @Override
    public void gale$setPackedKind(int packed) {
        this.gale$packedKind = packed;
    }
}
