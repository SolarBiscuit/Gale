package com.solarbiscuit.atmosphera.gale;

import com.solarbiscuit.atmosphera.gale.flight.FlightClient;
import com.solarbiscuit.atmosphera.gale.waving.WavingFeatures;
import com.solarbiscuit.atmosphera.gale.waving.WavingLoader;
import com.solarbiscuit.atmosphera.gale.waving.WavingTable;
import com.solarbiscuit.atmosphera.gale.wind.ClientOpenness;
import com.solarbiscuit.atmosphera.gale.wind.GaleWind;
import com.solarbiscuit.atmosphera.lib.state.AtmosState;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;

/**
 * Gale's client part (0.9.0, split out of {@link Gale}): the options tab, the waving features and their reload
 * listeners, the wind's client options and the elytra wind. Only {@link Gale} calls it, and only on the client, so a
 * dedicated server never loads this class or anything it references.
 */
final class GaleClient {
    private GaleClient() {
    }

    static void init(IEventBus modBus) {
        GaleConfig.register();
        WavingFeatures.register();
        // The options only a client has; the server runs the wind with the defaults.
        GaleWind.INSTANCE.setTuning(new GaleWind.Tuning() {
            @Override
            public boolean terrain() {
                return GaleConfig.terrain.get();
            }

            @Override
            public float stormStrength() {
                return GaleConfig.stormStrength.get().floatValue();
            }
        });
        FlightClient.register();
        modBus.addListener((RegisterClientReloadListenersEvent e) -> e.registerReloadListener(new WavingLoader()));
        MinecraftForge.EVENT_BUS.addListener((LevelEvent.Unload e) -> ClientOpenness.clear());
        // LOW: after AtmospheraLib has stepped its world clock for this frame (it listens at NORMAL).
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOW, false, TickEvent.RenderTickEvent.class, e -> {
            if (e.phase == TickEvent.Phase.START) {
                WavingFeatures.stepSwayTime();
            }
        });
        // Forge reassigns block-state ids from the registry snapshot when joining a world or server.
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn e) -> WavingTable.index("login"));
        // 0.7.3: tags are bound only when the server's tag packet arrives (never during the resource reload), so the
        // "#tag" entries of the waving files resolve here. The integrated server's own load is not the client's.
        MinecraftForge.EVENT_BUS.addListener((TagsUpdatedEvent e) -> {
            if (e.getUpdateCause() == TagsUpdatedEvent.UpdateCause.CLIENT_PACKET_RECEIVED) {
                WavingLoader.onTagsUpdated();
            }
        });
        // waving.blacklist is a list option the lib only remeshes for; this reclassifies first when it changed.
        AtmosState.addListener((state, tier) -> WavingLoader.onSettingsRefresh());
    }
}
