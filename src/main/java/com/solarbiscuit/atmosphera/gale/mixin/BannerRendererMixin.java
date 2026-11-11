package com.solarbiscuit.atmosphera.gale.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.solarbiscuit.atmosphera.gale.weather.BannerWind;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BannerRenderer;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gale 0.7.0: banner flags move with the wind ({@link BannerWind}). Vanilla sets {@code flag.xRot} just before it
 * draws the patterns; this overwrites it there. Optional ({@code require = 0}): a mod that rewrites the renderer only
 * loses the effect.
 */
@Mixin(BannerRenderer.class)
public abstract class BannerRendererMixin {
    @Shadow
    @Final
    private ModelPart flag;

    @Inject(method = "render(Lnet/minecraft/world/level/block/entity/BannerBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/blockentity/BannerRenderer;renderPatterns(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/model/geom/ModelPart;Lnet/minecraft/client/resources/model/Material;ZLjava/util/List;)V"),
            require = 0)
    private void gale$flagInWind(BannerBlockEntity banner, float partialTick, PoseStack pose, MultiBufferSource buffers,
                                 int light, int overlay, CallbackInfo ci) {
        this.flag.xRot = BannerWind.flagAngle(banner, partialTick, this.flag.xRot);
    }
}
