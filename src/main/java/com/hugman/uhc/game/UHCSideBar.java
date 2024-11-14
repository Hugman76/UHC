package com.hugman.uhc.game;

import com.hugman.uhc.util.TickUtil;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import xyz.nucleoid.plasmid.api.game.GameSpace;
import xyz.nucleoid.plasmid.api.game.common.GlobalWidgets;
import xyz.nucleoid.plasmid.api.game.common.widget.SidebarWidget;

public record UHCSideBar(SidebarWidget sidebarWidget) {
    public static UHCSideBar create(GlobalWidgets widgets, GameSpace gameSpace) {
        var name = gameSpace.getMetadata().sourceConfig().value().name();
        name = name != null ? name : Text.translatable("game.uhc");
        return new UHCSideBar(widgets.addSidebar(name.copy().formatted(Formatting.BOLD, Formatting.GOLD)));
    }

    public void update(long ticks, int worldSize, Object2ObjectMap<ServerPlayerEntity, UHCParticipant> participantMap) {
        sidebarWidget.set(content -> {
            long count = participantMap.values().stream().filter(participant -> !participant.isEliminated()).count();

            content.add(Text.literal(""));
            content.add(Text.translatable("text.uhc.players", Text.literal(String.valueOf(count)).formatted(Formatting.GREEN)).formatted(Formatting.WHITE));
            //TODO: write kills
            content.add(Text.literal(""));
            content.add(Text.translatable("text.uhc.world", Text.literal(worldSize + "x" + worldSize).formatted(Formatting.GREEN)).formatted(Formatting.WHITE));
            content.add(Text.literal(""));
            content.add(Text.translatable("text.uhc.time", TickUtil.format(ticks).formatted(Formatting.GREEN)).formatted(Formatting.WHITE));
        });
    }
}
