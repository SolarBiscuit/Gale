package com.solarbiscuit.atmosphera.gale.waving;

/**
 * Implemented by every block state (Gale's {@code BlockStateBaseMixin}): the cached waving class, packed with the
 * version of the table it came from. Read and written from chunk-builder threads; one int, so a reader never sees a
 * class from one write and a version from another, and racing writers write the same value.
 */
public interface WavingKindCache {
    int gale$packedKind();

    void gale$setPackedKind(int packed);
}
