package com.hugman.uhc.modifier;

import com.hugman.uhc.UHC;
import com.hugman.uhc.registry.UHCRegistries;
import com.mojang.serialization.MapCodec;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public record ModifierType<T extends Modifier>(MapCodec<T> codec) {
    public static final ModifierType<BlockLootModifier> BLOCK_LOOT = register("block_loot", BlockLootModifier.CODEC);
    public static final ModifierType<EntityLootModifier> ENTITY_LOOT = register("entity_loot", EntityLootModifier.CODEC);
    public static final ModifierType<TraversalBreakModifier> TRAVERSAL_BREAK = register("traversal_break", TraversalBreakModifier.CODEC);
    public static final ModifierType<PlayerAttributeModifier> PLAYER_ATTRIBUTE = register("player_attribute", PlayerAttributeModifier.CODEC);
    public static final ModifierType<PermanentEffectModifier> PERMANENT_EFFECT = register("permanent_effect", PermanentEffectModifier.CODEC);
    public static final ModifierType<PlacedFeaturesModifier> PLACED_FEATURES = register("placed_features", PlacedFeaturesModifier.CODEC);
    public static final ModifierType<ReplaceStackModifier> REPLACE_STACK = register("replace_stack", ReplaceStackModifier.CODEC);

    private static <T extends Modifier> ModifierType<T> register(String name, MapCodec<T> codec) {
        return register(UHC.id(name), codec);
    }

    public static <T extends Modifier> ModifierType<T> register(Identifier identifier, MapCodec<T> codec) {
        return Registry.register(UHCRegistries.MODIFIER_TYPE, identifier, new ModifierType<>(codec));
    }
}
