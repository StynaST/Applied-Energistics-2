package appeng.integration.modules.ntm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.hbm.inventory.fluid.FluidStackNTM;
import com.hbm.inventory.recipes.ingredient.CountIngredient;
import com.hbm.inventory.recipes.loader.GenericRecipe;
import com.hbm.inventory.recipes.loader.GenericRecipes;

import org.jetbrains.annotations.Nullable;

import net.minecraft.util.random.Weighted;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.item.ItemStack;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;

/**
 * Finds the NTM recipe a processing pattern was encoded from.
 */
final class NtmRecipeMatcher {
    private static final int MAX_CACHE_SIZE = 1024;

    private static final Map<CacheKey, CacheEntry> CACHE = new HashMap<>();

    private NtmRecipeMatcher() {
    }

    private record CacheKey(GenericRecipes<?, ?> recipes, AEItemKey definition) {
    }

    private record CacheEntry(List<?> rows, @Nullable GenericRecipe recipe) {
    }

    @Nullable
    static GenericRecipe find(GenericRecipes<?, ?> recipes, IPatternDetails pattern) {
        var rows = recipes.recipes();
        var key = new CacheKey(recipes, pattern.getDefinition());
        var cached = CACHE.get(key);
        if (cached != null && cached.rows() == rows) {
            return cached.recipe();
        }

        var recipe = search(rows, pattern);
        if (CACHE.size() >= MAX_CACHE_SIZE) {
            CACHE.clear();
        }
        CACHE.put(key, new CacheEntry(rows, recipe));
        return recipe;
    }

    @Nullable
    private static GenericRecipe search(List<? extends GenericRecipe> rows, IPatternDetails pattern) {
        var inputs = new ArrayList<GenericStack>();
        for (var input : pattern.getInputs()) {
            var possible = input.getPossibleInputs();
            if (possible.length > 0) {
                var first = possible[0];
                inputs.add(new GenericStack(first.what(), first.amount() * input.getMultiplier()));
            }
        }

        for (var recipe : rows) {
            if (matches(recipe, inputs, pattern.getOutputs())) {
                return recipe;
            }
        }
        return null;
    }

    private static boolean matches(GenericRecipe recipe, List<GenericStack> inputs, List<GenericStack> outputs) {
        for (var output : outputs) {
            if (!produces(recipe, output.what())) {
                return false;
            }
        }

        var left = new long[inputs.size()];
        for (int i = 0; i < left.length; i++) {
            left[i] = inputs.get(i).amount();
        }

        if (recipe.inputItem != null) {
            for (CountIngredient ingredient : recipe.inputItem) {
                if (!consumeItem(ingredient, inputs, left)) {
                    return false;
                }
            }
        }

        if (recipe.inputFluid != null) {
            for (FluidStackNTM fluid : recipe.inputFluid) {
                if (!consumeFluid(fluid, inputs, left)) {
                    return false;
                }
            }
        }

        for (int i = 0; i < left.length; i++) {
            if (left[i] == inputs.get(i).amount()) {
                return false;
            }
        }
        return true;
    }

    private static boolean consumeItem(CountIngredient ingredient, List<GenericStack> inputs, long[] left) {
        for (int i = 0; i < left.length; i++) {
            if (inputs.get(i).what() instanceof AEItemKey itemKey && left[i] >= ingredient.count()
                    && ingredient.matchesItem(itemKey.toStack())) {
                left[i] -= ingredient.count();
                return true;
            }
        }
        return false;
    }

    private static boolean consumeFluid(FluidStackNTM fluid, List<GenericStack> inputs, long[] left) {
        boolean offered = false;
        for (int i = 0; i < left.length; i++) {
            if (inputs.get(i).what() instanceof AEFluidKey fluidKey && fluidKey.getFluid() == fluid.type()) {
                offered = true;
                if (left[i] >= fluid.amount()) {
                    left[i] -= fluid.amount();
                    return true;
                }
            }
        }
        return !offered;
    }

    private static boolean produces(GenericRecipe recipe, AEKey what) {
        if (what instanceof AEItemKey itemKey) {
            WeightedList<ItemStack>[] outputs = recipe.outputItems();
            if (outputs == null) {
                return false;
            }
            for (var output : outputs) {
                for (Weighted<ItemStack> entry : output.unwrap()) {
                    if (!entry.value().isEmpty() && itemKey.matches(entry.value())) {
                        return true;
                    }
                }
            }
        } else if (what instanceof AEFluidKey fluidKey && recipe.outputFluid != null) {
            for (var fluid : recipe.outputFluid) {
                if (fluid.type() == fluidKey.getFluid()) {
                    return true;
                }
            }
        }
        return false;
    }
}
