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

package appeng.items.tools.powered;

import java.util.List;
import java.util.Objects;

import com.google.common.collect.ImmutableList;

import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ColorCollection;

import appeng.api.util.AEColor;

/**
 * Allows recoloring a variety of vanilla blocks.
 */
public final class BlockRecolorer {

    private BlockRecolorer() {
    }

    private static final List<RecolorableBlockGroup> BLOCK_GROUPS = ImmutableList.of(
            new RecolorableBlockGroup(Blocks.GLASS, Blocks.STAINED_GLASS),
            new RecolorableBlockGroup(Blocks.GLASS_PANE, Blocks.STAINED_GLASS_PANE),
            new RecolorableBlockGroup(Blocks.WOOL.pick(DyeColor.WHITE), Blocks.WOOL),
            new RecolorableBlockGroup(Blocks.BANNER.pick(DyeColor.WHITE), Blocks.BANNER),
            new RecolorableBlockGroup(Blocks.WALL_BANNER.pick(DyeColor.WHITE), Blocks.WALL_BANNER),
            new RecolorableBlockGroup(Blocks.CARPET.pick(DyeColor.WHITE), Blocks.CARPET),
            new RecolorableBlockGroup(Blocks.TERRACOTTA, Blocks.DYED_TERRACOTTA),
            new RecolorableBlockGroup(null, Blocks.GLAZED_TERRACOTTA),
            new RecolorableBlockGroup(null, Blocks.CONCRETE));

    public static Block recolor(Block block, AEColor newColor) {
        Objects.requireNonNull(block);

        for (RecolorableBlockGroup group : BLOCK_GROUPS) {
            if (group.uncoloredVariant == block || group.coloredVariants.asList().contains(block)) {
                return group.coloredVariants.pick(newColor.dye);
            }
        }

        return block;
    }

    private static class RecolorableBlockGroup {

        final Block uncoloredVariant;

        final ColorCollection<Block> coloredVariants;

        public RecolorableBlockGroup(Block uncoloredVariant, ColorCollection<Block> coloredVariants) {
            this.uncoloredVariant = uncoloredVariant;
            this.coloredVariants = coloredVariants;
        }

    }

}
