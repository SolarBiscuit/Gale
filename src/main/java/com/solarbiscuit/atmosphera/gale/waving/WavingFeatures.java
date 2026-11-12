package com.solarbiscuit.atmosphera.gale.waving;

import com.solarbiscuit.atmosphera.gale.Gale;
import com.solarbiscuit.atmosphera.gale.GaleConfig;
import com.solarbiscuit.atmosphera.gale.wind.ClientOpenness;
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
    /** 0.2.0: marks hanging blocks inside wave_class 2 (shared with plants), so they get their own response. */
    private static BitField hangingBit;
    /**
     * 0.6.3: hanging vertices that belong to a chain, scaled by the chain sway amount. Block stage: bit 0 = chain
     * above, bit 1 = chain below; vertex stage: 1 when the block is a chain, or the edge it shares is with one, so
     * a chain and the lantern under it agree on their shared vertices.
     */
    private static BitField chainMark;
    /**
     * 0.3.0: per-vertex lever arm of hanging blocks, distance below the chain's anchor in thirds of a block (0-6, 0 =
     * unknown: lever 1). Counted at most two blocks up (inside the slice's margin) and capped at two blocks, so the two
     * blocks sharing a vertex always agree on it, also across a section boundary.
     */
    private static BitField lever;
    private static final int LEVER_MAX = 2;

    private WavingFeatures() {
    }

    public static void register() {
        waveClass = VertexBits.material(Gale.MOD_ID, "wave_class", 2);
        anchor = VertexBits.light(Gale.MOD_ID, "anchor", 1);
        hangingBit = VertexBits.light(Gale.MOD_ID, "hanging", 1);
        lever = VertexBits.light(Gale.MOD_ID, "lever", 3);
        chainMark = VertexBits.light(Gale.MOD_ID, "chain", 2);
        FrameUniforms.vec4("gale_Chain", "x: chain sway amount (0 = chains hold still)", out -> {
            out[0] = GaleConfig.chainSway.get().floatValue();
        });
        FrameUniforms.vec4("gale_Contact", "x: entity bending strength, y: shelter strength, z: chain lever on, w: entities in gale_Push",
                WavingFeatures::writeContact);
        FrameUniforms.vec4("gale_Wave", "x: sway time (s x speed, wraps at 200), y: amplitude, "
                + "z: wind openness at the camera, w: sea level", WavingFeatures::writeWave);
        FrameUniforms.vec4("gale_Lod", "x: sway on Voxy LODs, y: LOD sway distance (blocks), z: canopy shimmer strength",
                WavingFeatures::writeLod);
        FrameUniforms.vec4("gale_Gust", "xy: gust patch scroll, coarse octave, zw: fine octave (blocks, wrapped at 4096)",
                WavingFeatures::writeGust);
        FrameUniforms.vec4("gale_Field", "x: gust patches on, y: wind sheen up close on, z: shockwave strength, "
                + "w: shockwaves in gale_Blast", WavingFeatures::writeField);
        for (int i = 0; i < Shockwaves.MAX; i++) {
            int n = i;
            FrameUniforms.vec4("gale_Blast" + i, "explosion " + i + ": xyz centre relative to the camera, "
                    + "w floor(final radius) * 32 + front radius (0 = none)", out -> Shockwaves.write(n, out));
        }

        String cls = waveClass.glslMacro();
        String anc = anchor.glslMacro();
        // n1/n2: cycles per 200 s (0.55 and 1.25 Hz for leaves), amp: blocks at energy 1, lean: steady bend
        // downwind per unit of wind strength, windy: 0 = ignores the wind (water current), 1 = follows it.
        feature("waving_leaves", GaleConfig.leaves, WavingTable.LEAVES, CLASS_LEAVES, false, """
                    if (%s(atmos_MaterialBits) == 1u) {
                        vec3 galeLeaf = gale_offsetS(atmos_worldPos(position), 110.0, 250.0, 0.08, 0.0, 1.0, gale_shelter(position));
                        if (gale_Field.w > 0.5) {
                            // 0.6.0: explosion shockwaves shake leaves too.
                            galeLeaf = gale_cap(galeLeaf + gale_blast(position) * gale_Field.z);
                        }
                        position += galeLeaf;
                    }
                """.formatted(cls));
        // Plants and hanging blocks share wave_class 2 (the anchor bit says which edge is pinned). Whichever of the
        // two features is active emits the code once.
        // 0.2.0: hanging blocks (light bit "hanging") swing as driven damped oscillators: per-term gain and lag
        // (gale_Hang0, gale_Hang1.xy) and a lean and gust that lag and overshoot (gale_Hang1.zw, integrated on the CPU).
        String flex = """
                #ifndef GALE_FLEX
                #define GALE_FLEX
                    if (%1$s(atmos_MaterialBits) == 2u && %2$s(atmos_LightBits) == 0u) {
                        float galeShelter = gale_shelter(position);
                        if (%3$s(atmos_LightBits) != 0u) {
                            // 0.3.0: the lever arm below the chain's anchor (thirds of a block; 0 = unknown, 1 block).
                            float galeQ = float(%4$s(atmos_LightBits));
                            float galeLever = gale_Contact.z > 0.5 && galeQ > 0.5 ? galeQ / 3.0 : 1.0;
                            // 0.10.1: a longer arm swings a little more than in proportion (x1.15 at 2 blocks), and
                            // hanging blocks follow the wind more (amplitude 0.15, lean 0.45; were 0.13, 0.35).
                            float galeArm = galeLever * (1.0 + 0.15 * max(galeLever - 1.0, 0.0));
                            // 0.6.3: chains sway by the chain sway amount (0 = they hold still); 0.10.1: applied before
                            // the 0.2-block cap, so a chain sway above 100%% never exceeds it.
                            float galeChainScale = %5$s(atmos_LightBits) != 0u ? gale_Chain.x : 1.0;
                            vec3 galeHangOff = gale_offsetRS(atmos_worldPos(position), 90.0, 210.0, 0.15, 0.45, 1.0, gale_Hang0, gale_Hang1,
                                    galeShelter * galeArm * galeChainScale);
                            if (galeQ > 0.5) {
                                // 0.6.0 arc: a swinging pendulum rises at the ends of its swing.
                                galeHangOff = gale_arc(galeHangOff, 1.0 / max(galeLever, 0.3333));
                            }
                            position += galeHangOff;
                        } else {
                            vec3 galeOff = gale_offsetS(atmos_worldPos(position), 90.0, 210.0, 0.13, 0.45, 1.0, galeShelter);
                            vec3 galeExtra = vec3(0.0);
                            if (gale_Contact.x > 0.0 && gale_Contact.w > 0.5) {
                                galeExtra += gale_push(position) * gale_Contact.x;
                            }
                            if (gale_Field.w > 0.5) {
                                galeExtra += gale_blast(position) * gale_Field.z;
                            }
                            galeOff = gale_cap(galeOff + galeExtra);
                            // 0.6.0 bend profile: tall plants curve from the root (lever = height up the column).
                            uint galeBendQ = %4$s(atmos_LightBits);
                            if (galeBendQ != 0u) {
                                galeOff = gale_arc(galeOff * gale_bend(galeBendQ), -1.0);
                            }
                            position += galeOff;
                        }
                    }
                #endif
                """.formatted(cls, anc, hangingBit.glslMacro(), lever.glslMacro(), chainMark.glslMacro());
        feature("waving_plants", GaleConfig.plants, WavingTable.PLANTS, CLASS_FLEX, true, flex);
        feature("waving_hanging", GaleConfig.hanging, WavingTable.HANGING, CLASS_FLEX, true, flex);
        feature("waving_aquatic", GaleConfig.aquatic, WavingTable.AQUATIC, CLASS_AQUATIC, true, """
                    if (%s(atmos_MaterialBits) == 3u && %s(atmos_LightBits) == 0u) {
                        vec3 galeWater = gale_offset(atmos_worldPos(position), 40.0, 100.0, 0.11, 0.0, 0.15);
                        uint galeWaterQ = %s(atmos_LightBits);
                        if (galeWaterQ != 0u) {
                            galeWater = gale_arc(galeWater * gale_bend(galeWaterQ), -1.0);
                        }
                        position += galeWater;
                    }
                """.formatted(cls, anc, lever.glslMacro()));
        FrameUniforms.vec4(features[2], "gale_Hang0", "hanging response: x, y: gain and lag (rad) of the n1 term, z, w: of the n2 term",
                WavingFeatures::writeHang0);
        FrameUniforms.vec4(features[2], "gale_Hang1", "hanging response: x, y: gain and lag of the n1+n2 term, z: lean drive, w: gust drive (oscillator outputs)",
                WavingFeatures::writeHang1);
        for (int i = 0; i < EntityPush.MAX; i++) {
            int n = i;
            FrameUniforms.vec4(features[1], "gale_Push" + i, "entity " + i + " pushing plants aside: xyz feet relative to the camera, w radius (0 = none)",
                    out -> EntityPush.write(n, out));
        }
        com.solarbiscuit.atmosphera.lib.world.ColumnFieldShader.requireInTerrain(features[0]);
        com.solarbiscuit.atmosphera.lib.world.ColumnFieldShader.requireInTerrain(features[1]);
        com.solarbiscuit.atmosphera.lib.world.ColumnFieldShader.requireInTerrain(features[2]);
        FrameUniforms.vec4(features[0], "gale_Flutter", "x: leaf flutter strength (brightness, 0 = off), y: fade start, z: fade end (blocks)",
                WavingFeatures::writeFlutter);
        // Leaf flutter (0.2.0): leaves near the camera brighten and darken a little per half-block cell, as leaves
        // turning in the light. 4.5 and 6.55 cycles per sway second (whole cycles per 200 s), under 10 Hz at any speed.
        ShaderSnippets.register(features[0], ShaderHook.FRAG_PRE_FOG, ShaderSnippets.PASS_ALL, 0, """
                    if (%s(v_AtmosMaterialBits) == 1u && gale_Flutter.x > 0.0) {
                        float galeFd = length(v_AtmosRelPos);
                        float galeFade = (1.0 - smoothstep(gale_Flutter.y, gale_Flutter.z, galeFd))
                                * smoothstep(2.0, 4.0, 0.5 / max(galeFd * 1.4 * atmos_Screen.y, 1e-5));
                        if (galeFade > 0.0) {
                            uvec3 galeQ = uvec3(ivec3(floor((v_AtmosRelPos + atmos_CameraPos.xyz) * 2.0)));
                            uint galeHash = (galeQ.x * 73856093u) ^ (galeQ.y * 19349663u) ^ (galeQ.z * 83492791u);
                            galeHash = (galeHash ^ (galeHash >> 13u)) * 1274126177u;
                            float galeH = float(galeHash & 65535u) * (1.0 / 65535.0);
                            float galeC = gale_Wave.x * 0.0314159265;
                            float galeF = 0.6 * sin(galeC * 900.0 + galeH * 6.2831853) + 0.4 * sin(galeC * 1310.0 + galeH * 17.0);
                            diffuseColor.rgb *= 1.0 + gale_Flutter.x * galeFade * galeF;
                        }
                    }
                """.formatted(cls));

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
     *       dithered transition shows no seam; 0.6.2: this full weight eases out over the next half transition width,
     *       since vanilla chunks (loaded by horizontal chunk distance) can reach past the transition's 3D end;</li>
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
        ShaderSnippets.register(feature, ShaderHook.LOD_DECL, ShaderSnippets.PASS_ALL, 0, WAVE_DECL + DECL + LOD_DECL);
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
                            // 0.5.0: with the sheen up close, LODs carry it at full weight too (no seam at the
                            // transition); without it, it ramps in where the motion fades (0.1.5 rule).
                            float galeRamp = gale_Field.y > 0.5 ? 1.0
                                    : (1.0 - galeGeo) * smoothstep(atmos_Lod.y, 1.5 * atmos_Lod.y, galeD);
                            v.vary.x = max(v.vary.x, galeZone * galeRamp);
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
        ShaderSnippets.register(feature, ShaderHook.VERT_DECL, ShaderSnippets.PASS_ALL, 0, WAVE_DECL + DECL);
        ShaderSnippets.register(feature, ShaderHook.VERT_POSITION, ShaderSnippets.PASS_ALL, 0, code);
        // 0.5.0 wind sheen up close: foliage brightens and darkens with the passing gust wave, the same function and
        // strength as the LOD canopy shimmer, so near and far meet without a seam. Hanging blocks (lanterns) are left
        // out. Guarded: emitted once whichever features are active.
        // 0.6.1: per vertex, scaling v_Color (the fragment multiplies the texture by it, so the result is the same
        // product), instead of per foliage fragment: measured +0.075 ms on Xenon's terrain pass at 1440p with the
        // 0.5.0-0.6.0 options on, most of it the per-fragment gust noise. The wave's shortest wavelength is about 14
        // blocks, so interpolating it across 1-block quads changes the brightness by under 0.1%. LODs keep it per
        // fragment (merged quads are coarse); same function, so the transition stays seamless.
        ShaderSnippets.register(feature, ShaderHook.VERT_POST_LIGHT, ShaderSnippets.PASS_ALL, 0, """
                #ifndef GALE_SHEEN
                #define GALE_SHEEN
                    if (gale_Field.y > 0.5 && gale_Lod.z > 0.0 && %1$s(atmos_MaterialBits) != 0u
                            && %2$s(atmos_LightBits) == 0u) {
                        v_Color.rgb *= 1.0 + gale_Lod.z * gale_canopyWave(atmos_worldPos(position));
                    }
                #endif
                """.formatted(waveClass.glslMacro(), hangingBit.glslMacro()));
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

    /**
     * 0.6.3: a hanging chain block. 0.7.3: asked of the table, which flags vertical ChainBlocks and the blocks of the
     * {@code chains} JSON key (Supplementaries rope), and clears the flag for anything a JSON or the blacklist stops.
     */
    private static boolean isChain(BlockState state) {
        return WavingTable.isChain(state);
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
        /** Hanging kind: the block-wide "hanging" light bit (0.2.0). */
        private final int hang;

        WaveHook(byte kind, int material, boolean anchored) {
            this.kind = kind;
            this.material = material;
            this.anchored = anchored;
            this.pinned = anchored ? anchor.set(0, 1) : 0;
            this.top = kind == WavingTable.HANGING;
            this.towardsRoot = top ? 1 : -1;
            this.hang = top ? hangingBit.set(0, 1) : 0;
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
            boolean continues = WavingTable.kindAt(level, pos, towardsRoot) == kind;
            int bits = (continues ? 0 : pinned) | hang;
            if (top) {
                // 0.6.3: chain neighbours (one block up and down, inside the slice margin), for the chain mark.
                int near = 0;
                if (isChain(MeshHooks.blockAt(level, pos.getX(), pos.getY() + 1, pos.getZ()))) {
                    near |= 1;
                }
                if (isChain(MeshHooks.blockAt(level, pos.getX(), pos.getY() - 1, pos.getZ()))) {
                    near |= 2;
                }
                bits = chainMark.set(bits, near);
            }
            if (top && GaleConfig.chainSwing.get()) {
                // 0.3.0: blocks of the chain above this one, at most LEVER_MAX (block-level here; per vertex below).
                int depth = !continues ? 0 : WavingTable.kindAt(level, pos, 2) == kind ? 2 : 1;
                bits = lever.set(bits, depth + 1);
            } else if (!top && GaleConfig.plantBend.get()) {
                // 0.6.0 bend profile, block code (per vertex below): 1 single block (height 1), 2 root of a taller
                // column, 3 and 4 one and two (or more) blocks up. Counted at most two blocks down, inside the slice's
                // margin; a block with plants below is always in a column of height >= 2 (capped at 2), so only the
                // root needs to look up.
                int code;
                if (continues) {
                    code = WavingTable.kindAt(level, pos, -2) == kind ? 4 : 3;
                } else {
                    code = WavingTable.kindAt(level, pos, 1) == kind ? 2 : 1;
                }
                bits = lever.set(bits, code);
            }
            return bits;
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
            int stored = lever.get(bits);
            if (stored > 0 && !top) {
                // 0.6.0: u = height above the column's root / the column height (both capped at 2 blocks), stored as
                // 1 + round(6u). The top edge of one block and the bottom edge of the block above give the same u.
                float h = (stored >= 3 ? stored - 2 : 0) + Math.max(0f, Math.min(1f, y));
                float height = stored == 1 ? 1f : 2f;
                float u = Math.min(h, height) / height;
                bits = lever.set(bits, 1 + Math.round(6f * u));
            } else if (stored > 0) {
                // 0.3.0: lever = blocks above (0-2) + this vertex's drop below the block top, capped at 2 blocks, in
                // thirds. The top edge of one block and the bottom edge of the block above give the same value.
                float arm = Math.min(LEVER_MAX, (stored - 1) + (1f - Math.max(0f, Math.min(1f, y))));
                // At least a third: 0 means "unknown" to the shader (lever 1), and only the pinned top edge has arm 0.
                bits = lever.set(bits, Math.max(1, Math.round(arm * 3f)));
            }
            if (top) {
                // 0.6.3: the chain mark per vertex. A vertex on an edge shared with a chain is marked from both
                // blocks, so a chain and the lantern below it scale their shared vertices alike (no crack).
                int near = chainMark.get(bits);
                boolean mark = isChain(state) || (y > 0.9f && (near & 1) != 0) || (y < 0.1f && (near & 2) != 0);
                bits = chainMark.set(bits, mark ? 1 : 0);
            }
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
    /** 0.7.0: Gale's sway time (seconds x speed x storm tempo, wraps at 200), for banners and ropes. Render thread. */
    public static double swayTime() {
        return swayTime;
    }

    public static void stepSwayTime() {
        double dt = AtmosClock.deltaSeconds();
        stepTempo(dt);
        swayTime = (swayTime + dt * GaleConfig.speed.get() * SPEED_SCALE * tempo) % SWAY_WRAP;
        stepGust(dt);
        stepContact();
        double held = AtmosClock.overrideSeconds();
        if (!Double.isNaN(held)) {
            // Lib dev clock override (lib 0.6.1): a held sway time, for frame-exact test shots.
            swayTime = held % SWAY_WRAP;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            stepDrive(level);
            // 0.4.0: rain and snow lean with the same smoothed drive the lanterns follow.
            com.solarbiscuit.atmosphera.gale.weather.RainTilt.step(DRIVE_WIND.dirX, DRIVE_WIND.dirZ,
                    driveStarted ? leanX : DRIVE_WIND.strength, driveStarted ? gustX : DRIVE_WIND.gust, cameraOpenness);
        } else {
            driveStarted = false;
        }
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
        out[2] = ClientOpenness.update(level, eye.x, eye.y, eye.z);
        cameraOpenness = out[2];
        out[3] = level.getSeaLevel();
    }

    /** The eased biome openness at the camera from the last uniform write (rain tilt, 0.4.0). */
    private static float cameraOpenness = 1f;

    // ---- Field (0.5.0): storm tempo and gust patches ----
    /** The sway runs this much faster in strong wind (1 in calm weather). */
    private static double tempo = 1.0;
    private static double appliedTempo = 1.0;
    /** Above it, leaf flutter could pass 10 Hz at the top sway speed (6.55 cycles per sway second x 0.6 x 1.6). */
    private static final double MAX_TEMPO = 1.6;
    /** Calm-weather wind strength: no speed-up below it. */
    private static final double CALM_STRENGTH = 0.22;
    /** Time constant (s) the tempo eases with (0.6.1). */
    private static final double TEMPO_EASE = 0.5;
    /** The gust lattice repeats every 4096 blocks (the atmos_worldPos wrap); the scroll wraps with it. */
    private static final double GUST_WRAP = 4096.0;
    private static double gustX1, gustZ1, gustX2, gustZ2;

    /**
     * Storm tempo: the sway time advances at {@code 1 + 0.6 (lean - calm)}, capped at 1.6, from the lean oscillator of
     * the previous frame (smooth, so a gust never jumps the phase: the time is integrated). The hanging response is
     * recomputed for it, so a lantern's own swing frequency stays fixed in real time.
     */
    private static void stepTempo(double dt) {
        double target = 1.0;
        if (GaleConfig.stormTempo.get() && driveStarted) {
            target = Math.min(MAX_TEMPO, 1.0 + 0.6 * Math.max(0.0, leanX - CALM_STRENGTH));
        }
        // 0.6.1: eased over TEMPO_EASE seconds. The lean is already smooth, but switching the option (or rejoining)
        // stepped the tempo, and with it the hanging lag (up to about 55 degrees between tempo 1 and 1.6): a lantern
        // jumped. A held clock (dt 0) snaps, so frame-exact shots are unchanged.
        tempo = dt > 0.0 ? tempo + (target - tempo) * (1.0 - Math.exp(-Math.min(dt, 0.25) / TEMPO_EASE)) : target;
        if (Math.abs(tempo - appliedTempo) > 1e-3) {
            appliedTempo = tempo;
            HANG = hangGains(tempo);
        }
    }

    /**
     * Gust patches drift downwind at {@code 3 + 6 lean} blocks per second (times the sway speed slider), the fine
     * octave 1.6 times as fast and a little across the wind. Integrated, so a turning wind bends the path instead of
     * racing the pattern; held by the lib's clock override (zero step), so test shots stay frame-exact.
     */
    private static void stepGust(double dt) {
        if (dt <= 0.0) {
            return;
        }
        double rate = dt * GaleConfig.speed.get() * (3.0 + 6.0 * Math.max(0.0, driveStarted ? leanX : DRIVE_WIND.strength));
        double dx = DRIVE_WIND.dirX;
        double dz = DRIVE_WIND.dirZ;
        gustX1 = wrapGust(gustX1 + dx * rate);
        gustZ1 = wrapGust(gustZ1 + dz * rate);
        gustX2 = wrapGust(gustX2 + (1.6 * dx - 0.35 * dz) * rate);
        gustZ2 = wrapGust(gustZ2 + (1.6 * dz + 0.35 * dx) * rate);
    }

    private static double wrapGust(double v) {
        v %= GUST_WRAP;
        return v < 0.0 ? v + GUST_WRAP : v;
    }

    private static void writeGust(float[] out) {
        out[0] = (float) gustX1;
        out[1] = (float) gustZ1;
        out[2] = (float) gustX2;
        out[3] = (float) gustZ2;
    }

    private static void writeField(float[] out) {
        out[0] = GaleConfig.gustPatches.get() ? 1f : 0f;
        out[1] = GaleConfig.windSheen.get() ? 1f : 0f;
        out[2] = GaleConfig.entityBending.get().floatValue();
        out[3] = Shockwaves.count;
    }

    // ---- Hanging response (0.2.0) ----
    /** Natural frequency of the hanging swing in cycles per 200 s of sway time (between n1 = 90 and n2 = 210). */
    private static final double HANG_N0 = 150.0;
    private static final double HANG_ZETA = 0.25;
    /** Lean and gust oscillator, real time: 0.35 Hz, lightly damped, so a gust makes lanterns overshoot and settle. */
    private static final double DRIVE_OMEGA = 2.2;
    private static final double DRIVE_ZETA = 0.18;
    /** Render thread: replaced (not mutated) when the storm tempo changes, so a writer never sees half an update. */
    private static float[] HANG = hangGains(1.0);
    private static final com.solarbiscuit.atmosphera.lib.wind.WindVector DRIVE_WIND = new com.solarbiscuit.atmosphera.lib.wind.WindVector();
    private static double leanX, leanV, gustX, gustV;
    private static boolean driveStarted;

    /**
     * Steady-state response of a damped oscillator x'' + 2 zeta w0 x' + w0^2 x = w0^2 u to each sine of the sway:
     * gain 1 / sqrt((1 - r^2)^2 + (2 zeta r)^2) and lag atan2(2 zeta r, 1 - r^2), r = n / n0. Gains are scaled so the
     * main pair (weights 0.65, 0.35) keeps its total, so the response reshapes the motion instead of growing it.
     * 0.5.0: {@code tempo} is the storm tempo; the drive frequencies speed up with it in real time while the natural
     * frequency does not, so r = n * tempo / n0.
     */
    static float[] hangGains(double tempo) {
        double[] n = {90.0, 210.0, 300.0};
        double[] g = new double[3];
        double[] d = new double[3];
        for (int i = 0; i < 3; i++) {
            double r = n[i] * tempo / HANG_N0;
            double a = 1.0 - r * r;
            double b = 2.0 * HANG_ZETA * r;
            g[i] = 1.0 / Math.sqrt(a * a + b * b);
            d[i] = Math.atan2(b, a);
        }
        double k = 1.0 / (0.65 * g[0] + 0.35 * g[1]);
        return new float[] {(float) (g[0] * k), (float) d[0], (float) (g[1] * k), (float) d[1], (float) (g[2] * k), (float) d[2]};
    }

    /** Per frame: integrates the lean (wind strength) and gust oscillators over this frame's world-clock step. */
    private static void stepDrive(ClientLevel level) {
        com.solarbiscuit.atmosphera.lib.wind.Wind.global(level, Minecraft.getInstance().getFrameTime(), DRIVE_WIND);
        double u = DRIVE_WIND.strength;
        double ug = DRIVE_WIND.gust;
        if (!driveStarted) {
            driveStarted = true;
            leanX = u;
            gustX = ug;
            leanV = gustV = 0.0;
            return;
        }
        double dt = Math.min(AtmosClock.deltaSeconds(), 0.25);
        int steps = Math.max(1, (int) Math.ceil(dt / (1.0 / 120.0)));
        double h = dt / steps;
        double w2 = DRIVE_OMEGA * DRIVE_OMEGA;
        double c = 2.0 * DRIVE_ZETA * DRIVE_OMEGA;
        for (int i = 0; i < steps; i++) {
            leanV += (w2 * (u - leanX) - c * leanV) * h;
            leanX += leanV * h;
            gustV += (w2 * (ug - gustX) - c * gustV) * h;
            gustX += gustV * h;
        }
    }

    private static void writeHang0(float[] out) {
        if (!GaleConfig.hangingSwing.get()) {
            out[0] = 1f;
            out[1] = 0f;
            out[2] = 1f;
            out[3] = 0f;
            return;
        }
        out[0] = HANG[0];
        out[1] = HANG[1];
        out[2] = HANG[2];
        out[3] = HANG[3];
    }

    private static void writeHang1(float[] out) {
        if (!GaleConfig.hangingSwing.get() || !driveStarted) {
            out[0] = 1f;
            out[1] = 0f;
            out[2] = DRIVE_WIND.strength;
            out[3] = DRIVE_WIND.gust;
            return;
        }
        out[0] = HANG[4];
        out[1] = HANG[5];
        // Overshoot may exceed 1 briefly; gale_offsetR still caps the offset at 0.2 blocks.
        out[2] = (float) Math.max(0.0, leanX);
        out[3] = (float) Math.max(0.0, gustX);
    }

    // ---- Contact (0.3.0) ----
    private static final Object COLUMN_OWNER = new Object();

    /** Per frame: the entities near the camera (bending), and the column field kept running for shelter. */
    private static void stepContact() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            EntityPush.count = 0;
            return;
        }
        boolean plants = features[1] != null && features[1].isActive();
        if (plants && GaleConfig.entityBendingOn.get() && GaleConfig.entityBending.get() > 0.0) {
            EntityPush.update(level, mc.gameRenderer.getMainCamera().getPosition(), mc.getFrameTime());
        } else {
            EntityPush.count = 0;
        }
        boolean leavesOn = features[0] != null && features[0].isActive();
        Shockwaves.step((plants || leavesOn) && GaleConfig.explosionWave.get() && GaleConfig.entityBending.get() > 0.0,
                mc.gameRenderer.getMainCamera().getPosition());
        boolean anyActive = plants || features[0] != null && features[0].isActive()
                || features[2] != null && features[2].isActive();
        if (anyActive && GaleConfig.shelterOn.get() && GaleConfig.shelter.get() > 0.0) {
            com.solarbiscuit.atmosphera.lib.world.ColumnField.use(COLUMN_OWNER, level, 256);
        }
    }

    private static void writeContact(float[] out) {
        out[0] = GaleConfig.entityBendingOn.get() ? GaleConfig.entityBending.get().floatValue() : 0f;
        out[1] = GaleConfig.shelterOn.get() && com.solarbiscuit.atmosphera.lib.world.ColumnField.ready()
                ? GaleConfig.shelter.get().floatValue() : 0f;
        out[2] = GaleConfig.chainSwing.get() ? 1f : 0f;
        out[3] = EntityPush.count;
    }

    private static void writeFlutter(float[] out) {
        double strength = GaleConfig.leafFlutter.get();
        if (strength <= 0.0) {
            return;
        }
        // 8% brightness swing at 100%, more in wind, scaled by the sway amount like the motion.
        out[0] = (float) (strength * 0.08 * (0.4 + 0.6 * DRIVE_WIND.strength) * Math.min(1.0, GaleConfig.amplitude.get()));
        out[1] = 16f;
        out[2] = 24f;
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
            // faded with the motion's size on screen so sub-pixel offsets never shimmer (from 0.6.2 only once no vanilla
            // foliage can be left beside it, see below).
            float gale_lodPixelWeight(float galeD, float galeBlocksPerPixel, float galeAmplitude) {
                if (galeD <= atmos_Lod.y || galeBlocksPerPixel <= 0.0) return 1.0;
                // 0.6.2: vanilla chunks load by horizontal chunk distance, so seen from above or at chunk corners
                // vanilla foliage reaches past the transition end (a 3D distance) and keeps its sub-pixel sway. Measured:
                // it changed 2.5x as much between two moments as the still LOD foliage next to it. The motion now stays
                // at full weight past the end and eases out over half a transition width (the 0.1.5 sheen ramp).
                float galeNear = 1.0 - smoothstep(atmos_Lod.y, 1.5 * atmos_Lod.y, galeD);
                return max(galeNear, smoothstep(0.25, 0.5, galeAmplitude / (galeD * galeBlocksPerPixel)));
            }
            #endif
            """;

    /**
     * Shared by the vertex, fragment and LOD stages (guarded): the travelling waves, the gust field and the canopy
     * light wave. Reads only frame slots, so it compiles wherever the AtmosFrame block does.
     */
    private static final String WAVE_DECL = """
            #ifndef GALE_WAVE_DECL
            #define GALE_WAVE_DECL
            // Travelling waves run along four fixed axes (+x, -x, +z, -z), weighted by how much the wind points along
            // each (the weights sum to 1). A wave along the wind vector itself shifts its phase by 0.45 |p| dTheta when
            // the wind turns; world positions are thousands of blocks, so every gust veer made foliage race at 20-250x
            // speed (Gale 0.1.0-0.1.7). Turning the wind now only moves the weights, slowly.
            // The weights are computed once per frame on the CPU (lib 0.4.0 atmos_WindAxes, audit R3); same formula.
            vec4 gale_axisWeights() {
                return atmos_WindAxes;
            }
            // sum over the axes of w_k * sin(base - along_k * k), with along_k = (x, -x, z, -z)
            float gale_travel(vec4 galeW, vec2 galeXZ, float galeBase, float galeK) {
                vec4 galeAlong = vec4(galeXZ.x, -galeXZ.x, galeXZ.y, -galeXZ.y) * galeK;
                return dot(galeW, sin(vec4(galeBase) - galeAlong));
            }
            // 0.5.0 gust patches: hashed value noise on a lattice that repeats every 4096 blocks (the atmos_worldPos
            // wrap), so the camera crossing the wrap shows no seam. galeMask = 4096 / cell - 1.
            float gale_hash(ivec2 galeC) {
                uvec2 galeU = uvec2(galeC);
                uint galeH = (galeU.x * 73856093u) ^ (galeU.y * 19349663u);
                galeH = (galeH ^ (galeH >> 13u)) * 1274126177u;
                galeH ^= galeH >> 16u;
                return float(galeH & 65535u) * (1.0 / 65535.0);
            }
            float gale_value(vec2 galeQ, float galeCell, int galeMask) {
                vec2 galeG = galeQ / galeCell;
                vec2 galeF = floor(galeG);
                vec2 galeT = galeG - galeF;
                galeT = galeT * galeT * (3.0 - 2.0 * galeT);
                ivec2 galeI = ivec2(galeF);
                float galeA = gale_hash(galeI & galeMask);
                float galeB = gale_hash((galeI + ivec2(1, 0)) & galeMask);
                float galeC = gale_hash((galeI + ivec2(0, 1)) & galeMask);
                float galeD = gale_hash((galeI + ivec2(1, 1)) & galeMask);
                return mix(mix(galeA, galeB, galeT.x), mix(galeC, galeD, galeT.x), galeT.y);
            }
            // Irregular gust patches drifting downwind, about -1 (lull) to 1 (gust). Two octaves (32 and 16 blocks)
            // scrolled separately by the CPU (gale_Gust), the fine one faster and a little across the wind, so patches
            // change shape as they travel instead of sliding like a stencil.
            float gale_gustField(vec2 galeXZ) {
                float galeV = 0.65 * gale_value(galeXZ - gale_Gust.xy, 32.0, 127)
                        + 0.35 * gale_value(galeXZ - gale_Gust.zw, 16.0, 255);
                return smoothstep(0.3, 0.7, galeV) * 2.0 - 1.0;
            }
            // The gust envelope: the patches, or the 0.1.x travelling sine (about 78 blocks) when they are off.
            float gale_gust(vec4 galeAxes, vec2 galeXZ, float galeCycle) {
                return gale_Field.x > 0.5 ? gale_gustField(galeXZ) : gale_travel(galeAxes, galeXZ, galeCycle * 7.0, 0.08);
            }
            // How strongly the gust envelope shows, from the gust drive (0..1). Patches show in any breeze (30%) and
            // grow with gusts; the 0.1.x bands only show during gusts, as before.
            float gale_gustWeight(float galeDrive) {
                return gale_Field.x > 0.5 ? 0.3 + 0.7 * galeDrive : galeDrive;
            }
            // Wind waves of light over canopies and fields: gale_offset's along-wind term (about 14 blocks) and its
            // gust envelope, the parts of the motion that read as light rolling over foliage. About -1..1.
            float gale_canopyWave(vec3 galeP) {
                vec4 galeAxes = gale_axisWeights();
                float galeCycle = gale_Wave.x * 0.0314159265;
                float galeGust = gale_gust(galeAxes, galeP.xz, galeCycle);
                float galeMain = gale_travel(galeAxes, galeP.xz, galeCycle * 110.0, 0.45);
                float galeEnergy = min((0.3 + 1.1 * atmos_Wind.z) * gale_Wave.z, 1.5);
                float galeWeight = gale_gustWeight(atmos_Wind.w);
                if (gale_Field.x > 0.5) {
                    // 0.5.2: like the sway, the along-wind ripples are strongest inside a gust patch and fade
                    // between them (cat's paws), on top of the patch's own light.
                    galeMain *= 0.5 + galeWeight * (0.5 + 0.5 * galeGust);
                }
                return (0.6 * galeMain + 0.4 * galeGust * galeWeight) * galeEnergy * gale_Wave.y;
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
            // (gale_axisWeights, gale_travel and the gust field are in WAVE_DECL, declared before this.)
            // Offsets stay within 0.2 blocks (Xenon's section culling margin).
            vec3 gale_cap(vec3 galeOff) {
                float galeLen = length(galeOff);
                return galeLen > 0.2 ? galeOff * (0.2 / galeLen) : galeOff;
            }
            // galeR0 = (gain, lag) of the n1 and n2 terms, galeR1 = (gain, lag) of the n1+n2 term, strength, gust.
            // gale_offset passes the identity (1, 0, 1, 0), (1, 0, wind strength, gust): the 0.1.x motion exactly.
            // galeScale (0.3.0): shelter and chain lever, applied before the 0.2-block cap.
            vec3 gale_offsetRS(vec3 galeP, float galeN1, float galeN2, float galeAmp, float galeLean, float galeWindy,
                               vec4 galeR0, vec4 galeR1, float galeScale) {
                vec2 galeDir = atmos_Wind.xy;
                vec4 galeAxes = gale_axisWeights();
                float galeCycle = gale_Wave.x * 0.0314159265;
                float galeAlt = clamp((galeP.y - gale_Wave.w) * 0.0104, 0.0, 0.6);
                float galeEnergy = mix(0.6, (0.3 + 1.1 * galeR1.z) * gale_Wave.z * (1.0 + galeAlt), galeWindy);
                float galeP1 = galeCycle * galeN1;
                float galeP2 = galeCycle * galeN2 + galeP.x * 0.83 - galeP.z * 0.61 + galeP.y * 0.5;
                float galeP3 = galeCycle * (galeN1 + galeN2) + galeP.z * 0.7 - galeP.x * 0.4 + 1.7;
                float galeGust = 0.75 + 0.5 * gale_gustWeight(galeR1.w) * (0.5 + 0.5 * gale_gust(galeAxes, galeP.xz, galeCycle));
                float galeMain = (0.65 * galeR0.x * gale_travel(galeAxes, galeP.xz, galeP1 - galeR0.y, 0.45)
                        + 0.35 * galeR0.z * sin(galeP2 - galeR0.w)) * galeGust + galeLean * galeR1.z;
                vec2 galeXZ = galeDir * galeMain + vec2(-galeDir.y, galeDir.x) * (0.3 * galeR1.x * sin(galeP3 - galeR1.y));
                vec3 galeOff = vec3(galeXZ.x, 0.12 * galeR1.x * sin(galeP3 - galeR1.y + galeP1 - 0.45 * (galeP.x + galeP.z)), galeXZ.y) * (galeAmp * galeEnergy * gale_Wave.y * galeScale);
                return gale_cap(galeOff);
            }
            vec3 gale_offsetR(vec3 galeP, float galeN1, float galeN2, float galeAmp, float galeLean, float galeWindy,
                              vec4 galeR0, vec4 galeR1) {
                return gale_offsetRS(galeP, galeN1, galeN2, galeAmp, galeLean, galeWindy, galeR0, galeR1, 1.0);
            }
            vec3 gale_offsetS(vec3 galeP, float galeN1, float galeN2, float galeAmp, float galeLean, float galeWindy,
                              float galeScale) {
                return gale_offsetRS(galeP, galeN1, galeN2, galeAmp, galeLean, galeWindy, vec4(1.0, 0.0, 1.0, 0.0),
                        vec4(1.0, 0.0, atmos_Wind.z, atmos_Wind.w), galeScale);
            }
            vec3 gale_offset(vec3 galeP, float galeN1, float galeN2, float galeAmp, float galeLean, float galeWindy) {
                return gale_offsetS(galeP, galeN1, galeN2, galeAmp, galeLean, galeWindy, 1.0);
            }
            // Shelter (0.3.0): sway is reduced (to 35% at full strength) under a sun-casting block more than half a
            // block above the vertex, from the column field's caster top; 1 without field data or on LODs.
            float gale_shelter(vec3 galeRel) {
            #ifdef ATMOS_COLUMN_FIELD
                if (gale_Contact.y > 0.0) {
                    float galeTop = atmos_columnTop(galeRel);
                    if (galeTop > -1e8) {
                        float galeY = galeRel.y + atmos_CameraPos.y;
                        return 1.0 - 0.65 * gale_Contact.y * smoothstep(galeY + 0.5, galeY + 2.5, galeTop);
                    }
                }
            #endif
                return 1.0;
            }
            // Entity bending (0.3.0): pushed away from the entity's axis and a little down, with a smooth radial
            // falloff from 40% of its radius to radius + 0.5 blocks. Safe at zero distance (no direction there).
            // 0.6.0: w packs radius + 2 * round(7 * intensity) (radius < 2, intensity 0.6..1 from the entity's speed).
            vec3 gale_pushOne(vec4 galeE, vec3 galeRel) {
                if (galeE.w <= 0.0) return vec3(0.0);
                float galeK = floor(galeE.w * 0.5);
                float galeRad = galeE.w - 2.0 * galeK;
                vec3 galeD = galeRel - galeE.xyz;
                if (galeD.y < -0.5 || galeD.y > 2.5) return vec3(0.0);
                float galeDist = length(galeD.xz);
                float galeF = 1.0 - smoothstep(galeRad * 0.4, galeRad + 0.5, galeDist);
                if (galeF <= 0.0) return vec3(0.0);
                vec2 galeDir = galeD.xz / max(galeDist, 1e-3) * min(galeDist * 1000.0, 1.0);
                return vec3(galeDir.x * 0.25, -0.12, galeDir.y * 0.25) * (galeF * galeK * (1.0 / 7.0));
            }
            // 0.6.0 explosion shockwave: w packs floor(R) * 32 + front (R: final radius, front < 32). A pulse a few
            // blocks wide rides just behind the front, pushing plants outward and a little down; strength fades as
            // the front grows. Plants spring back once it has passed.
            vec3 gale_blastOne(vec4 galeB, vec3 galeRel) {
                if (galeB.w <= 0.0) return vec3(0.0);
                float galeR = floor(galeB.w * (1.0 / 32.0));
                if (galeR < 1.0) return vec3(0.0);
                float galeFront = galeB.w - 32.0 * galeR;
                vec3 galeD = galeRel - galeB.xyz;
                if (abs(galeD.y) > galeR) return vec3(0.0);
                float galeDist = length(galeD.xz);
                float galePulse = smoothstep(galeFront - 3.0, galeFront - 0.5, galeDist)
                        * (1.0 - smoothstep(galeFront - 0.5, galeFront + 0.5, galeDist));
                if (galePulse <= 0.0) return vec3(0.0);
                float galeT = galeFront / galeR;
                float galeS = max(1.0 - galeT * galeT, 0.0);
                vec2 galeDir = galeD.xz / max(galeDist, 1e-3) * min(galeDist * 1000.0, 1.0);
                return vec3(galeDir.x * 0.3, -0.1, galeDir.y * 0.3) * (galeS * galeS * galePulse);
            }
            vec3 gale_blast(vec3 galeRel) {
                vec3 galeSum = gale_blastOne(gale_Blast0, galeRel);
                if (gale_Field.w > 1.5) {
                    galeSum += gale_blastOne(gale_Blast1, galeRel) + gale_blastOne(gale_Blast2, galeRel)
                            + gale_blastOne(gale_Blast3, galeRel);
                }
                return galeSum;
            }
            // 0.6.0 bend profile: lever code 1..7 = 1 + 6u, u = height up the column / its height (capped at 2
            // blocks); the clamped-cantilever shape (u^4 - 4u^3 + 6u^2) / 3, 0 at the root and 1 at the top.
            float gale_bend(uint galeQ) {
                float galeU = float(galeQ - 1u) * (1.0 / 6.0);
                return galeU * galeU * (galeU * galeU - 4.0 * galeU + 6.0) * (1.0 / 3.0);
            }
            // 0.6.0 arc: a stem bent sideways by d gets shorter by about |d|^2 / 2L (L = 1 block for plants, so it
            // stays a pure function of the offset and cannot crack); galeSign -1 drops a plant's tip, a positive
            // 1/L raises a pendulum. Re-capped at 0.2 blocks.
            vec3 gale_arc(vec3 galeOff, float galeSign) {
                galeOff.y += 0.5 * galeSign * dot(galeOff.xz, galeOff.xz);
                return gale_cap(galeOff);
            }
            vec3 gale_push(vec3 galeRel) {
                vec3 galeSum = gale_pushOne(gale_Push0, galeRel) + gale_pushOne(gale_Push1, galeRel)
                        + gale_pushOne(gale_Push2, galeRel) + gale_pushOne(gale_Push3, galeRel);
                if (gale_Contact.w > 4.5) {
                    galeSum += gale_pushOne(gale_Push4, galeRel) + gale_pushOne(gale_Push5, galeRel)
                            + gale_pushOne(gale_Push6, galeRel) + gale_pushOne(gale_Push7, galeRel);
                }
                return galeSum;
            }
            #endif
            """;
}
