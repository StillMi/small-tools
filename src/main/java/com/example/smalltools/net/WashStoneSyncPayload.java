package com.example.smalltools.net;

import com.example.smalltools.SmallTools;
import com.example.smalltools.menu.WashStoneMenu;
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
 * 服务端 → 客户端：洗炼界面的当前状态。
 *
 * <h2>为什么直接传显示名</h2>
 *
 * <p>之前只传附魔 id，客户端自己拼 {@code enchantment.<命名空间>.<路径>} 这个翻译键。
 * 但附魔的显示名在 1.21 里是 {@code Enchantment#description} 这个
 * <b>Component 字段</b>，不一定等于由 id 推出来的键 —— 模组附魔、
 * 或者用了自定义 description 的原版附魔都会解析失败，界面上就出现一整串生硬的英文标识。</p>
 *
 * <p>现在服务端用 {@code Enchantment.getFullname(holder, level)} 生成和原版附魔书
 * 完全一致的名称（含罗马数字等级），直接作为 {@link Component} 发过来。</p>
 */
public record WashStoneSyncPayload(int containerId,
                                   List<Entry> entries,
                                   int selected,
                                   List<Entry> candidates,
                                   boolean locked,
                                   int luckPercent) implements CustomPacketPayload {

    /**
     * 一条附魔。
     *
     * @param name  已经渲染好的显示名（服务端生成，带等级）
     * @param level 等级，客户端可能用来做额外排版
     */
    public record Entry(Component name, int level) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC =
                StreamCodec.composite(
                        ComponentSerialization.STREAM_CODEC, Entry::name,
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
                    ByteBufCodecs.BOOL, WashStoneSyncPayload::locked,
                    ByteBufCodecs.VAR_INT, WashStoneSyncPayload::luckPercent,
                    WashStoneSyncPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 把菜单当前状态打包发给玩家。 */
    public static void send(ServerPlayer player, WashStoneMenu menu) {
        List<Entry> entries = new ArrayList<>();
        for (var holder : menu.entries()) {
            entries.add(new Entry(menu.displayNameOf(holder), menu.levelOf(holder)));
        }
        List<Entry> candidates = new ArrayList<>();
        for (var candidate : menu.candidates()) {
            // 关键：用候选自己的 level 生成名字。
            // 之前只传了 enchantment，内部退回 levelOf() —— 而候选是物品上还没有的
            // 附魔，levelOf 永远是 0，结果界面显示不带等级、实际却写入了等级。
            candidates.add(new Entry(
                    menu.displayNameOf(candidate.enchantment(), candidate.level()),
                    candidate.level()));
        }
        PacketDistributor.sendToPlayer(player,
                new WashStoneSyncPayload(menu.containerId, entries, menu.selected(),
                        candidates, menu.locked(), menu.luckPercent()));
    }

    /** 客户端收到后交给界面渲染。 */
    public static void handle(WashStoneSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
                com.example.smalltools.client.ClientHooks.onWashSync(payload));
    }
}
