package com.solarbiscuit.atmosphera.gale.weather;

import com.solarbiscuit.atmosphera.gale.GaleConfig;
import com.solarbiscuit.atmosphera.gale.wind.GaleWind;
import com.solarbiscuit.atmosphera.lib.wind.Wind;
import com.solarbiscuit.atmosphera.lib.world.ColumnField;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

/**
 * Gale 0.7.1: shelter for the renderer-side wind users (banners, Connectible Chains) from the column field's caster
 * top, the same test as the foliage shader's {@code gale_shelter}, so a banner and the grass under the same roof calm
 * down by the same amount. {@link GaleWind#sample} only counts a position more than 3 blocks below the motion-blocking
 * surface as sheltered (it must stay safe from any thread), so in 0.7.0 a banner under a roof 2 blocks above it still
 * blew. Render thread only (the column field is); no allocation.
 * <p>
 * The field is not started here: Gale keeps it running while a waving class is active and shelter is on. Without it
 * (every waving class off, or outside its window) the factor is 1 and the coarse heightmap test alone applies, as in
 * 0.7.0.
 */
public final class LocalShelter {
    private LocalShelter() {
    }

    /** Wind strength factor at a position, 0.35..1 (1 = open, or no field data). */
    public static float factor(Level level, double x, double y, double z) {
        if (!GaleConfig.shelterOn.get() || !ColumnField.ready()) {
            return 1f;
        }
        float amount = GaleConfig.shelter.get().floatValue();
        if (amount <= 0f) {
            return 1f;
        }
        int raw = ColumnField.casterRaw(Mth.floor(x), Mth.floor(z));
        if (raw == 0) {
            return 1f;
        }
        float top = ColumnField.top(raw);
        float t = Mth.clamp((top - (float) y - 0.5f) / 2.0f, 0f, 1f);
        float cover = t * t * (3f - 2f * t); // smoothstep(y + 0.5, y + 2.5, top), as in gale_shelter
        if (cover <= 0f) {
            return 1f;
        }
        // Gale's own sample already took 35% deep under the surface: do not count the same roof twice.
        if (Wind.provider() == GaleWind.INSTANCE && GaleWind.deepSheltered(level, x, y, z)) {
            return 1f;
        }
        return 1f - 0.65f * amount * cover;
    }
}
