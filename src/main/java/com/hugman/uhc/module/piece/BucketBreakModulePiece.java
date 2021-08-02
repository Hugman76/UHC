package com.hugman.uhc.module.piece;

import com.hugman.uhc.game.phase.UHCActive;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.block.BlockState;
import net.minecraft.block.LeavesBlock;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.rule.RuleTest;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import xyz.nucleoid.plasmid.util.BlockTraversal;

public record BucketBreakModulePiece(RuleTest predicate, int amount) implements ModulePiece {
	public static final Codec<BucketBreakModulePiece> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			RuleTest.TYPE_CODEC.fieldOf("target").forGetter(module -> module.predicate),
			Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("amount", 128).forGetter(module -> module.amount)
	).apply(instance, BucketBreakModulePiece::new));

	@Override
	public Codec<? extends ModulePiece> getCodec() {
		return CODEC;
	}

	public void breakBlock(UHCActive active, @Nullable LivingEntity entity, BlockPos origin) {
		ServerWorld world = active.world;
		BlockState state = world.getBlockState(origin);

		if(this.predicate.test(state, world.getRandom())) {
			BlockTraversal traversal = BlockTraversal.create()
					.order(BlockTraversal.Order.BREADTH_FIRST)
					.connectivity(BlockTraversal.Connectivity.TWENTY_SIX);
			traversal.accept(origin, (nextPos, fromPos, depth) -> {
				if(depth > this.amount) {
					return BlockTraversal.Result.TERMINATE;
				}
				if(origin.asLong() == nextPos.asLong()) {
					return BlockTraversal.Result.CONTINUE;
				}
				BlockState previousState = world.getBlockState(fromPos);
				BlockState nextState = world.getBlockState(nextPos);
				if(this.predicate.test(nextState, world.getRandom())) {
					world.breakBlock(nextPos, true, entity);
					return BlockTraversal.Result.CONTINUE;
				}
				else {
					if(nextState.getBlock() instanceof LeavesBlock) {
						if(!(previousState.getBlock() instanceof LeavesBlock)) {
							if(nextState.get(LeavesBlock.DISTANCE) == 1) {
								world.breakBlock(nextPos, true, entity);
								return BlockTraversal.Result.CONTINUE;
							}
						}
						else {
							if(nextState.get(LeavesBlock.DISTANCE) > previousState.get(LeavesBlock.DISTANCE)) {
								world.breakBlock(nextPos, true, entity);
								return BlockTraversal.Result.CONTINUE;
							}
						}
					}
				}
				return BlockTraversal.Result.TERMINATE;
			});
		}
	}
}
