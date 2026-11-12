package com.solarbiscuit.atmosphera.gale.flight;

import com.solarbiscuit.atmosphera.gale.GaleConfig;
import com.solarbiscuit.atmosphera.lib.net.ServerSettings;
import com.solarbiscuit.atmosphera.lib.wind.Wind;
import com.solarbiscuit.atmosphera.lib.wind.WindVector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

/**
 * Client only (0.10.0): the wind on the local player's glide. The local player's elytra movement is computed on the
 * client ({@code LivingEntity.travel}), so this is where it can be changed; the server checks flying movement
 * generously and the effect is bounded well inside that ({@link FlightWind}).
 * <p>
 * Other mods' gliders that report {@code isFallFlying} get it for free.
 */
public final class FlightClient {
    private static final WindVector WIND = new WindVector();
    private static final double[] DV = new double[2];

    private FlightClient() {
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.addListener(FlightClient::onClientTick);
    }

    /**
     * START of the client tick: that is before the tick's entity update, so the nudge is in the velocity that
     * {@code LivingEntity.travel} then uses this same tick (it flies on {@code getDeltaMovement}, and the player's
     * tick runs inside the level's entity tick, after this event). At END the change would only act one tick later,
     * after vanilla had already moved the player on the old velocity.
     */
    private static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.START) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.isPaused() || !player.isFallFlying() || player.isPassenger()
                || player.isInWater() || player.isInLava()) {
            return;
        }
        // Fairness: only when the server announced it (singleplayer's own server does, with mode AUTO), and when
        // this player did not decline it. A server without Gale never announces, so nothing changes there.
        if (ServerSettings.get("gale", "flight", 0f) <= 0.5f || !GaleConfig.elytra.get()) {
            return;
        }
        double strength = ServerSettings.get("gale", "flight_strength", 1f);
        if (strength <= 0.0) {
            return;
        }
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        Wind.sample(mc.level, x, y, z, 0f, WIND);
        double ground = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, player.getBlockX(), player.getBlockZ());
        double speed = FlightWind.windSpeed(WIND.strength, y - ground);
        Vec3 v = player.getDeltaMovement();
        FlightWind.nudge(v.x, v.z, WIND.dirX * speed, WIND.dirZ * speed, strength, DV);
        player.setDeltaMovement(v.x + DV[0], v.y, v.z + DV[1]);
    }
}
