package com.solarbiscuit.atmosphera.gale.waving;

import com.solarbiscuit.atmosphera.gale.Gale;
import com.solarbiscuit.atmosphera.gale.GaleConfig;
import com.solarbiscuit.atmosphera.gale.wind.GaleWind;
import com.solarbiscuit.atmosphera.lib.feature.AtmosFeature;
import com.solarbiscuit.atmosphera.lib.feature.Capability;
import com.solarbiscuit.atmosphera.lib.gpu.FrameUniforms;
import com.solarbiscuit.atmosphera.lib.mesh.BitField;
import com.solarbiscuit.atmosphera.lib.mesh.BlockMeshHook;
import com.solarbiscuit.atmosphera.lib.mesh.MeshHooks;
import com.solarbiscuit.atmosphera.lib.mesh.VertexBits;
import com.solarbiscuit.atmosphera.lib.options.AtmosOption;
import com.solarbiscuit.atmosphera.lib.shader.ShaderHook;
import com.solarbiscuit.atmosphera.lib.shader.ShaderSnippets;
import com.solarbiscuit.atmosphera.lib.wind.AtmosClock;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The four waving features. Each block gets a 2-bit material {@code wave_class} (1 leaves, 2 plants and hanging,
 * 3 aquatic) and, for the anchored classes, a per-vertex light bit {@code anchor} that pins the vertices on the edge
 * a plant grows from: the bottom for plants and water plants, the top for hanging blocks. Only the first block of a
 * column is anchored (a block above/below of the same class continues the column), so stacked plants bend as one.
 * <p>
 * The vertex snippet moves every other vertex of a classified block by {@code gale_offset(worldPos)}, a pure function
 * of world position and the frame uniforms: vertices shared by neighbouring blocks get the same offset, so nothing
 * cracks. The offset is capped at 0.2 blocks, inside Xenon's section culling margin.
 */
public final class WavingFeatures {
    /** Material wave_class values (shader side); 0 = not waving. */
    private static final int CLASS_LEAVES = 1;
    private static final int CLASS_FLEX = 2;
    private static final int CLASS_AQUATIC = 3;
    /** Wrap of the sway time in gale_Wave.x. Every frequency below is a whole number of cycles per wrap. */
    private static final double SWAY_WRAP = 200.0;
    /** The sway amount and speed sliders read 100% at 30% of 0.1.x's motion (2026-11-02: 70% calmer by default). */
    private static final double AMOUNT_SCALE = 0.3;
    private static final double SPEED_SCALE = 0.3;
    /** Render thread: sway time in seconds x speed, wrapped at SWAY_WRAP. */
    private static double swayTime;

    private static BitField waveClass;
    private static BitField anchor;

    private WavingFeatures() {
    }

    public static void register() {
        waveClass = VertexBits.material(Gale.MOD_ID, "wave_class", 2);
        anchor = VertexBits.light(Gale.MOD_ID, "anchor", 1);
        FrameUniforms.vec4("gale_Wave", "x: sway time (s x speed, wraps at 200), y: amplitude, "
                + "z: wind openness at the camera, w: sea level", WavingFeatures::writeWave);
        FrameUniforms.vec4("gale_Lod", "x: sway on Voxy LODs, y: LOD sway distance (blocks), z: canopy shimmer strength",
                WavingFeatures::writeLod);

        String cls = waveClass.glslMacro();
        String anc = anchor.glslMacro();
        // n1/n2: cycles per 200 s (0.55 and 1.25 Hz for leaves), amp: blocks at energy 1, lean: steady bend
        // downwind per unit of wind strength, windy: 0 = ignores the wind (water current), 1 = follows it.
        feature("waving_leaves", GaleConfig.leaves, WavingTable.LEAVES, CLASS_LEAVES, false, """
                    if (%s(atmos_MaterialBits) == 1u) {
                        position += gale_offset(atmos_worldPos(position), 110.0, 250.0, 0.08, 0.0, 1.0);
                    }
                """.formatted(cls));
        // Plants and hanging blocks share wave_class 2 (the anchor bit says which edge is pinned). Whichever of the
        // two features is active emits the code once.
        String flex = """
                #ifndef GALE_FLEX
                #define GALE_FLEX
                    if (%s(atmos_MaterialBits) == 2u && %s(atmos_LightBits) == 0u) {
                        position += gale_offset(atmos_worldPos(position), 90.0, 210.0, 0.13, 0.45, 1.0);
                    }
                #endif
                """.formatted(cls, anc);
        feature("waving_plants", GaleConfig.plants, WavingTable.PLANTS, CLASS_FLEX, true, flex);
        feature("waving_hanging", GaleConfig.hanging, WavingTable.HANGING, CLASS_FLEX, true, flex);
        feature("waving_aquatic", GaleConfig.aquatic, WavingTable.AQUATIC, CLASS_AQUATIC, true, """
                    if (%s(atmos_MaterialBits) == 3u && %s(atmos_LightBits) == 0u) {
                        position += gale_offset(atmos_worldPos(position), 40.0, 100.0, 0.11, 0.0, 0.15);
                    }
                """.formatted(cls, anc));

        // Voxy LOD terrain (AtmospheraLib compat.voxy): the same gale_offset, then a canopy shading wave further out
        lod(features[0], cls, 1, "110.0, 250.0, 0.08, 0.0, 1.0", 0.08, false);
        lod(features[1], cls, 2, "90.0, 210.0, 0.13, 0.45, 1.0", 0.13, true);
        lod(features[2], cls, 2, "90.0, 210.0, 0.13, 0.45, 1.0", 0.13, true);
        lod(features[3], cls, 3, "40.0, 100.0, 0.11, 0.0, 0.15", 0.11, true);
    }

    /**
     * Sway on Voxy LODs, in three zones by camera distance d (see GALE-REPORT.md):
     * <ul>
     *   <li>d up to the end of the vanilla-to-LOD transition: exactly the vertex offset vanilla blocks get, so the
     *       dithered transition shows no seam;</li>
     *   <li>further out: the offset fades with its size on screen (gone once the motion would be under ~0.35 px, where
     *       it only shimmers), and a low-frequency shading wave from Gale's own wind terms takes over;</li>
     *   <li>the last 20% of the LOD sway distance: both fade out.</li>
     * </ul>
     * Offsets only move quads Voxy keeps one voxel wide (LOD 0 foliage, leaf cards), so merged quads never crack; on
     * merged plant strips only the top edge moves. The shading wave is evaluated per fragment, so coarse merged quads
     * do not alias.
     */
    private static void lod(AtmosFeature feature, String cls, int classValue, String params, double amplitude, boolean anchored) {
        if (feature == null) {
            return;
        }
        ShaderSnippets.register(feature, ShaderHook.LOD_DECL, ShaderSnippets.PASS_ALL, 0, DECL + LOD_DECL);
        String pinned = anchored
                ? "((v.quadFlags & VOXY_EXT_QUAD_UNMERGED) != 0u"
                        + " ? ((voxyExt_anchor(v.quadFlags) == 1u && v.voxelFracY < 0.1) || (voxyExt_anchor(v.quadFlags) == 2u && v.voxelFracY > 0.9))"
                        + " : v.voxelFracY < 0.1)"
                : "false";
        String merged = anchored ? "" : "                if ((v.quadFlags & VOXY_EXT_QUAD_UNMERGED) == 0u) galeGeo = 0.0;\n";
        // Plants and hanging blocks share class 2: whichever of the two is active emits it once (like GALE_FLEX)
        ShaderSnippets.register(feature, ShaderHook.LOD_VERTEX, ShaderSnippets.PASS_ALL, 0, """
                #ifndef GALE_LOD_CLASS_%2$d
                #define GALE_LOD_CLASS_%2$d
                    if (%1$s(atmos_MaterialBits) == %2$du && gale_Lod.x > 0.5) {
                        float galeD = length(position);
                        float galeZone = gale_lodZone(galeD);
                        if (galeZone > 0.0) {
                            float galeGeo = galeZone * gale_lodPixelWeight(galeD, v.blocksPerPixel, %5$s * gale_Wave.y);
                %4$s                if (galeGeo > 0.0 && !%3$s) {
                                position += gale_offset(atmos_worldPos(position), %6$s) * galeGeo;
                            }
                            v.vary.x = max(v.vary.x, galeZone * (1.0 - galeGeo) * smoothstep(atmos_Lod.y, 1.5 * atmos_Lod.y, galeD));
                        }
                    }
                #endif
                """.formatted(cls, classValue, pinned, merged, glslFloat(amplitude * 1.5), params));
        ShaderSnippets.register(feature, ShaderHook.LOD_FRAGMENT, ShaderSnippets.PASS_SOLID | ShaderSnippets.PASS_CUTOUT, 0, """
                #ifndef GALE_LOD_SHIMMER
                #define GALE_LOD_SHIMMER
                    if (%s(atmos_MaterialBits) != 0u && f.vary.x > 0.001 && gale_Lod.z > 0.0) {
                        f.colour.rgb *= 1.0 + gale_Lod.z * gale_canopyWave(atmos_worldPos(f.relPos)) * f.vary.x;
                    }
                #endif
                """.formatted(cls));
    }

    private static String glslFloat(double value) {
        return String.format(java.util.Locale.ROOT, "%.4f", value);
    }

    private static void feature(String name, AtmosOption<Boolean> toggle, byte kind, int classValue, boolean anchored,
                                String code) {
        AtmosFeature registered = featureImpl(name, toggle, kind, classValue, anchored, code);
        features[featureCount++] = registered;
    }

    private static final AtmosFeature[] features = new AtmosFeature[4];
    private static int featureCount;

    private static AtmosFeature featureImpl(String name, AtmosOption<Boolean> toggle, byte kind, int classValue, boolean anchored,
                                String code) {
        AtmosFeature.Builder builder = AtmosFeature.builder(Gale.MOD_ID, name)
                .provides(Capability.WAVING_FOLIAGE)
                .switchedBy(GaleConfig.waving)
                .enabledWhen(() -> GaleConfig.waving.enabled().get() && toggle.get());
        AtmosFeature feature = anchored
                ? builder.uses(AtmosFeature.Uses.TERRAIN_SHADER, AtmosFeature.Uses.MESH_BITS, AtmosFeature.Uses.LIGHT_BITS).register()
                : builder.uses(AtmosFeature.Uses.TERRAIN_SHADER, AtmosFeature.Uses.MESH_BITS).register();
        MeshHooks.registerBlock(feature, anchored ? anchoredHook(kind, classValue) : swayHook(kind, classValue));
        ShaderSnippets.register(feature, ShaderHook.VERT_DECL, ShaderSnippets.PASS_ALL, 0, DECL);
        ShaderSnippets.register(feature, ShaderHook.VERT_POSITION, ShaderSnippets.PASS_ALL, 0, code);
        return feature;
    }

    /**
     * Leaves sway as a whole block; plants, hanging and aquatic blocks pin the growing edge of a column's first block.
     * One class for all four features, so the lib's per-block hook call stays bimorphic (Gale and Torrente) and the
     * JIT can inline it.
     */
    private static WaveHook swayHook(byte kind, int classValue) {
        return new WaveHook(kind, waveClass.set(0, classValue), false);
    }

    private static WaveHook anchoredHook(byte kind, int classValue) {
        return new WaveHook(kind, waveClass.set(0, classValue), true);
    }

    private static final class WaveHook implements BlockMeshHook {
        private final byte kind;
        private final int material;
        private final boolean anchored;
        private final int pinned;
        private final boolean top;
        private final int towardsRoot;

        WaveHook(byte kind, int material, boolean anchored) {
            this.kind = kind;
            this.material = material;
            this.anchored = anchored;
            this.pinned = anchored ? anchor.set(0, 1) : 0;
            this.top = kind == WavingTable.HANGING;
            this.towardsRoot = top ? 1 : -1;
        }

        @Override
        public int materialBits(BlockState state, BlockPos pos, BlockAndTintGetter level) {
            return WavingTable.kind(state) == kind ? material : 0;
        }

        @Override
        public int lightBits(BlockState state, BlockPos pos, BlockAndTintGetter level) {
            if (!anchored || WavingTable.kind(state) != kind) {
                return 0;
            }
            // Inside a column (same class towards the root): this block's root edge moves like the block it
            // touches, so leave it free.
            return WavingTable.kindAt(level, pos, towardsRoot) == kind ? 0 : pinned;
        }

        @Override
        public boolean perVertex(BlockState state) {
            return anchored && WavingTable.kind(state) == kind;
        }

        @Override
        public int lodMaterialBits(BlockState state) {
            return WavingTable.kind(state) == kind ? material : 0;
        }

        /** LOD 0 quads stay one voxel wide (per-vertex sway); plants pin the bottom of a column, hanging blocks the top. */
        @Override
        public int lodShape(BlockState state) {
            if (WavingTable.kind(state) != kind) {
                return 0;
            }
            int pin = !anchored ? 0 : (top ? BlockMeshHook.LOD_ANCHOR_TOP : BlockMeshHook.LOD_ANCHOR_BOTTOM);
            return BlockMeshHook.LOD_UNMERGED | pin;
        }

        @Override
        public int vertexLightBits(BlockState state, int bits, float x, float y, float z) {
            if (anchor.get(bits) == 0) {
                return bits;
            }
            boolean rootEdge = top ? y > 0.9f : y < 0.1f;
            return rootEdge ? bits : anchor.set(bits, 0);
        }
    }

    /**
     * Render thread, once per rendered frame (RenderTickEvent, after the lib has stepped AtmosClock): advances the sway
     * time from the smoothed world clock. Raw game time raced while an integrated server caught up (with Better Days
     * it is resent every tick); integrating here, not in the uniform writer, keeps the step once per frame however
     * often the uniform block is rewritten, and a speed change does not jump the phase.
     */
    public static void stepSwayTime() {
        swayTime = (swayTime + AtmosClock.deltaSeconds() * GaleConfig.speed.get() * SPEED_SCALE) % SWAY_WRAP;
    }

    /** Render thread, when the uniform block is written (also while the features are off, so it stays cheap). */
    private static void writeWave(float[] out) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        out[1] = (float) (GaleConfig.amplitude.get() * AMOUNT_SCALE);
        if (level == null) {
            out[0] = 0f;
            out[2] = 1f;
            out[3] = 63f;
            return;
        }
        out[0] = (float) swayTime;
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 eye = camera.getPosition();
        out[2] = GaleWind.INSTANCE.cameraOpenness(level, eye.x, eye.y, eye.z);
        out[3] = level.getSeaLevel();
    }

    private static void writeLod(float[] out) {
        out[0] = GaleConfig.lodSway.get() ? 1f : 0f;
        out[1] = (float) (GaleConfig.lodSwayDistance.get() * Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0);
        out[2] = GaleConfig.lodShimmer.get().floatValue();
    }

    /** LOD helpers (guarded like DECL): zone and pixel weights, and the canopy shading wave. */
    private static final String LOD_DECL = """
            #ifndef GALE_LOD_DECL
            #define GALE_LOD_DECL
            // 1 up to 80% of the LOD sway distance, fading to 0 at it.
            float gale_lodZone(float galeD) {
                return 1.0 - smoothstep(0.8 * gale_Lod.y, gale_Lod.y, galeD);
            }
            // Geometric sway weight: full inside the vanilla-to-LOD transition (it must match vanilla there), past it
            // faded with the motion's size on screen so sub-pixel offsets never shimmer.
            float gale_lodPixelWeight(float galeD, float galeBlocksPerPixel, float galeAmplitude) {
                if (galeD <= atmos_Lod.y || galeBlocksPerPixel <= 0.0) return 1.0;
                return smoothstep(0.25, 0.5, galeAmplitude / (galeD * galeBlocksPerPixel));
            }
            // Wind waves of light over distant canopies and fields: gale_offset's along-wind term (about 14 blocks) and
            // its gust envelope (about 78 blocks), the parts of the motion still visible far away. About -1..1.
            float gale_canopyWave(vec3 galeP) {
                vec4 galeAxes = gale_axisWeights();
                float galeCycle = gale_Wave.x * 0.0314159265;
                float galeGust = gale_travel(galeAxes, galeP.xz, galeCycle * 7.0, 0.08);
                float galeMain = gale_travel(galeAxes, galeP.xz, galeCycle * 110.0, 0.45);
                float galeEnergy = min((0.3 + 1.1 * atmos_Wind.z) * gale_Wave.z, 1.5);
                return (0.6 * galeMain + 0.4 * galeGust * atmos_Wind.w) * galeEnergy * gale_Wave.y;
            }
            #endif
            """;

    /**
     * Shared by the four features (guarded, so it is declared once whichever are active). Returns the offset of a
     * vertex at world position {@code p}. Phases use whole cycles per 200 s of sway time, so the wrap is seamless;
     * the spatial terms make neighbouring blocks move slightly out of step, as wind passing over them would.
     */
    private static final String DECL = """
            #ifndef GALE_DECL
            #define GALE_DECL
            // Travelling waves run along four fixed axes (+x, -x, +z, -z), weighted by how much the wind points along
            // each (the weights sum to 1). A wave along the wind vector itself shifts its phase by 0.45 |p| dTheta when
            // the wind turns; world positions are thousands of blocks, so every gust veer made foliage race at 20-250x
            // speed (Gale 0.1.0-0.1.7). Turning the wind now only moves the weights, slowly.
            vec4 gale_axisWeights() {
                vec4 galeW = max(vec4(atmos_Wind.x, -atmos_Wind.x, atmos_Wind.y, -atmos_Wind.y), 0.0);
                galeW *= galeW;
                return galeW / max(galeW.x + galeW.y + galeW.z + galeW.w, 1e-4);
            }
            // sum over the axes of w_k * sin(base - along_k * k), with along_k = (x, -x, z, -z)
            float gale_travel(vec4 galeW, vec2 galeXZ, float galeBase, float galeK) {
                vec4 galeAlong = vec4(galeXZ.x, -galeXZ.x, galeXZ.y, -galeXZ.y) * galeK;
                return dot(galeW, sin(vec4(galeBase) - galeAlong));
            }
            vec3 gale_offset(vec3 galeP, float galeN1, float galeN2, float galeAmp, float galeLean, float galeWindy) {
                vec2 galeDir = atmos_Wind.xy;
                vec4 galeAxes = gale_axisWeights();
                float galeCycle = gale_Wave.x * 0.0314159265;
                float galeAlt = clamp((galeP.y - gale_Wave.w) * 0.0104, 0.0, 0.6);
                float galeEnergy = mix(0.6, (0.3 + 1.1 * atmos_Wind.z) * gale_Wave.z * (1.0 + galeAlt), galeWindy);
                float galeP1 = galeCycle * galeN1;
                float galeP2 = galeCycle * galeN2 + galeP.x * 0.83 - galeP.z * 0.61 + galeP.y * 0.5;
                float galeP3 = galeCycle * (galeN1 + galeN2) + galeP.z * 0.7 - galeP.x * 0.4 + 1.7;
                float galeGust = 0.75 + 0.5 * atmos_Wind.w * (0.5 + 0.5 * gale_travel(galeAxes, galeP.xz, galeCycle * 7.0, 0.08));
                float galeMain = (0.65 * gale_travel(galeAxes, galeP.xz, galeP1, 0.45) + 0.35 * sin(galeP2)) * galeGust
                        + galeLean * atmos_Wind.z;
                vec2 galeXZ = galeDir * galeMain + vec2(-galeDir.y, galeDir.x) * (0.3 * sin(galeP3));
                vec3 galeOff = vec3(galeXZ.x, 0.12 * sin(galeP3 + galeP1 - 0.45 * (galeP.x + galeP.z)), galeXZ.y) * (galeAmp * galeEnergy * gale_Wave.y);
                float galeLen = length(galeOff);
                return galeLen > 0.2 ? galeOff * (0.2 / galeLen) : galeOff;
            }
            #endif
            """;
}
