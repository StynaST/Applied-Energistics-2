package appeng.integration.modules.ntm;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.hbm.inventory.fluid.tank.FluidTankNTM;
import com.hbm.inventory.recipes.AssemblyMachineRecipes;
import com.hbm.inventory.recipes.ChemicalPlantRecipes;
import com.hbm.inventory.recipes.FusionRecipes;
import com.hbm.inventory.recipes.PUREXRecipes;
import com.hbm.inventory.recipes.PlasmaForgeRecipes;
import com.hbm.inventory.recipes.PrecAssRecipes;
import com.hbm.inventory.recipes.RockMillRecipes;
import com.hbm.inventory.recipes.loader.GenericRecipe;
import com.hbm.inventory.recipes.loader.GenericRecipes;
import com.hbm.items.machine.ItemBlueprints;
import com.hbm.modules.machine.ModuleMachineBase;
import com.hbm.tileentity.BlockEntityMachineBase;
import com.hbm.tileentity.machine.BlockEntityMachineAssemblyFactory;
import com.hbm.tileentity.machine.BlockEntityMachineAssemblyMachine;
import com.hbm.tileentity.machine.BlockEntityMachineChemicalFactory;
import com.hbm.tileentity.machine.BlockEntityMachineChemicalPlant;
import com.hbm.tileentity.machine.BlockEntityMachinePUREX;
import com.hbm.tileentity.machine.BlockEntityMachinePrecAss;
import com.hbm.tileentity.machine.BlockEntityMachineRockMill;
import com.hbm.tileentity.machine.fusion.BlockEntityFusionPlasmaForge;
import com.hbm.tileentity.machine.fusion.BlockEntityFusionTorus;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;

/**
 * Describes the recipe modules of the NTM machines that require a selected recipe before they accept inputs.
 */
final class NtmMachines {
    private static final Logger LOG = LoggerFactory.getLogger(NtmMachines.class);

    @Nullable
    private static final Field INPUT_SLOTS = findField("inputSlots");
    @Nullable
    private static final Field INPUT_TANKS = findField("inputTanks");

    private static final int FACTORY_BLUEPRINT_OFFSET = 4;

    private NtmMachines() {
    }

    record Unit(ModuleMachineBase module, GenericRecipes<?, ?> recipes, ItemStack blueprint) {
        boolean allows(GenericRecipe recipe) {
            return !recipe.isPooled() || recipe.isPartOfPool(ItemBlueprints.grabPool(blueprint));
        }
    }

    static List<Unit> units(BlockEntity be) {
        return switch (be) {
            case BlockEntityMachineAssemblyMachine m -> List.of(new Unit(m.recipeModule,
                    AssemblyMachineRecipes.INSTANCE, m.getItem(BlockEntityMachineAssemblyMachine.SLOT_BLUEPRINT)));
            case BlockEntityMachinePrecAss m -> List.of(new Unit(m.recipeModule,
                    PrecAssRecipes.INSTANCE, m.getItem(BlockEntityMachinePrecAss.SLOT_BLUEPRINT)));
            case BlockEntityMachineChemicalPlant m -> List.of(new Unit(m.module,
                    ChemicalPlantRecipes.INSTANCE, m.getItem(BlockEntityMachineChemicalPlant.SLOT_SCHEMATIC)));
            case BlockEntityMachineRockMill m -> List.of(new Unit(m.module,
                    RockMillRecipes.INSTANCE, m.getItem(BlockEntityMachineRockMill.SLOT_SCHEMATIC)));
            case BlockEntityMachinePUREX m -> List.of(new Unit(m.module,
                    PUREXRecipes.INSTANCE, m.getItem(BlockEntityMachinePUREX.SLOT_BLUEPRINT)));
            case BlockEntityFusionTorus m -> List.of(new Unit(m.module,
                    FusionRecipes.INSTANCE, m.getItem(BlockEntityFusionTorus.SLOT_BLUEPRINT)));
            case BlockEntityFusionPlasmaForge m -> List.of(new Unit(m.module,
                    PlasmaForgeRecipes.INSTANCE, m.getItem(BlockEntityFusionPlasmaForge.SLOT_BLUEPRINT)));
            case BlockEntityMachineAssemblyFactory m -> factory(m, m.module, AssemblyMachineRecipes.INSTANCE,
                    BlockEntityMachineAssemblyFactory.SLOTS_PER_MODULE);
            case BlockEntityMachineChemicalFactory m -> factory(m, m.module, ChemicalPlantRecipes.INSTANCE,
                    BlockEntityMachineChemicalFactory.SLOTS_PER_MODULE);
            default -> List.of();
        };
    }

    private static List<Unit> factory(BlockEntityMachineBase machine, ModuleMachineBase[] modules,
            GenericRecipes<?, ?> recipes, int slotsPerModule) {
        var result = new ArrayList<Unit>(modules.length);
        for (int i = 0; i < modules.length; i++) {
            result.add(new Unit(modules[i], recipes, machine.getItem(FACTORY_BLUEPRINT_OFFSET + i * slotsPerModule)));
        }
        return result;
    }

    static boolean isIdle(Unit unit, BlockEntityMachineBase machine) {
        if (INPUT_SLOTS == null || unit.module().progress > 0) {
            return false;
        }
        try {
            for (int slot : (int[]) INPUT_SLOTS.get(unit.module())) {
                if (!machine.getItem(slot).isEmpty()) {
                    return false;
                }
            }
            return true;
        } catch (IllegalAccessException e) {
            return false;
        }
    }

    static FluidTankNTM @Nullable [] inputTanks(Unit unit) {
        if (INPUT_TANKS == null) {
            return null;
        }
        try {
            return (FluidTankNTM[]) INPUT_TANKS.get(unit.module());
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    /**
     * Used for blocking mode. Unknown layouts count as occupied so we never overfill a module.
     */
    static boolean containsPatternInput(Unit unit, BlockEntityMachineBase machine, Set<AEKey> patternInputs) {
        if (INPUT_SLOTS == null || INPUT_TANKS == null) {
            return true;
        }
        try {
            for (int slot : (int[]) INPUT_SLOTS.get(unit.module())) {
                var key = AEItemKey.of(machine.getItem(slot));
                if (key != null && patternInputs.contains(key.dropSecondary())) {
                    return true;
                }
            }
            for (var tank : (FluidTankNTM[]) INPUT_TANKS.get(unit.module())) {
                var fluid = tank.getFluid();
                if (fluid != null && tank.getFill() > 0
                        && patternInputs.contains(AEFluidKey.of(fluid).dropSecondary())) {
                    return true;
                }
            }
            return false;
        } catch (IllegalAccessException e) {
            return true;
        }
    }

    @Nullable
    private static Field findField(String name) {
        try {
            var field = ModuleMachineBase.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warn("Failed to access NTM module field {}, recipe switching and blocking mode are disabled", name, e);
            return null;
        }
    }
}
