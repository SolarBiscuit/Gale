package com.solarbiscuit.atmosphera.gale;

import com.solarbiscuit.atmosphera.gale.waving.WavingFeatures;
import com.solarbiscuit.atmosphera.gale.waving.WavingLoader;
import com.solarbiscuit.atmosphera.gale.waving.WavingTable;
import com.solarbiscuit.atmosphera.gale.wind.GaleWind;
import com.solarbiscuit.atmosphera.lib.wind.Wind;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gale: wind and waving foliage for the Atmosphera suite. Everything goes through the AtmospheraLib API: the config
 * and options tab, four waving features (leaves, plants, hanging, aquatic), their vertex bits and vertex snippet, and
 * a richer wind provider that replaces the library's default. Client-only; on a dedicated server it does nothing.
 */
@Mod(Gale.MOD_ID)
public final class Gale {
    public static final String MOD_ID = "gale";
    public static final Logger LOGGER = LoggerFactory.getLogger("Gale");
    /** Above the library default (0); leaves room for a pack-specific provider to win over Gale. */
    public static final int WIND_PRIORITY = 100;

    public Gale() {
        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) {
            LOGGER.info("Gale is client-only; inactive on this dedicated server.");
            return;
        }
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        GaleConfig.register();
        WavingFeatures.register();
        Wind.register(GaleWind.INSTANCE, WIND_PRIORITY);
        modBus.addListener((RegisterClientReloadListenersEvent e) -> e.registerReloadListener(new WavingLoader()));
        MinecraftForge.EVENT_BUS.addListener((LevelEvent.Unload e) -> GaleWind.INSTANCE.clearCaches());
        // LOW: after AtmospheraLib has stepped its world clock for this frame (it listens at NORMAL).
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOW, false, TickEvent.RenderTickEvent.class, e -> {
            if (e.phase == TickEvent.Phase.START) {
                WavingFeatures.stepSwayTime();
            }
        });
        // Forge reassigns block-state ids from the registry snapshot when joining a world or server.
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn e) -> WavingTable.index("login"));
    }
}
