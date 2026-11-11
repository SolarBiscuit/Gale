package com.solarbiscuit.atmosphera.gale.weather;

import com.solarbiscuit.atmosphera.gale.GaleConfig;
import com.solarbiscuit.atmosphera.gale.waving.WavingFeatures;
import com.solarbiscuit.atmosphera.lib.wind.Wind;
import com.solarbiscuit.atmosphera.lib.wind.WindVector;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RotationSegment;

/**
 * Gale 0.7.0: banner flags in the wind. Vanilla swings the flag with a small fixed sine of game time
 * ({@code (-0.0125 + 0.01 cos(2 pi f)) pi}); this replaces it with a lift from the wind across the banner's face
 * (the free end blows downwind) plus a flutter that grows with the wind and follows Gale's sway time (storm tempo,
 * dev clock override). Render thread only.
 * <p>
 * Geometry ({@code BannerRenderer.render}): the pose is yawed by {@code yaw}, then scaled (2/3, -2/3, -2/3); a positive
 * {@code flag.xRot} moves the flag's free end towards {@code R_y(yaw) (0, 0, -1) = (-sin yaw, 0, -cos yaw)}.
 */
public final class BannerWind {
    private static final WindVector WIND = new WindVector();
    /** Flutter: 260 cycles per 200 s of sway time (whole cycles per wrap), about 0.4 Hz at the default speed. */
    private static final double FLUTTER_CYCLES = 260.0 / 200.0;
    private static final float MAX_LIFT = 0.6f;

    private BannerWind() {
    }

    /** The flag angle for this banner this frame, or {@code vanilla} when the option is off or there is no level. */
    public static float flagAngle(BannerBlockEntity banner, float partialTick, float vanilla) {
        Level level = banner.getLevel();
        if (level == null || !GaleConfig.banners.get()) {
            return vanilla;
        }
        BlockState state = banner.getBlockState();
        float yawDeg;
        if (state.getBlock() instanceof BannerBlock) {
            yawDeg = -RotationSegment.convertToDegrees(state.getValue(BannerBlock.ROTATION));
        } else if (state.getBlock() instanceof WallBannerBlock) {
            yawDeg = -state.getValue(WallBannerBlock.FACING).toYRot();
        } else {
            return vanilla;
        }
        BlockPos pos = banner.getBlockPos();
        Wind.sample(level, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, partialTick, WIND);
        float amount = (float) Math.min(2.0, GaleConfig.amplitude.get());
        float drive = Math.min(WIND.strength, 1.6f) * amount;
        float yaw = yawDeg * Mth.DEG_TO_RAD;
        // Wind component through the banner's face, towards its back (+: the free end blows to the back).
        float through = WIND.dirX * -Mth.sin(yaw) + WIND.dirZ * -Mth.cos(yaw);
        float lift = Mth.clamp(0.40f * drive * through, -MAX_LIFT, MAX_LIFT);
        double phase = WavingFeatures.swayTime() * FLUTTER_CYCLES
                + ((pos.getX() * 7 + pos.getY() * 9 + pos.getZ() * 13) & 1023) * 0.0619;
        float flutter = (0.01f * Mth.PI + 0.045f * drive) * (float) Math.cos(Mth.TWO_PI * phase);
        return -0.0125f * Mth.PI + lift + flutter;
    }
}
