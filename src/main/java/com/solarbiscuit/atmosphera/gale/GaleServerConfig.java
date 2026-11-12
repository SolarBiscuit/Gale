package com.solarbiscuit.atmosphera.gale;

import com.solarbiscuit.atmosphera.lib.options.AtmosPaths;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.Map;

/**
 * {@code config/Atmosphera/gale-server.toml} (0.9.0): what Gale does to gameplay. Common code (a plain Forge spec;
 * the library's {@code AtmosConfig} is client-only) that loads on a dedicated server and in singleplayer.
 * <p>
 * {@code flight.mode}: AUTO is on when the server is the integrated one (singleplayer, LAN) and off on a dedicated
 * server, until its owner turns it on. The server announces the result to clients through the library's settings
 * handshake ({@link #settings}); a client applies the elytra wind only when it was announced.
 */
public final class GaleServerConfig {
    /** When the wind acts on a gliding player. */
    public enum FlightMode {
        AUTO, ON, OFF
    }

    static final ForgeConfigSpec SPEC;
    private static final ForgeConfigSpec.EnumValue<FlightMode> MODE;
    private static final ForgeConfigSpec.DoubleValue STRENGTH;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("Flight: the wind pushes a gliding player (elytra and other fall-flying gear).").push("flight");
        MODE = builder.comment("AUTO: on in singleplayer and LAN, off on a dedicated server. ON / OFF: always.")
                .defineEnum("mode", FlightMode.AUTO);
        STRENGTH = builder.comment("How strongly the wind acts on a glider; 1.0 is the designed amount, 0 is none.")
                .defineInRange("strength", 1.0, 0.0, 2.0);
        builder.pop();
        SPEC = builder.build();
    }

    private GaleServerConfig() {
    }

    /** Both sides, from the mod constructor. */
    static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC, AtmosPaths.relative("gale-server.toml"));
    }

    /** Whether the wind acts on gliders on this server. */
    public static boolean flightOn(MinecraftServer server) {
        FlightMode mode;
        try {
            mode = MODE.get();
        } catch (IllegalStateException notLoaded) {
            mode = FlightMode.AUTO;
        }
        return switch (mode) {
            case ON -> true;
            case OFF -> false;
            case AUTO -> server != null && !server.isDedicatedServer();
        };
    }

    public static float flightStrength() {
        try {
            return STRENGTH.get().floatValue();
        } catch (IllegalStateException notLoaded) {
            return 1f;
        }
    }

    /** The handshake payload for {@code ServerSettings}: what clients learn about this server. */
    static Map<String, Float> settings() {
        boolean on = flightOn(ServerLifecycleHooks.getCurrentServer());
        return Map.of("flight", on ? 1f : 0f, "flight_strength", flightStrength());
    }
}
