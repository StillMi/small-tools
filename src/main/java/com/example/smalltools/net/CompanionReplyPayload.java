package com.example.smalltools.net;

import com.example.smalltools.SmallTools;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：同意 / 拒绝那位玩家的传送请求。
 *
 * <p>点了同意不代表一定传得过去 —— 服务端在 10 秒后还要再确认一次
 * 两边都在线、请求者手里还拿着石头、目标脚下有能站人的地方，
 * 任何一项不满足都会取消，且不会消耗石头。</p>
 */
public record CompanionReplyPayload(int requestId, boolean accept) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CompanionReplyPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(SmallTools.MODID, "companion_reply"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CompanionReplyPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, CompanionReplyPayload::requestId,
                    ByteBufCodecs.BOOL, CompanionReplyPayload::accept,
                    CompanionReplyPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(CompanionReplyPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() ->
                com.example.smalltools.portal.CompanionTeleports.reply(
                        player, payload.requestId(), payload.accept()));
    }
}
