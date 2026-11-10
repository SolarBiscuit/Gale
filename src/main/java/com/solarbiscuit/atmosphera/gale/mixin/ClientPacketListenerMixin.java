package com.solarbiscuit.atmosphera.gale.mixin;

import com.solarbiscuit.atmosphera.gale.waving.Shockwaves;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gale 0.6.0: every explosion the client is told about (singleplayer included: the integrated server sends the same
 * packet) starts a shockwave in the plants. Runs on the client thread, after vanilla has handled the packet.
 * Optional ({@code require = 0}): without it only the shockwaves are lost.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "handleExplosion", at = @At("TAIL"), require = 0)
    private void gale$shockwave(ClientboundExplodePacket packet, CallbackInfo ci) {
        Shockwaves.add(packet.getX(), packet.getY(), packet.getZ(), packet.getPower());
    }
}
