package appeng.integration.modules.ntm;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

import appeng.api.AECapabilities;

/**
 * Lets pattern providers push into NTM recipe-select machines, selecting the recipe the pattern encodes.
 */
public final class NtmIntegration {
    public static final String MOD_ID = "hbm";

    private NtmIntegration() {
    }

    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        var blocks = BuiltInRegistries.BLOCK.stream()
                .filter(block -> MOD_ID.equals(BuiltInRegistries.BLOCK.getKey(block).getNamespace()))
                .toArray(Block[]::new);
        if (blocks.length > 0) {
            event.registerBlock(AECapabilities.CRAFTING_MACHINE, NtmCraftingMachine::find, blocks);
        }
    }
}
