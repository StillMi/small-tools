package com.example.smalltools.net;

import com.example.smalltools.SmallTools;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.UUIDUtil;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;
import java.util.UUID;

/**
 * 服务端 → 客户端：把可以传送过去的玩家名单推给界面。
 *
 * <h2>为什么不用 {@code Minecraft#getConnection().getOnlinePlayers()}</h2>
 *
 * <p>客户端自己确实有 Tab 列表，但那份数据<b>只有名字和延迟</b>：没有距离、没有维度，
 * 而且被服务器设成隐藏 Tab 的玩家根本不在里面。这些东西只有服务端知道，
 * 所以干脆由服务端现算一份送过来。</p>
 *
 * <p>名单里已经排除掉请求者本人。距离按"自己所在维度"算，跨维度时 {@code distance}
 * 固定是 {@code -1}，客户端据此显示"另一个维度"而不是一个没有意义的数字。</p>
 */
public record CompanionRosterPayload(List<Entry> players) implements CustomPacketPayload {

    /**
     * 名单里的一行。
     *
     * @param name      显示名，服务端生成好再发，免得客户端还要处理队伍前缀之类的东西
     * @param id        玩家 UUID，点选之后原样发回服务端
     * @param distance  直线距离（取整格数）；跨维度时为 {@code -1}
     * @param sameLevel 是否和请求者在同一个维度
     */
    public record Entry(Component name, UUID id, int distance, boolean sameLevel) {

        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> CODEC =
                StreamCodec.composite(
                        ComponentSerialization.STREAM_CODEC, Entry::name,
                        UUIDUtil.STREAM_CODEC, Entry::id,
                        ByteBufCodecs.VAR_INT, Entry::distance,
                        ByteBufCodecs.BOOL, Entry::sameLevel,
                        Entry::new);
    }

    public static final CustomPacketPayload.Type<CompanionRosterPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(SmallTools.MODID, "companion_roster"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CompanionRosterPayload> STREAM_CODEC =
            StreamCodec.composite(
                    Entry.CODEC.apply(ByteBufCodecs.list()), CompanionRosterPayload::players,
                    CompanionRosterPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 客户端处理：转交给已经打开的选人界面。 */
    public static void handle(CompanionRosterPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
                com.example.smalltools.client.ClientHooks.onCompanionRoster(payload));
    }
}
