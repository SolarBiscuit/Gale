package com.solarbiscuit.atmosphera.gale.weather;

import com.mojang.blaze3d.vertex.PoseStack;
import com.solarbiscuit.atmosphera.gale.Gale;
import com.solarbiscuit.atmosphera.gale.GaleConfig;
import com.solarbiscuit.atmosphera.gale.waving.WavingFeatures;
import com.solarbiscuit.atmosphera.lib.wind.Wind;
import com.solarbiscuit.atmosphera.lib.wind.WindVector;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

/**
 * Gale 0.7.0: Connectible Chains spans swing in the wind. The mod caches each span's baked geometry, so instead of
 * re-baking, the whole span (with its buntings, banners and hangings) is rotated about its chord, the line between
 * its two ends, before the mod applies its own local rotation. The origin is the span's start, so both ends stay in
 * place and only the sag swings sideways. Render thread only; no allocation per span.
 * <p>
 * The mod's {@code ChainData} is read through cached getters for its public fields, so Gale does not compile against
 * the mod; if they are missing, the feature switches itself off once with a warning.
 */
public final class RopeWind {
    private static final WindVector WIND = new WindVector();
    private static final Quaternionf ROTATION = new Quaternionf();
    /** Swing: 150 cycles per 200 s of sway time (whole cycles per wrap), slower than lanterns. */
    private static final double SWING_CYCLES = 150.0 / 200.0;
    private static final float MAX_ANGLE = 0.6f;

    private static Class<?> dataClass;
    private static MethodHandle startPos;
    private static MethodHandle endPos;
    private static boolean broken;

    private RopeWind() {
    }

    /** Called by the Connectible Chains mixin with the pose translated to the span's start. */
    public static void swing(PoseStack pose, Object data) {
        if (broken || !GaleConfig.connectibleChains.get() || data == null) {
            return;
        }
        float amount = GaleConfig.chainSway.get().floatValue();
        Level level = Minecraft.getInstance().level;
        if (amount <= 0f || level == null) {
            return;
        }
        Vec3 start;
        Vec3 end;
        try {
            if (data.getClass() != dataClass) {
                bind(data.getClass());
            }
            start = (Vec3) startPos.invoke(data);
            end = (Vec3) endPos.invoke(data);
        } catch (Throwable t) {
            broken = true;
            Gale.LOGGER.warn("Connectible Chains swing off: its chain data changed ({})", t.toString());
            return;
        }
        if (start == null || end == null) {
            return;
        }
        float ax = (float) (end.x - start.x);
        float ay = (float) (end.y - start.y);
        float az = (float) (end.z - start.z);
        float length = Mth.sqrt(ax * ax + ay * ay + az * az);
        if (length < 1e-3f) {
            return;
        }
        ax /= length;
        ay /= length;
        az /= length;
        // The sag hangs down; rotating it about the chord a by +theta moves it towards m = a x (0, -1, 0) = (az, 0, -ax).
        float horizontal = Mth.sqrt(ax * ax + az * az);
        if (horizontal < 0.2f) {
            return; // a nearly vertical span has no sag to swing
        }
        float mx = az / horizontal;
        float mz = -ax / horizontal;
        double midX = (start.x + end.x) * 0.5;
        double midY = (start.y + end.y) * 0.5;
        double midZ = (start.z + end.z) * 0.5;
        Wind.sample(level, midX, midY, midZ, Minecraft.getInstance().getFrameTime(), WIND);
        float cross = WIND.dirX * mx + WIND.dirZ * mz;
        float drive = Math.min(WIND.strength, 1.6f);
        double phase = WavingFeatures.swayTime() * SWING_CYCLES + ((int) Math.floor(midX * 3 + midZ * 5) & 1023) * 0.0619;
        float theta = amount * horizontal
                * (0.30f * drive * cross + 0.10f * WIND.gust * (float) Math.sin(Mth.TWO_PI * phase));
        theta = Mth.clamp(theta, -MAX_ANGLE, MAX_ANGLE);
        if (Math.abs(theta) < 1e-4f) {
            return;
        }
        pose.mulPose(ROTATION.identity().rotateAxis(theta, ax, ay, az));
    }

    private static void bind(Class<?> type) throws ReflectiveOperationException {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        startPos = lookup.unreflectGetter(type.getField("startPos"));
        endPos = lookup.unreflectGetter(type.getField("endPos"));
        dataClass = type;
    }
}
