package com.solarbiscuit.atmosphera.gale.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.solarbiscuit.atmosphera.gale.weather.BannerWind;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BannerRenderer;
import net.minecraft.client.resources.model.Material;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Banner flags move with the wind ({@link BannerWind}). Optional ({@code require = 0}): a mod that rewrites the
 * renderer only loses the effect.
 * <p>
 * 0.7.2: two hooks instead of 0.7.0's one inside {@code render}. Optimized Block Entities (OBE, in RatPack) cancels
 * {@code render} at its head and draws the flag itself (vanilla's game-time angle, then the static
 * {@code renderPatterns}), so 0.7.0's injection never ran there and banners kept vanilla's swing. Now the head of
 * {@code render} only notes which banner and flag this renderer is drawing (priority 500, so it runs before a cancelling
 * mod at the default 1000), and the head of {@code renderPatterns}, which vanilla and OBE both call with the flag after
 * setting its angle, replaces the angle when the part is that flag.
 */
@Mixin(value = BannerRenderer.class, priority = 500)
public abstract class BannerRendererMixin {
    @Shadow
    @Final
    private ModelPart flag;

    @Inject(method = "render(Lnet/minecraft/world/level/block/entity/BannerBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At("HEAD"), require = 0)
    private void gale$beginBanner(BannerBlockEntity banner, float partialTick, PoseStack pose, MultiBufferSource buffers,
                                  int light, int overlay, CallbackInfo ci) {
        BannerWind.begin(banner, partialTick, this.flag);
    }

    @Inject(method = "renderPatterns(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/model/geom/ModelPart;Lnet/minecraft/client/resources/model/Material;ZLjava/util/List;)V",
            at = @At("HEAD"), require = 0)
    private static void gale$flagInWind(PoseStack pose, MultiBufferSource buffers, int light, int overlay, ModelPart part,
                                        Material material, boolean banner, List<?> patterns, CallbackInfo ci) {
        if (banner) {
            BannerWind.apply(part);
        }
    }
}
