package appeng.integration.modules.ntm;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.hbm.api.fluidmk2.IFluidHandlerMK2;
import com.hbm.capability.NtmCapabilities;
import com.hbm.inventory.fluid.FluidStackNTM;
import com.hbm.inventory.fluid.tank.FluidTankNTM;
import com.hbm.inventory.recipes.loader.GenericRecipe;
import com.hbm.tileentity.BlockEntityMachineBase;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

import appeng.api.crafting.IPatternDetails;
import appeng.api.implementations.blockentities.ICraftingMachine;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;

/**
 * Selects the recipe a pushed pattern was encoded from on the machine, then inserts the items through the machine's
 * item capability at the face the pattern provider is attached to, and the fluids directly into the module's tanks.
 */
final class NtmCraftingMachine implements ICraftingMachine {
    private final Level level;
    private final BlockPos pos;
    private final Direction side;
    private final BlockEntityMachineBase machine;
    private final List<NtmMachines.Unit> units;

    private NtmCraftingMachine(Level level, BlockPos pos, Direction side, BlockEntityMachineBase machine,
            List<NtmMachines.Unit> units) {
        this.level = level;
        this.pos = pos;
        this.side = side;
        this.machine = machine;
        this.units = units;
    }

    @Nullable
    static ICraftingMachine find(Level level, BlockPos pos, BlockState state, @Nullable BlockEntity be,
            @Nullable Direction side) {
        if (side == null || level.isClientSide()) {
            return null;
        }

        var owner = be instanceof BlockEntityMachineBase ? be : NtmCapabilities.itemOwnerAt(level, pos, state, side);
        if (!(owner instanceof BlockEntityMachineBase machine) || machine.isRemoved()) {
            return null;
        }

        var units = NtmMachines.units(machine);
        if (units.isEmpty()) {
            return null;
        }
        return new NtmCraftingMachine(level, pos.immutable(), side, machine, units);
    }

    @Override
    public PatternContainerGroup getCraftingMachineInfo() {
        var icon = AEItemKey.of(machine.getBlockState().getBlock());
        return new PatternContainerGroup(icon, machine.getDisplayName(), List.of());
    }

    @Override
    public boolean acceptsPlans() {
        return !machine.isRemoved();
    }

    @Override
    public boolean containsPatternInput(Set<AEKey> patternInputs) {
        for (var unit : units) {
            if (!NtmMachines.containsPatternInput(unit, machine, patternInputs)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputs, Direction ejectionDirection) {
        var items = level.getCapability(Capabilities.Item.BLOCK, pos, side);
        if (items == null) {
            return false;
        }

        var recipe = NtmRecipeMatcher.find(units.getFirst().recipes(), patternDetails);
        if (recipe == null) {
            var fluids = hasFluids(inputs) ? level.getCapability(Capabilities.Fluid.BLOCK, pos, side) : null;
            return insertAll(items, fluids, null, inputs);
        }

        for (var unit : candidates(recipe)) {
            if (tryPush(unit, recipe, items, inputs)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Modules already running the recipe come first, so we only switch an idle module when needed.
     */
    private List<NtmMachines.Unit> candidates(GenericRecipe recipe) {
        var name = recipe.getInternalName();
        var result = new ArrayList<NtmMachines.Unit>(units.size());
        for (var unit : units) {
            if (unit.allows(recipe) && name.equals(unit.module().getRecipeName())) {
                result.add(unit);
            }
        }
        for (var unit : units) {
            if (unit.allows(recipe) && !name.equals(unit.module().getRecipeName())
                    && NtmMachines.isIdle(unit, machine)) {
                result.add(unit);
            }
        }
        return result;
    }

    private boolean tryPush(NtmMachines.Unit unit, GenericRecipe recipe, ResourceHandler<ItemResource> items,
            KeyCounter[] inputs) {
        var module = unit.module();
        var previousRecipe = module.getRecipeName();
        var previousRestricted = module.restrictedMode;
        var switching = !previousRecipe.equals(recipe.getInternalName());
        if (switching) {
            module.setRecipe(recipe.getInternalName());
        }

        FluidTankNTM[] tanks = null;
        List<FluidStackNTM> previousTanks = null;
        if (hasFluids(inputs)) {
            tanks = NtmMachines.inputTanks(unit);
            if (switching && machine instanceof IFluidHandlerMK2 fluidHandler) {
                previousTanks = FluidStackNTM.snapshot(fluidHandler.getAllTanks());
            }
            module.setupTanks(recipe);
        }

        var ok = (tanks != null || !hasFluids(inputs)) && insertAll(items, null, tanks, inputs);
        if (ok) {
            module.markDirty = true;
            return true;
        }

        if (switching) {
            module.setRecipe(previousRecipe, previousRestricted);
            if (previousTanks != null && machine instanceof IFluidHandlerMK2 fluidHandler) {
                module.setupTanks(module.getRecipe());
                FluidStackNTM.restore(previousTanks, fluidHandler.getAllTanks());
            }
        }
        return false;
    }

    private boolean insertAll(ResourceHandler<ItemResource> items, @Nullable ResourceHandler<FluidResource> fluids,
            FluidTankNTM @Nullable [] tanks, KeyCounter[] inputs) {
        var planned = tanks == null ? null : new int[tanks.length];
        var plannedFluid = tanks == null ? null : new Fluid[tanks.length];

        try (var tx = Transaction.openRoot()) {
            for (var counter : inputs) {
                for (var entry : counter) {
                    var what = entry.getKey();
                    var amount = entry.getLongValue();
                    if (amount > Integer.MAX_VALUE) {
                        return false;
                    }

                    boolean inserted;
                    if (what instanceof AEItemKey itemKey) {
                        inserted = items.insert(itemKey.toResource(), (int) amount, tx) == amount;
                    } else if (what instanceof AEFluidKey fluidKey && tanks != null) {
                        inserted = planFill(tanks, planned, plannedFluid, fluidKey.getFluid(), (int) amount);
                    } else if (what instanceof AEFluidKey fluidKey && fluids != null) {
                        inserted = fluids.insert(fluidKey.toResource(), (int) amount, tx) == amount;
                    } else {
                        inserted = false;
                    }

                    if (!inserted) {
                        return false;
                    }
                }
            }
            tx.commit();
        }

        if (tanks != null) {
            for (int i = 0; i < tanks.length; i++) {
                if (planned[i] > 0) {
                    tanks[i].fill(plannedFluid[i], planned[i], true);
                }
            }
        }
        machine.setChanged();
        return true;
    }

    private static boolean planFill(FluidTankNTM[] tanks, int[] planned, Fluid[] plannedFluid, Fluid fluid,
            int amount) {
        for (int i = 0; i < tanks.length; i++) {
            if (planned[i] > 0 && plannedFluid[i] != fluid) {
                continue;
            }
            if (tanks[i].fill(fluid, Integer.MAX_VALUE, false) - planned[i] >= amount) {
                planned[i] += amount;
                plannedFluid[i] = fluid;
                return true;
            }
        }
        return false;
    }

    private static boolean hasFluids(KeyCounter[] inputs) {
        for (var counter : inputs) {
            for (var entry : counter) {
                if (entry.getKey() instanceof AEFluidKey) {
                    return true;
                }
            }
        }
        return false;
    }
}
