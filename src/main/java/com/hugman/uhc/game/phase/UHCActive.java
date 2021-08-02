package com.hugman.uhc.game.phase;

import com.google.common.collect.Multimap;
import com.hugman.uhc.config.UHCConfig;
import com.hugman.uhc.game.UHCBar;
import com.hugman.uhc.game.UHCLogic;
import com.hugman.uhc.game.UHCParticipant;
import com.hugman.uhc.game.UHCSideBar;
import com.hugman.uhc.game.UHCSpawner;
import com.hugman.uhc.game.UHCTeam;
import com.hugman.uhc.game.map.UHCMap;
import com.hugman.uhc.module.piece.BlockLootModulePiece;
import com.hugman.uhc.module.piece.BucketBreakModulePiece;
import com.hugman.uhc.module.piece.EntityLootModulePiece;
import com.hugman.uhc.module.piece.PermanentEffectModulePiece;
import com.hugman.uhc.module.piece.PlayerAttributeModulePiece;
import com.hugman.uhc.util.TickUtil;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.WorldBorderInitializeS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldBorderInterpolateSizeS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.LiteralText;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Texts;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.GameMode;
import net.minecraft.world.explosion.Explosion;
import org.jetbrains.annotations.Nullable;
import xyz.nucleoid.plasmid.game.GameActivity;
import xyz.nucleoid.plasmid.game.GameCloseReason;
import xyz.nucleoid.plasmid.game.GameSpace;
import xyz.nucleoid.plasmid.game.common.GlobalWidgets;
import xyz.nucleoid.plasmid.game.event.GameActivityEvents;
import xyz.nucleoid.plasmid.game.event.GamePlayerEvents;
import xyz.nucleoid.plasmid.game.player.PlayerOffer;
import xyz.nucleoid.plasmid.game.player.PlayerOfferResult;
import xyz.nucleoid.plasmid.game.player.PlayerSet;
import xyz.nucleoid.plasmid.game.rule.GameRuleType;
import xyz.nucleoid.stimuli.event.block.BlockBreakEvent;
import xyz.nucleoid.stimuli.event.block.BlockDropItemsEvent;
import xyz.nucleoid.stimuli.event.entity.EntityDropItemsEvent;
import xyz.nucleoid.stimuli.event.player.PlayerDamageEvent;
import xyz.nucleoid.stimuli.event.player.PlayerDeathEvent;
import xyz.nucleoid.stimuli.event.world.ExplosionDetonatedEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public class UHCActive {
	public final GameSpace gameSpace;
	public final ServerWorld world;
	private final GameActivity activity;
	private final UHCMap map;
	private final UHCConfig config;

	private final Object2ObjectMap<ServerPlayerEntity, UHCParticipant> participants;
	private final Multimap<UHCTeam, ServerPlayerEntity> teamMap;

	private final UHCLogic logic;
	private final UHCSpawner spawnLogic;
	private final UHCBar bar;
	private final UHCSideBar sideBar;

	private long gameStartTick;
	private long startInvulnerableTick;
	private long startWarmupTick;
	private long finaleCagesTick;
	private long finaleInvulnerabilityTick;
	private long reducingTick;
	private long deathMatchTick;
	private long gameEndTick;
	private long gameCloseTick;

	private boolean invulnerable;
	private boolean isFinished = false;

	private UHCActive(GameActivity activity, ServerWorld world, UHCConfig config, UHCMap map, GlobalWidgets widgets, Object2ObjectMap<ServerPlayerEntity, UHCParticipant> participants, Multimap<UHCTeam, ServerPlayerEntity> teamMap) {
		this.activity = activity;
		this.gameSpace = this.activity.getGameSpace();
		this.world = world;
		this.map = map;
		this.config = config;

		this.participants = participants;
		this.teamMap = teamMap;

		this.logic = new UHCLogic(config, this.participants.size());
		this.spawnLogic = new UHCSpawner(this.world, this.config);
		this.bar = UHCBar.create(widgets, this.gameSpace);
		this.sideBar = UHCSideBar.create(widgets, this);
	}

	public static void start(GameSpace gameSpace, ServerWorld world, UHCConfig config, UHCMap map, Object2ObjectMap<ServerPlayerEntity, UHCParticipant> participants, Multimap<UHCTeam, ServerPlayerEntity> teams) {
		gameSpace.setActivity(activity -> {
			GlobalWidgets widgets = GlobalWidgets.addTo(activity);
			UHCActive active = new UHCActive(activity, world, config, map, widgets, participants, teams);

			activity.allow(GameRuleType.CRAFTING);
			activity.deny(GameRuleType.PORTALS);
			activity.deny(GameRuleType.PVP);
			activity.allow(GameRuleType.BLOCK_DROPS);
			activity.allow(GameRuleType.FALL_DAMAGE);
			activity.allow(GameRuleType.HUNGER);

			activity.listen(GameActivityEvents.ENABLE, active::enable);
			activity.listen(GameActivityEvents.DISABLE, active::disable);

			activity.listen(GamePlayerEvents.OFFER, active::offerPlayer);
			activity.listen(GamePlayerEvents.LEAVE, active::playerLeave);

			activity.listen(GameActivityEvents.TICK, active::tick);

			activity.listen(PlayerDamageEvent.EVENT, active::onPlayerDamage);
			activity.listen(PlayerDeathEvent.EVENT, active::onPlayerDeath);

			activity.listen(EntityDropItemsEvent.EVENT, active::onMobLoot);
			activity.listen(BlockBreakEvent.EVENT, active::onBlockBroken);
			activity.listen(BlockDropItemsEvent.EVENT, active::onBlockDrop);
			activity.listen(ExplosionDetonatedEvent.EVENT, active::onExplosion);
		});
	}

	// GENERAL GAME MANAGEMENT
	private void enable() {
		ServerWorld world = this.world;

		// Setup
		world.getWorldBorder().setCenter(0, 0);
		world.getWorldBorder().setSize(this.logic.getStartMapSize());
		world.getWorldBorder().setDamagePerBlock(0.5);
		this.gameSpace.getPlayers().forEach(player -> player.networkHandler.sendPacket(new WorldBorderInitializeS2CPacket(world.getWorldBorder())));

		this.gameStartTick = world.getTime();
		this.startInvulnerableTick = world.getTime() + this.logic.getInCagesTime();
		this.startWarmupTick = this.startInvulnerableTick + this.logic.getInvulnerabilityTime();
		this.finaleCagesTick = this.startWarmupTick + this.logic.getWarmupTime();
		this.finaleInvulnerabilityTick = this.finaleCagesTick + this.logic.getInCagesTime();
		this.reducingTick = this.finaleInvulnerabilityTick + this.logic.getInvulnerabilityTime();
		this.deathMatchTick = this.reducingTick + this.logic.getShrinkingTime();
		this.gameEndTick = this.deathMatchTick + this.logic.getDeathmatchTime();
		this.gameCloseTick = this.gameEndTick + 600;

		// Start - Cage chapter
		this.participants.keySet().forEach(player -> {
			this.resetPlayer(player);
			this.refreshPlayerAttributes(player);
			player.changeGameMode(GameMode.ADVENTURE);
		});
		this.tpToCages();
		this.bar.set("text.uhc.dropping", this.logic.getInCagesTime(), this.startInvulnerableTick, BossBar.Color.PURPLE);
	}

	private void tick() {
		ServerWorld world = this.world;
		long worldTime = world.getTime();

		this.bar.tick(world);
		this.sideBar.update(worldTime - this.gameStartTick, (int) world.getWorldBorder().getSize());


		// Game ends
		if(isFinished) {
			if(worldTime > this.gameCloseTick) {
				this.gameSpace.close(GameCloseReason.FINISHED);
			}
			return;
		}

		// Start - Cage chapter (@ 80%)
		if(worldTime == this.startInvulnerableTick - (logic.getInCagesTime() * 0.8)) {
			this.sendModuleListToChat();
		}
		// Start - Invulnerable chapter
		else if(worldTime == this.startInvulnerableTick) {
			this.dropCages();
			this.sendInfo("text.uhc.dropped_players");
			this.sendInfo("text.uhc.world_will_shrink", TickUtil.formatPretty(this.finaleCagesTick - worldTime));

			this.bar.set("🛡", "text.uhc.vulnerable", this.logic.getInvulnerabilityTime(), this.startWarmupTick, BossBar.Color.YELLOW);
		}

		// Start - Warmup chapter
		else if(worldTime == this.startWarmupTick) {
			this.setInvulnerable(false);
			this.sendWarning("🛡", "text.uhc.no_longer_immune");

			this.bar.set("text.uhc.tp", this.logic.getWarmupTime(), this.finaleCagesTick, BossBar.Color.BLUE);
		}

		// Finale - Cages chapter
		else if(worldTime == this.finaleCagesTick) {
			this.participants.keySet().forEach(player -> {
				this.clearPlayer(player);
				this.refreshPlayerAttributes(player);
				player.changeGameMode(GameMode.ADVENTURE);
			});
			this.tpToCages();
			this.sendInfo("text.uhc.shrinking_when_pvp");

			this.bar.set("text.uhc.dropping", this.logic.getInCagesTime(), this.finaleInvulnerabilityTick, BossBar.Color.PURPLE);
		}

		// Finale - Invulnerability chapter
		else if(worldTime == this.finaleInvulnerabilityTick) {
			this.dropCages();
			this.sendInfo("text.uhc.dropped_players");

			this.bar.set("🗡", "text.uhc.pvp", this.logic.getInvulnerabilityTime(), this.reducingTick, BossBar.Color.YELLOW);
		}

		// Finale - Reducing chapter
		else if(worldTime == this.reducingTick) {
			this.setInvulnerable(false);
			this.sendWarning("🛡", "text.uhc.no_longer_immune");

			this.setPvp(true);
			this.sendWarning("🗡", "text.uhc.pvp_enabled");

			world.getWorldBorder().interpolateSize(this.logic.getStartMapSize(), this.logic.getEndMapSize(), this.logic.getShrinkingTime() * 50L);
			this.gameSpace.getPlayers().forEach(player -> player.networkHandler.sendPacket(new WorldBorderInterpolateSizeS2CPacket(world.getWorldBorder())));
			this.sendWarning("text.uhc.shrinking_start");

			this.bar.set("text.uhc.shrinking_finish", this.logic.getShrinkingTime(), this.deathMatchTick, BossBar.Color.RED);
		}

		// Finale - Deathmatch chapter
		else if(worldTime == this.deathMatchTick) {
			this.bar.setFull(new LiteralText("🗡").append(new TranslatableText("text.uhc.deathmatchTime")).append("🗡"));
			world.getWorldBorder().setDamagePerBlock(2.5);
			world.getWorldBorder().setSafeZone(0.125);
			this.sendInfo("🗡", "text.uhc.last_one_wins");
			this.checkForWinner();
		}
	}

	private void disable() {
		teamMap.keySet().forEach(team -> gameSpace.getServer().getScoreboard().removeTeam(team.getTeam()));
	}

	// GENERAL PLAYER MANAGEMENT
	public Object2ObjectMap<ServerPlayerEntity, UHCParticipant> getParticipants() {
		return participants;
	}

	private UHCParticipant getParticipant(ServerPlayerEntity player) {
		return participants.get(player);
	}

	@Nullable
	public UHCTeam getTeam(ServerPlayerEntity player) {
		for(UHCTeam theTowersTeam : this.teamMap.keys()) {
			if(this.teamMap.get(theTowersTeam).contains(player)) return theTowersTeam;
		}
		return null;
	}

	private PlayerOfferResult offerPlayer(PlayerOffer offer) {
		return offer.accept(this.world, UHCSpawner.getSurfaceBlock(world, 0, 0)).and(() -> {
			ServerPlayerEntity player = offer.player();

			player.changeGameMode(GameMode.SPECTATOR);
			player.networkHandler.sendPacket(new WorldBorderInitializeS2CPacket(this.world.getWorldBorder()));
		});
	}

	private void playerLeave(ServerPlayerEntity player) {
		if(participants.containsKey(player)) {
			PlayerSet players = this.gameSpace.getPlayers();
			players.sendMessage(new LiteralText("\n☠ ").append(new TranslatableText("text.uhc.player_eliminated", player.getDisplayName())).append("\n").formatted(Formatting.DARK_RED));
			players.playSound(SoundEvents.ENTITY_WITHER_SPAWN);
			this.eliminateParticipant(player);
		}
	}

	private ActionResult onPlayerDeath(ServerPlayerEntity player, DamageSource source) {
		if(participants.containsKey(player)) {
			PlayerSet players = this.gameSpace.getPlayers();
			players.sendMessage(new LiteralText("\n☠ ").append(source.getDeathMessage(player).copy()).append("!\n").formatted(Formatting.DARK_RED));
			players.playSound(SoundEvents.ENTITY_WITHER_SPAWN);
			this.eliminateParticipant(player);
		}
		else {
			this.spawnLogic.spawnPlayerAtCenter(player);
		}
		return ActionResult.FAIL;
	}

	private void eliminateParticipant(ServerPlayerEntity player) {
		ItemScatterer.spawn(player.getServerWorld(), player.getBlockPos(), player.getInventory());
		player.changeGameMode(GameMode.SPECTATOR);
		this.resetPlayer(player);
		this.spawnLogic.spawnPlayerAtCenter(player);
		this.participants.remove(player);
		UHCTeam team = getTeam(player);
		if(team != null) {
			if(team.getTeam().getPlayerList().size() == 1) {
				this.world.getScoreboard().removeTeam(team.getTeam());
			}
			this.teamMap.values().remove(player);
		}
		this.checkForWinner();
	}

	public void resetPlayer(ServerPlayerEntity player) {
		this.clearPlayer(player);
		player.getInventory().clear();
		player.getEnderChestInventory().clear();
		player.clearStatusEffects();
		player.getHungerManager().setFoodLevel(20);
		player.setExperienceLevel(0);
		player.setExperiencePoints(0);
		player.setHealth(player.getMaxHealth());
	}

	public void clearPlayer(ServerPlayerEntity player) {
		player.extinguish();
		player.fallDistance = 0.0F;
	}

	public void refreshPlayerAttributes(ServerPlayerEntity player) {
		for(PlayerAttributeModulePiece piece : this.config.playerAttributeModulePieces) {
			piece.setAttribute(player);
		}
	}

	public void applyPlayerEffects(ServerPlayerEntity player, int effectDuration) {
		for(PermanentEffectModulePiece piece : this.config.permanentEffectModulePieces) {
			piece.setEffect(player, effectDuration);
		}
	}

	private void checkForWinner() {
		PlayerSet players = this.gameSpace.getPlayers();

		// Remove empty teams
		this.teamMap.keys().forEach(team -> {
			if(this.teamMap.get(team).isEmpty()) {
				gameSpace.getServer().getScoreboard().removeTeam(team.getTeam());
				this.teamMap.keys().remove(team);
			}
		});
		if(this.teamMap.keySet().size() <= 1) {
			Optional<UHCTeam> oTeam = this.teamMap.keySet().stream().findFirst();
			if(oTeam.isPresent()) {
				UHCTeam team = oTeam.get();
				Collection<ServerPlayerEntity> teamMembers = this.teamMap.get(team);
				if(teamMembers.size() > 1) {
					players.sendMessage(new LiteralText("\n").append(new TranslatableText("text.uhc.player_win.team", Texts.join(teamMembers, PlayerEntity::getName)).formatted(Formatting.BOLD, Formatting.GOLD)).append("\n"));
				}
				else {
					Optional<ServerPlayerEntity> participant = teamMembers.stream().findFirst();
					if(participant.isPresent()) {
						players.sendMessage(new LiteralText("\n").append(new TranslatableText("text.uhc.player_win.solo", participant.get().getName()).formatted(Formatting.BOLD, Formatting.GOLD)).append("\n"));
					}
					else {
						players.sendMessage(new LiteralText("\n").append(new TranslatableText("text.uhc.none_win").formatted(Formatting.BOLD, Formatting.GOLD)).append("\n"));
					}
				}
				teamMembers.forEach(playerEntity -> playerEntity.changeGameMode(GameMode.ADVENTURE));
				this.setInvulnerable(true);
				this.setPvp(false);
			}
			else {
				players.sendMessage(new LiteralText("\n").append(new TranslatableText("text.uhc.none_win").formatted(Formatting.BOLD, Formatting.GOLD)).append("\n"));
			}
			players.playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE);
			this.gameCloseTick = this.world.getTime() + 200;
			this.bar.close();
			this.isFinished = true;
			this.participants.clear();
		}
	}

	// GAME STATES
	private void setInvulnerable(boolean b) {
		this.invulnerable = b;
		if(b) {
			this.activity.deny(GameRuleType.HUNGER);
			this.activity.deny(GameRuleType.FALL_DAMAGE);
		}
		else {
			this.activity.allow(GameRuleType.HUNGER);
			this.activity.allow(GameRuleType.FALL_DAMAGE);
		}
	}

	private void setPvp(boolean b) {
		if(b) {
			this.activity.allow(GameRuleType.PVP);
		}
		else {
			this.activity.deny(GameRuleType.PVP);
		}
	}

	private void tpToCages() {
		this.setInvulnerable(true);
		this.activity.deny(GameRuleType.BREAK_BLOCKS);
		this.activity.deny(GameRuleType.PLACE_BLOCKS);
		this.activity.deny(GameRuleType.INTERACTION);
		this.activity.deny(GameRuleType.CRAFTING);

		int index = 0;
		for(UHCTeam team : this.teamMap.keySet()) {
			double theta = ((double) index++ / this.teamMap.size()) * 2 * Math.PI;

			int x = MathHelper.floor(Math.cos(theta) * (this.logic.getStartMapSize() / 2 - this.config.getMapConfig().spawnOffset()));
			int z = MathHelper.floor(Math.sin(theta) * (this.logic.getStartMapSize() / 2 - this.config.getMapConfig().spawnOffset()));

			this.spawnLogic.summonCage(team, x, z);
		}
		this.teamMap.forEach(this.spawnLogic::putParticipantInGame);
	}

	private void dropCages() {
		this.spawnLogic.clearCages();
		this.activity.allow(GameRuleType.BREAK_BLOCKS);
		this.activity.allow(GameRuleType.PLACE_BLOCKS);
		this.activity.allow(GameRuleType.INTERACTION);
		this.activity.allow(GameRuleType.CRAFTING);

		this.participants.keySet().forEach(player -> {
			player.changeGameMode(GameMode.SURVIVAL);
			this.refreshPlayerAttributes(player);
			this.clearPlayer(player);
			this.applyPlayerEffects(player, (int) this.gameEndTick);
		});
	}

	// MESSAGES
	private void sendMessage(String symbol, String s, Formatting f, Object... args) {
		this.gameSpace.getPlayers().sendMessage(new LiteralText(symbol).append(new TranslatableText(s, args)).formatted(f));
	}

	public void sendInfo(String symbol, String s, Object... args) {
		this.sendMessage(symbol + " ", s, Formatting.YELLOW, args);
	}

	public void sendInfo(String s, Object... args) {
		this.sendMessage("", s, Formatting.YELLOW, args);
	}

	private void sendWarning(String symbol, String s, Object... args) {
		this.sendMessage(symbol + " ", s, Formatting.RED, args);
	}

	private void sendWarning(String s, Object... args) {
		this.sendMessage("", s, Formatting.RED, args);
	}

	public void sendModuleListToChat() {
		if(!this.config.getModules().isEmpty()) {
			MutableText text = new LiteralText("\n").append(new TranslatableText("text.uhc.modules_enabled").formatted(Formatting.GOLD));
			this.config.getModules().forEach(module -> {
				Style style = Style.EMPTY.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new TranslatableText(module.description().orElseGet(() -> module.translation() + ".description"))));
				text.append(new LiteralText("\n  - ").formatted(Formatting.WHITE)).append(Texts.bracketed(new TranslatableText(module.translation()).formatted(Formatting.GREEN)).setStyle(style));
			});
			text.append("\n");
			this.gameSpace.getPlayers().sendMessage(text);
			this.gameSpace.getPlayers().playSound(SoundEvents.ENTITY_ITEM_PICKUP);
		}
	}

	// GENERAL LISTENERS
	private ActionResult onPlayerDamage(ServerPlayerEntity entity, DamageSource damageSource, float v) {
		if(this.invulnerable) {
			return ActionResult.FAIL;
		}
		else {
			return ActionResult.SUCCESS;
		}
	}

	private ActionResult onBlockBroken(ServerPlayerEntity playerEntity, ServerWorld world, BlockPos pos) {
		for(BucketBreakModulePiece piece : this.config.bucketBreakModulePieces) {
			piece.breakBlock(this, playerEntity, pos);
		}
		return ActionResult.SUCCESS;
	}

	private void onExplosion(Explosion explosion, boolean b) {
		explosion.getAffectedBlocks().forEach(pos -> {
			for(BucketBreakModulePiece piece : this.config.bucketBreakModulePieces) {
				piece.breakBlock(this, explosion.getCausingEntity(), pos);
			}
		});
	}

	private TypedActionResult<List<ItemStack>> onMobLoot(LivingEntity livingEntity, List<ItemStack> itemStacks) {
		boolean keepOld = true;
		List<ItemStack> stacks = new ArrayList<>();
		for(EntityLootModulePiece piece : this.config.entityLootModulePieces) {
			if(piece.test(livingEntity)) {
				stacks.addAll(piece.getLoots(this.world, livingEntity));
				if(piece.replace()) keepOld = false;
			}
		}
		if(keepOld) stacks.addAll(itemStacks);
		return TypedActionResult.pass(stacks);
	}

	private TypedActionResult<List<ItemStack>> onBlockDrop(@Nullable Entity entity, ServerWorld world, BlockPos pos, BlockState state, List<ItemStack> itemStacks) {
		boolean keepOld = true;
		List<ItemStack> stacks = new ArrayList<>();
		for(BlockLootModulePiece piece : this.config.blockLootModulePieces) {
			if(piece.test(state, world.getRandom())) {
				piece.spawnExperience(world, pos);
				itemStacks.addAll(piece.getLoots(world, pos, entity, entity instanceof LivingEntity ? ((LivingEntity) entity).getActiveItem() : ItemStack.EMPTY));
				if(piece.replace()) keepOld = false;
			}
		}
		if(keepOld) stacks.addAll(itemStacks);
		return TypedActionResult.pass(stacks);
	}
}
