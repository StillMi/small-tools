package com.example.smalltools.net;

import com.example.smalltools.SmallTools;
import com.example.smalltools.menu.WashStoneMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 → 客户端：洗炼界面的当前状态。
 *
 * <p>带三样东西：输入物品上的所有<b>词条</b>、已选中的词条下标、三条<b>候选</b>。</p>
 *
 * <p>只传 {@code ResourceLocation} 和 {@code int}，客户端自己拼显示名
 * （{@code enchantment.<命名空间>.<路径>}）。这样客户端不用查注册表，
 * 也不会因为两边注册表编号不同而错位。</p>
 */
public record WashStoneSyncPayload(int containerId,
                                   List<Entry> entries,
                                   int selected,
                                   List<Entry> candidates) implements CustomPacketPayload {

    /** 一条附魔：id 与等级。 */
    public record Entry(ResourceLocation id, int level) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC =
                StreamCodec.composite(
                        ResourceLocation.STREAM_CODEC, Entry::id,
                        ByteBufCodecs.VAR_INT, Entry::level,
                        Entry::new);
    }

    public static final CustomPacketPayload.Type<WashStoneSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(SmallTools.MODID, "wash_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WashStoneSyncPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, WashStoneSyncPayload::containerId,
                    Entry.STREAM_CODEC.apply(ByteBufCodecs.list()), WashStoneSyncPayload::entries,
                    ByteBufCodecs.VAR_INT, WashStoneSyncPayload::selected,
                    Entry.STREAM_CODEC.apply(ByteBufCodecs.list()), WashStoneSyncPayload::candidates,
                    WashStoneSyncPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 把菜单当前状态打包发给玩家。 */
    public static void send(ServerPlayer player, WashStoneMenu menu) {
        List<Entry> entries = new ArrayList<>();
        for (var holder : menu.entries()) {
            holder.unwrapKey().ifPresent(key ->
                    entries.add(new Entry(key.location(), menu.levelOf(holder))));
        }
        List<Entry> candidates = new ArrayList<>();
        for (var candidate : menu.candidates()) {
            candidate.enchantment().unwrapKey().ifPresent(key ->
                    candidates.add(new Entry(key.location(), candidate.level())));
        }
        PacketDistributor.sendToPlayer(player,
                new WashStoneSyncPayload(menu.containerId, entries, menu.selected(), candidates));
    }

    /** 客户端收到后交给界面渲染。 */
    public static void handle(WashStoneSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
                com.example.smalltools.client.ClientHooks.onWashSync(payload));
    }
}
