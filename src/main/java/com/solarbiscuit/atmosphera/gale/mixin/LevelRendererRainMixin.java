package com.solarbiscuit.atmosphera.gale.mixin;

import com.solarbiscuit.atmosphera.gale.weather.RainTilt;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gale 0.4.0: rain and snow lean with the wind ({@link RainTilt}). Reads the calls vanilla already makes in
 * {@code renderSnowAndRain}, without locals or MixinExtras (Forge 1.20.1 does not ship it):
 * <ul>
 *   <li>{@code MutableBlockPos.set(III)} #0 is {@code set(x, rainBottom, z)}: the column's bottom;</li>
 *   <li>#1 is the rain branch's light position, #2 the snow branch's: which slope the next four vertices use;</li>
 *   <li>{@code BufferBuilder.vertex(DDD)}: the camera-relative corners, moved upwind by their height above the bottom.</li>
 * </ul>
 * Each argument is returned unchanged except the vertex x and z. Every hook but HEAD is optional ({@code require = 0}),
 * so a mod that rewrites the pass only loses the tilt. Xenon (fancy-graphics redirect), Oculus and Hybrid Aquatic
 * (its own rain in trenches, which stays upright) touch other parts of the method.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererRainMixin {
    private static final String RENDER = "renderSnowAndRain";
    private static final String SET_III = "Lnet/minecraft/core/BlockPos$MutableBlockPos;set(III)Lnet/minecraft/core/BlockPos$MutableBlockPos;";
    private static final String VERTEX = "Lcom/mojang/blaze3d/vertex/BufferBuilder;vertex(DDD)Lcom/mojang/blaze3d/vertex/VertexConsumer;";

    @Inject(method = RENDER, at = @At("HEAD"))
    private void gale$rainBegin(LightTexture lightTexture, float partialTick, double camX, double camY, double camZ,
                                CallbackInfo ci) {
        RainTilt.begin(camY);
    }

    @ModifyArg(method = RENDER, at = @At(value = "INVOKE", target = SET_III, ordinal = 0), index = 1, require = 0)
    private int gale$rainBottom(int y) {
        RainTilt.bottom(y);
        return y;
    }

    @ModifyArg(method = RENDER, at = @At(value = "INVOKE", target = SET_III, ordinal = 1), index = 1, require = 0)
    private int gale$rainColumn(int y) {
        RainTilt.precipitation(true);
        return y;
    }

    @ModifyArg(method = RENDER, at = @At(value = "INVOKE", target = SET_III, ordinal = 2), index = 1, require = 0)
    private int gale$snowColumn(int y) {
        RainTilt.precipitation(false);
        return y;
    }

    @ModifyArg(method = RENDER, at = @At(value = "INVOKE", target = VERTEX), index = 0, require = 0)
    private double gale$tiltX(double x, double y, double z) {
        return x + RainTilt.dx(y);
    }

    @ModifyArg(method = RENDER, at = @At(value = "INVOKE", target = VERTEX), index = 2, require = 0)
    private double gale$tiltZ(double x, double y, double z) {
        return z + RainTilt.dz(y);
    }
}
