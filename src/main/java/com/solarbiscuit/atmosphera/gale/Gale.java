package com.solarbiscuit.atmosphera.gale;

import com.solarbiscuit.atmosphera.gale.wind.GaleWind;
import com.solarbiscuit.atmosphera.lib.net.ServerSettings;
import com.solarbiscuit.atmosphera.lib.wind.Wind;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gale: wind and waving foliage for the Atmosphera suite. Everything goes through the AtmospheraLib API: the config
 * and options tab, four waving features (leaves, plants, hanging, aquatic), their vertex bits and vertex snippet, and
 * a richer wind provider that replaces the library's default.
 * <p>
 * 0.9.0: optional on both sides. The common part (this class, {@link GaleWind}, the server config and the settings
 * handshake) runs on a dedicated server too; everything that draws lives in {@link GaleClient}, which only the client
 * ever loads. The wind is a pure function of game time, weather, dimension and position, so both sides agree
 * without syncing it.
 */
@Mod(Gale.MOD_ID)
public final class Gale {
    public static final String MOD_ID = "gale";
    public static final Logger LOGGER = LoggerFactory.getLogger("Gale");
    /** Above the library default (0); leaves room for a pack-specific provider to win over Gale. */
    public static final int WIND_PRIORITY = 100;

    public Gale() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        GaleServerConfig.register();
        Wind.register(GaleWind.INSTANCE, WIND_PRIORITY);
        // What clients are told about this server's gameplay settings (sent at login, and again on a change).
        ServerSettings.publish(MOD_ID, GaleServerConfig::settings);
        modBus.addListener((ModConfigEvent.Reloading e) -> {
            if (e.getConfig().getSpec() == GaleServerConfig.SPEC) {
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (server != null) {
                    // The reload comes from the file watcher's thread; players are sent to from the server thread.
                    server.execute(() -> ServerSettings.resend(server, MOD_ID));
                }
            }
        });
        MinecraftForge.EVENT_BUS.addListener((LevelEvent.Unload e) -> GaleWind.INSTANCE.clearCaches());
        if (FMLEnvironment.dist == Dist.CLIENT) {
            GaleClient.init(modBus);
        } else {
            LOGGER.info("Gale on a dedicated server: wind and the flight settings only (no waving, no rendering).");
        }
    }
}
