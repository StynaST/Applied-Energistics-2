/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2013 - 2014, AlgorithmX2, All rights reserved.
 *
 * Applied Energistics 2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Applied Energistics 2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Applied Energistics 2.  If not, see <http://www.gnu.org/licenses/lgpl>.
 */

package appeng.client.renderer.spatialstorage;

import java.util.Optional;
import java.util.OptionalDouble;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.client.CustomSkyboxRenderer;

import appeng.client.render.AERenderPipelines;

public class SpatialStorageSkyRenderer implements CustomSkyboxRenderer, AutoCloseable {

    private static final int MAX_SPARKLE_QUADS = 50;

    private final RandomSource random = RandomSource.create();
    private long cycle = 0;
    private GpuBuffer sparklesVertices;
    private int sparklesQuads;
    private GpuBuffer skyboxVertices;

    private static final Quaternionf[] SKYBOX_SIDE_ROTATIONS = { new Quaternionf(),
            new Quaternionf().rotationX(Mth.DEG_TO_RAD * 90.0F),
            new Quaternionf().rotationX(Mth.DEG_TO_RAD * -90.0F), new Quaternionf().rotationX(Mth.DEG_TO_RAD * 180.0F),
            new Quaternionf().rotationZ(Mth.DEG_TO_RAD * 90.0F),
            new Quaternionf().rotationZ(Mth.DEG_TO_RAD * -90.0F), };

    @Override
    public boolean renderSky(LevelRenderState levelRenderState, SkyRenderState skyRenderState,
            Matrix4fc modelViewMatrix, Runnable setupFog) {
        if (skyboxVertices == null) {
            buildSkybox();
        }
        renderSkybox(modelViewMatrix);

        // Cycle the sparkles between 0 and 0.25 color value over 2 seconds
        final long now = System.currentTimeMillis();
        if (sparklesVertices == null || now - this.cycle > 2000) {
            this.cycle = now;
            this.rebuildSparkles(1);
        }

        float fade = now - this.cycle;
        fade /= 1000;
        fade = 0.25f * (1.0f - Math.abs((fade - 1.0f) * (fade - 1.0f)));

        renderSparkles(sparklesVertices, modelViewMatrix, fade);

        return true;
    }

    private void renderSparkles(GpuBuffer sparklesVertices, Matrix4fc modelViewMatrix, float fade) {
        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                .writeTransform(new Matrix4f(modelViewMatrix), new Vector4f(fade, fade, fade, 1.0F), new Vector3f(),
                        new Matrix4f());

        drawQuads("spatial sky sparkles", AERenderPipelines.SPATIAL_SKYBOX_SPARKLES, dynamicTransforms,
                sparklesVertices, sparklesQuads);
    }

    private void renderSkybox(Matrix4fc modelViewMatrix) {
        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                .writeTransform(new Matrix4f(modelViewMatrix), new Vector4f(1f, 1f, 1f, 1f), new Vector3f(),
                        new Matrix4f());

        drawQuads("spatial skybox", AERenderPipelines.SPATIAL_SKYBOX, dynamicTransforms, skyboxVertices,
                SKYBOX_SIDE_ROTATIONS.length);
    }

    private static void drawQuads(String label, RenderPipeline pipeline, GpuBufferSlice dynamicTransforms,
            GpuBuffer vertices, int quadCount) {
        var renderTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        var colorBuffer = renderTarget.getColorTextureView();
        var depthBuffer = renderTarget.getDepthTextureView();
        var autoIndexBuffer = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        var indexCount = quadCount * 6;
        var indexBuffer = autoIndexBuffer.getBuffer(indexCount);

        try (var pass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(() -> label, colorBuffer, Optional.empty(), depthBuffer,
                        OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", dynamicTransforms);
            pass.setPipeline(pipeline);
            pass.setVertexBuffer(0, vertices.slice());
            pass.setIndexBuffer(indexBuffer, autoIndexBuffer.type());
            pass.drawIndexed(indexCount, 1, 0, 0, 0);
        }
    }

    /**
     * Bakes the six pitch black, untextured skybox faces into a static vertex buffer.
     */
    private void buildSkybox() {
        try (var bytebufferbuilder = new ByteBufferBuilder(
                SKYBOX_SIDE_ROTATIONS.length * 4 * DefaultVertexFormat.POSITION_COLOR.getVertexSize())) {
            var vb = new BufferBuilder(bytebufferbuilder, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_COLOR);

            var poseStack = new PoseStack();
            for (Quaternionf rotation : SKYBOX_SIDE_ROTATIONS) {
                poseStack.pushPose();
                poseStack.mulPose(rotation);

                Matrix4f pose = poseStack.last().pose();
                vb.addVertex(pose, -100.0f, -100.0f, -100.0f).setColor(0f, 0f, 0f, 1f);
                vb.addVertex(pose, -100.0f, -100.0f, 100.0f).setColor(0f, 0f, 0f, 1f);
                vb.addVertex(pose, 100.0f, -100.0f, 100.0f).setColor(0f, 0f, 0f, 1f);
                vb.addVertex(pose, 100.0f, -100.0f, -100.0f).setColor(0f, 0f, 0f, 1f);

                poseStack.popPose();
            }

            try (var meshdata = vb.buildOrThrow()) {
                skyboxVertices = RenderSystem.getDevice()
                        .createBuffer(() -> "Spatial skybox vertex buffer", GpuBuffer.USAGE_VERTEX,
                                meshdata.vertexBuffer());
            }
        }
    }

    private void rebuildSparkles(float fade) {
        if (sparklesVertices != null) {
            sparklesVertices.close();
        }
        sparklesQuads = 0;

        try (var bytebufferbuilder = new ByteBufferBuilder(
                MAX_SPARKLE_QUADS * 4 * DefaultVertexFormat.POSITION_COLOR.getVertexSize())) {
            var vb = new BufferBuilder(bytebufferbuilder, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_COLOR);

            for (int i = 0; i < MAX_SPARKLE_QUADS; ++i) {
                float iX = this.random.nextFloat() * 2.0f - 1.0f;
                float iY = this.random.nextFloat() * 2.0f - 1.0f;
                float iZ = this.random.nextFloat() * 2.0f - 1.0f;
                float d3 = 0.05F + this.random.nextFloat() * 0.1f;
                float dist = iX * iX + iY * iY + iZ * iZ;

                if (dist < 1.0f && dist > 0.01f) {
                    dist = 1.0f / Mth.sqrt(dist);
                    iX *= dist;
                    iY *= dist;
                    iZ *= dist;
                    float x = iX * 100.0f;
                    float y = iY * 100.0f;
                    float z = iZ * 100.0f;
                    float d8 = (float) Mth.atan2(iX, iZ);
                    float d9 = Mth.sin(d8);
                    float d10 = Mth.cos(d8);
                    float d11 = (float) Mth.atan2(Mth.sqrt(iX * iX + iZ * iZ), iY);
                    float d12 = Mth.sin(d11);
                    float d13 = Mth.cos(d11);
                    float d14 = this.random.nextFloat() * Mth.PI * 2.0f;
                    float d15 = Mth.sin(d14);
                    float d16 = Mth.cos(d14);

                    for (int j = 0; j < 4; ++j) {
                        float d17 = 0.0f;
                        float d18 = ((j & 2) - 1) * d3;
                        float d19 = ((j + 1 & 2) - 1) * d3;
                        float d20 = d18 * d16 - d19 * d15;
                        float d21 = d19 * d16 + d18 * d15;
                        float d22 = d20 * d12 + d17 * d13;
                        float d23 = d17 * d12 - d20 * d13;
                        float d24 = d23 * d9 - d21 * d10;
                        float d25 = d21 * d9 + d23 * d10;
                        vb.addVertex(x + d24, y + d22, z + d25).setColor(fade, fade, fade, 1.0f);
                    }
                    sparklesQuads++;
                }
            }

            try (var meshdata = vb.buildOrThrow()) {
                sparklesVertices = RenderSystem.getDevice()
                        .createBuffer(() -> "Spatial sky sparkles vertex buffer", GpuBuffer.USAGE_VERTEX,
                                meshdata.vertexBuffer());
            }
        }
    }

    @Override
    public void close() {
        if (sparklesVertices != null) {
            sparklesVertices.close();
            sparklesVertices = null;
        }
        if (skyboxVertices != null) {
            skyboxVertices.close();
            skyboxVertices = null;
        }
    }
}
