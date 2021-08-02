package com.hugman.uhc.game;

import com.hugman.uhc.config.UHCConfig;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;
import xyz.nucleoid.map_templates.BlockBounds;
import xyz.nucleoid.plasmid.util.ColoredBlocks;

import java.util.HashMap;
import java.util.Map;

public class UHCSpawner {
	private final ServerWorld world;
	private final UHCConfig config;
	private final Map<UHCTeam, BlockBounds> cages = new HashMap<>();

	public UHCSpawner(ServerWorld world, UHCConfig config) {
		this.world = world;
		this.config = config;
	}

	public static Vec3d getSurfaceBlock(ServerWorld world, int x, int z) {
		WorldChunk chunk = world.getWorldChunk(new BlockPos(x, 0, z));
		return new Vec3d(x, chunk.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) + 1, z);
	}

	public void spawnPlayerAtCenter(ServerPlayerEntity player) {
		this.spawnPlayerAt(player, this.getSurfaceBlock(0, 0));
	}

	public void spawnPlayerAt(ServerPlayerEntity player, BlockPos pos) {
		ChunkPos chunkPos = new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
		this.world.getChunkManager().addTicket(ChunkTicketType.POST_TELEPORT, chunkPos, 1, player.getId());
		player.teleport(this.world, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 0.0F, 0.0F);
	}

	public void putParticipantInGame(UHCTeam team, ServerPlayerEntity participant) {
		BlockBounds bounds = this.cages.get(team);
		if(bounds != null) {
			this.spawnPlayerAt(participant, new BlockPos(bounds.centerBottom()).up());
		}
	}

	public void summonCage(UHCTeam team, int x, int z) {
		BlockPos pos = new BlockPos(x, 200, z);
		if(this.world.isSkyVisible(pos)) {
			pos = new BlockPos(x, 200, z);
		}
		this.addCageAt(team, pos, Blocks.BARRIER.getDefaultState(), 3, 4);
	}

	public void addCageAt(UHCTeam team, BlockPos origin, BlockState sides, int width, int height) {
		ServerWorld world = this.world;
		BlockState floor = ColoredBlocks.glass(team.getColor()).getDefaultState();

		BlockBounds fullCage = BlockBounds.of(origin.down().north(width).east(width), origin.up(height).south(width).west(width));
		BlockBounds cageFloor = BlockBounds.of(origin.down().north(width - 1).east(width - 1), origin.down().south(width - 1).west(width - 1));
		BlockBounds cageAir = BlockBounds.of(origin.north(width - 1).east(width - 1), origin.up(height - 1).south(width - 1).west(width - 1));

		fullCage.forEach(pos -> world.setBlockState(pos, sides));
		cageFloor.forEach(pos -> world.setBlockState(pos, floor));
		cageAir.forEach(pos -> world.setBlockState(pos, Blocks.AIR.getDefaultState()));

		this.cages.put(team, fullCage);
	}

	public void clearCages() {
		this.cages.values().forEach(bounds -> bounds.forEach(pos -> this.world.setBlockState(pos, Blocks.AIR.getDefaultState())));
	}

	public BlockPos getSurfaceBlock(int x, int z) {
		return new BlockPos(getSurfaceBlock(world, x, z));
	}
}
