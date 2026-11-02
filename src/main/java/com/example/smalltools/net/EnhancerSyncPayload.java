package com.example.smalltools.net;

import com.example.smalltools.SmallTools;
import com.example.smalltools.menu.EnhancerMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
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
 * 服务端 → 客户端：强化界面的当前状态。
 *
 * <p>传四样东西：物品上的附魔列表（显示名 + 等级 + 是否满级）、
 * 下一次消耗、玩家持有的凝造青金数、以及最近一次操作的结果提示。</p>
 *
 * <p>显示名由服务端用 {@code Enchantment.getFullname} 生成 ——
 * 客户端不拼翻译键（拼出来的键对模组附魔不成立）。</p>
 */
public record EnhancerSyncPayload(int containerId,
                                  List<Entry> enchantments,
                                  int cost,
                                  int lapis,
                                  boolean canEnhance,
                                  Component result) implements CustomPacketPayload {

    /** 一条附魔：显示名、等级、是否已满级。 */
    public record Entry(Component name, int level, boolean maxed) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC =
                StreamCodec.composite(
                        ComponentSerialization.STREAM_CODEC, Entry::name,
                        ByteBufCodecs.VAR_INT, Entry::level,
                        ByteBufCodecs.BOOL, Entry::maxed,
                        Entry::new);
    }

    public static final CustomPacketPayload.Type<EnhancerSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(SmallTools.MODID, "enhancer_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, EnhancerSyncPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, EnhancerSyncPayload::containerId,
                    Entry.STREAM_CODEC.apply(ByteBufCodecs.list()), EnhancerSyncPayload::enchantments,
                    ByteBufCodecs.VAR_INT, EnhancerSyncPayload::cost,
                    ByteBufCodecs.VAR_INT, EnhancerSyncPayload::lapis,
                    ByteBufCodecs.BOOL, EnhancerSyncPayload::canEnhance,
                    ComponentSerialization.STREAM_CODEC, EnhancerSyncPayload::result,
                    EnhancerSyncPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 把菜单状态打包发给玩家。 */
    public static void send(ServerPlayer player, EnhancerMenu menu) {
        var stack = menu.slots.get(EnhancerMenu.SLOT_ITEM).getItem();

        List<Entry> list = new ArrayList<>();
        if (!stack.isEmpty()) {
            for (var holder : stack.getEnchantments().keySet()) {
                int level = stack.getEnchantments().getLevel(holder);
                int max = Math.max(1, holder.value().getMaxLevel());
                list.add(new Entry(
                        net.minecraft.world.item.enchantment.Enchantment.getFullname(holder, level),
                        level, level >= max));
            }
        }

        String key = menu.lastResult();
        Component result = key == null || key.isEmpty()
                ? Component.empty()
                : Component.translatable(key);

        PacketDistributor.sendToPlayer(player, new EnhancerSyncPayload(
                menu.containerId, list, menu.costFor(stack), menu.lapisCount(),
                menu.canEnhance(stack), result));
    }

    public static void handle(EnhancerSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
                com.example.smalltools.client.ClientHooks.onEnhancerSync(payload));
    }
}
