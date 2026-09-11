/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2021, TeamAppliedEnergistics, All rights reserved.
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

package appeng.client.areaoverlay;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import org.joml.Matrix4f;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

import appeng.client.render.AERenderTypes;
import appeng.core.AppEng;
import appeng.core.areaoverlay.AreaOverlayManager;
import appeng.core.areaoverlay.IAreaOverlayDataSource;

/**
 * This is based on the area render of https://github.com/TeamPneumatic/pnc-repressurized/
 */
public class AreaOverlayRenderer {

    private static final ContextKey<List<IAreaOverlayDataSource>> OVERLAY_AREAS = new ContextKey<>(
            AppEng.makeId("overlay_areas"));

    @SubscribeEvent
    public void extractRenderState(ExtractLevelRenderStateEvent event) {
        var visibleAreas = AreaOverlayManager.getInstance().getVisible();

        var areasInThisLevel = new ArrayList<IAreaOverlayDataSource>();
        for (var visibleArea : visibleAreas) {
            if (visibleArea.getOverlaySourceLocation().getLevel() == event.getLevel()) {
                areasInThisLevel.add(visibleArea);
            }
        }
        event.getRenderState().setRenderData(OVERLAY_AREAS, areasInThisLevel);
    }

    @SubscribeEvent
    public void submitGeometry(SubmitCustomGeometryEvent event) {
        var levelRenderState = event.getLevelRenderState();
        var visibleAreas = levelRenderState.getRenderDataOrDefault(OVERLAY_AREAS, List.of());

        if (visibleAreas.isEmpty()) {
            return;
        }

        var nodes = event.getSubmitNodeCollector();
        PoseStack poseStack = event.getPoseStack();

        poseStack.pushPose();

        Vec3 projectedView = levelRenderState.cameraRenderState.pos;
        poseStack.translate(-projectedView.x, -projectedView.y, -projectedView.z);

        for (var visibleArea : visibleAreas) {
            render(visibleArea, poseStack, nodes);
        }

        poseStack.popPose();
    }

    public void render(IAreaOverlayDataSource area, PoseStack poseStack, SubmitNodeCollector nodes) {
        Level level = area.getOverlaySourceLocation().getLevel();
        Collection<ChunkPos> allChunks = area.getOverlayChunks();

        render(level, allChunks, poseStack, nodes, AERenderTypes.AREA_OVERLAY_LINE_OCCLUDED, true, 0x30ffffff);
        render(level, allChunks, poseStack, nodes, AERenderTypes.AREA_OVERLAY_FACE, false, area.getOverlayColor());
        render(level, allChunks, poseStack, nodes, AERenderTypes.AREA_OVERLAY_LINE, true, area.getOverlayColor());
    }

    private void render(Level level, Collection<ChunkPos> allChunks, PoseStack poseStack, SubmitNodeCollector nodes,
            RenderType renderType, boolean renderLines, int color) {
        int[] cols = decomposeColor(color);
        nodes.submitCustomGeometry(poseStack, renderType, (pose, builder) -> {
            for (ChunkPos pos : allChunks) {
                var posMat = new Matrix4f(pose.pose()).translate(pos.getMinBlockX(), 0, pos.getMinBlockZ());
                addVertices(level, allChunks, builder, posMat, pos, cols, renderLines);
            }
        });
    }

    private static int[] decomposeColor(int color) {
        int[] res = new int[4];
        res[0] = color >> 24 & 0xff;
        res[1] = color >> 16 & 0xff;
        res[2] = color >> 8 & 0xff;
        res[3] = color & 0xff;
        return res;
    }

    private void addVertices(Level level, Collection<ChunkPos> allChunks, VertexConsumer wr, Matrix4f posMat,
            ChunkPos pos, int[] cols, boolean renderLines) {
        // Render around a whole chunk
        float x1 = 0f;
        float x2 = 16f;
        float y1 = level.getMinY();
        float y2 = level.getMaxY();
        float z1 = 0f;
        float z2 = 16f;

        final float lineWidth = 3f;

        boolean noNorth = !allChunks.contains(new ChunkPos(pos.x(), pos.z() - 1));
        boolean noSouth = !allChunks.contains(new ChunkPos(pos.x(), pos.z() + 1));
        boolean noWest = !allChunks.contains(new ChunkPos(pos.x() - 1, pos.z()));
        boolean noEast = !allChunks.contains(new ChunkPos(pos.x() + 1, pos.z()));

        if (noNorth) {
            // Face North, Edge Bottom
            wr.addVertex(posMat, x1, y1, z1).setColor(cols[1], cols[2], cols[3], cols[0])
                    .setNormal(1, 0, 0).setLineWidth(lineWidth);
            wr.addVertex(posMat, x2, y1, z1).setColor(cols[1], cols[2], cols[3], cols[0])
                    .setNormal(1, 0, 0).setLineWidth(lineWidth);
            // Face North, Edge Top
            wr.addVertex(posMat, x2, y2, z1).setColor(cols[1], cols[2], cols[3], cols[0])
                    .setNormal(-1, 0, 0).setLineWidth(lineWidth);
            wr.addVertex(posMat, x1, y2, z1).setColor(cols[1], cols[2], cols[3], cols[0])
                    .setNormal(-1, 0, 0).setLineWidth(lineWidth);
        }

        if (noSouth) {
            // Face South, Edge Bottom
            wr.addVertex(posMat, x2, y1, z2).setColor(cols[1], cols[2], cols[3], cols[0])
                    .setNormal(-1, 0, 0).setLineWidth(lineWidth);
            wr.addVertex(posMat, x1, y1, z2).setColor(cols[1], cols[2], cols[3], cols[0])
                    .setNormal(-1, 0, 0).setLineWidth(lineWidth);
            // Face South, Edge Top
            wr.addVertex(posMat, x1, y2, z2).setColor(cols[1], cols[2], cols[3], cols[0])
                    .setNormal(1, 0, 0).setLineWidth(lineWidth);
            wr.addVertex(posMat, x2, y2, z2).setColor(cols[1], cols[2], cols[3], cols[0])
                    .setNormal(1, 0, 0).setLineWidth(lineWidth);
        }

        if (noWest) {
            // Face West, Edge Bottom
            wr.addVertex(posMat, x1, y1, z1).setColor(cols[1], cols[2], cols[3], cols[0])
                    .setNormal(0, 0, 1).setLineWidth(lineWidth);
            wr.addVertex(posMat, x1, y1, z2).setColor(cols[1], cols[2], cols[3], cols[0])
                    .setNormal(0, 0, 1).setLineWidth(lineWidth);
            // Face West, Edge Top
            wr.addVertex(posMat, x1, y2, z2).setColor(cols[1], cols[2], cols[3], cols[0])
                    .setNormal(0, 0, -1).setLineWidth(lineWidth);
            wr.addVertex(posMat, x1, y2, z1).setColor(cols[1], cols[2], cols[3], cols[0])
                    .setNormal(0, 0, -1).setLineWidth(lineWidth);
        }

        if (noEast) {
            // Face East, Edge Bottom
            wr.addVertex(posMat, x2, y1, z2).setColor(cols[1], cols[2], cols[3], cols[0])
                    .setNormal(0, 0, -1).setLineWidth(lineWidth);
            wr.addVertex(posMat, x2, y1, z1).setColor(cols[1], cols[2], cols[3], cols[0])
                    .setNormal(0, 0, -1).setLineWidth(lineWidth);
            // Face East, Edge Top
            wr.addVertex(posMat, x2, y2, z1).setColor(cols[1], cols[2], cols[3], cols[0])
                    .setNormal(0, 0, 1).setLineWidth(lineWidth);
            wr.addVertex(posMat, x2, y2, z2).setColor(cols[1], cols[2], cols[3], cols[0])
                    .setNormal(0, 0, 1).setLineWidth(lineWidth);
        }

        if (renderLines) {
            if (noNorth || noWest) {
                // Face North, Edge West
                wr.addVertex(posMat, x1, y1, z1).setColor(cols[1], cols[2], cols[3], cols[0])
                        .setNormal(0, 1, 0).setLineWidth(lineWidth);
                wr.addVertex(posMat, x1, y2, z1).setColor(cols[1], cols[2], cols[3], cols[0])
                        .setNormal(0, 1, 0).setLineWidth(lineWidth);
            }

            if (noNorth || noEast) {
                // Face North, Edge East
                wr.addVertex(posMat, x2, y2, z1).setColor(cols[1], cols[2], cols[3], cols[0])
                        .setNormal(0, -1, 0).setLineWidth(lineWidth);
                wr.addVertex(posMat, x2, y1, z1).setColor(cols[1], cols[2], cols[3], cols[0])
                        .setNormal(0, -1, 0).setLineWidth(lineWidth);
            }

            if (noSouth || noEast) {
                // Face South, Edge East
                wr.addVertex(posMat, x2, y1, z2).setColor(cols[1], cols[2], cols[3], cols[0])
                        .setNormal(0, 1, 0).setLineWidth(lineWidth);
                wr.addVertex(posMat, x2, y2, z2).setColor(cols[1], cols[2], cols[3], cols[0])
                        .setNormal(0, 1, 0).setLineWidth(lineWidth);
            }
            if (noSouth || noWest) {
                // Face South, Edge West
                wr.addVertex(posMat, x1, y2, z2).setColor(cols[1], cols[2], cols[3], cols[0])
                        .setNormal(0, -1, 0).setLineWidth(lineWidth);
                wr.addVertex(posMat, x1, y1, z2).setColor(cols[1], cols[2], cols[3], cols[0])
                        .setNormal(0, -1, 0).setLineWidth(lineWidth);
            }
        } else {
            // Bottom Face
            wr.addVertex(posMat, x1, y1, z1).setColor(cols[1], cols[2], cols[3], cols[0])
                    .setNormal(1, 0, 0).setLineWidth(lineWidth);
            wr.addVertex(posMat, x2, y1, z1).setColor(cols[1], cols[2], cols[3], cols[0])
                    .setNormal(1, 0, 0).setLineWidth(lineWidth);
            wr.addVertex(posMat, x2, y1, z2).setColor(cols[1], cols[2], cols[3], cols[0])
                    .setNormal(-1, 0, 0).setLineWidth(lineWidth);
            wr.addVertex(posMat, x1, y1, z2).setColor(cols[1], cols[2], cols[3], cols[0])
                    .setNormal(-1, 0, 0).setLineWidth(lineWidth);
        }

    }
}
