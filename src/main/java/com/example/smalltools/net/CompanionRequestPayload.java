package com.example.smalltools.net;

import com.example.smalltools.SmallTools;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * 客户端 → 服务端：我想传送到这位玩家身边。
 *
 * <p>只送一个 UUID。手上是不是真的拿着同伴传送石、目标在不在线、对方忙不忙，
 * 全部由服务端重新查一遍 —— 客户端说什么都不算数。</p>
 */
public record CompanionRequestPayload(UUID target) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CompanionRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(SmallTools.MODID, "companion_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CompanionRequestPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, CompanionRequestPayload::target,
                    CompanionRequestPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(CompanionRequestPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        // 玩家状态只在主线程可信
        context.enqueueWork(() ->
                com.example.smalltools.portal.CompanionTeleports.request(player, payload.target()));
    }
}
