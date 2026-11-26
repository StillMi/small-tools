package com.example.smalltools.net;

import com.example.smalltools.SmallTools;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：有人请求传送到你身边，弹窗问一下。
 *
 * <p>同一个包也负责<b>撤下</b>弹窗（{@code cancel}）：请求超时、对方取消、
 * 或者已经进入倒计时，都用它把界面关掉，不用再多一个包类型。</p>
 *
 * @param requestId 请求编号，回包时原样带回
 * @param sender    请求者的显示名
 * @param seconds   还剩多少秒超时，给界面做本地倒计时用
 * @param distance  直线距离（格）；跨维度时为 {@code -1}
 * @param cancel    {@code true} 表示撤下弹窗，其余字段仅供参考
 */
public record CompanionPromptPayload(int requestId, Component sender, int seconds,
                                     int distance, boolean cancel) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CompanionPromptPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(SmallTools.MODID, "companion_prompt"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CompanionPromptPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, CompanionPromptPayload::requestId,
                    ComponentSerialization.STREAM_CODEC, CompanionPromptPayload::sender,
                    ByteBufCodecs.VAR_INT, CompanionPromptPayload::seconds,
                    ByteBufCodecs.VAR_INT, CompanionPromptPayload::distance,
                    ByteBufCodecs.BOOL, CompanionPromptPayload::cancel,
                    CompanionPromptPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(CompanionPromptPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
                com.example.smalltools.client.ClientHooks.onCompanionPrompt(payload));
    }
}
