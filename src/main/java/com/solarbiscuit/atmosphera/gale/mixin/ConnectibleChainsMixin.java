package com.solarbiscuit.atmosphera.gale.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.solarbiscuit.atmosphera.gale.weather.RopeWind;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gale 0.7.0: Connectible Chains spans swing in the wind ({@link RopeWind}). Applied only when the mod is loaded
 * ({@link GaleMixinPlugin}); optional ({@code require = 0}). The rotation goes in just before the mod's own
 * {@code mulPose}, after it has moved the pose to the span's start, so it turns about a world-space axis and the
 * chain and everything hung on it swing together. Gale does not compile against the mod.
 */
@Pseudo
@Mixin(targets = "com.evandev.connectiblechains.client.render.entity.ChainKnotEntityRenderer")
public abstract class ConnectibleChainsMixin {
    @Inject(method = "renderChainLink", remap = false, require = 0,
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;mulPose(Lorg/joml/Quaternionf;)V",
                    ordinal = 0, remap = true))
    private void gale$swingInWind(PoseStack pose, MultiBufferSource buffers, @Coerce Object data, CallbackInfo ci) {
        RopeWind.swing(pose, data);
    }
}
