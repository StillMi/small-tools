package com.example.smalltools.net;

import com.example.smalltools.SmallTools;
import com.example.smalltools.menu.EnhancerMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：请求一次强化。
 *
 * <p>不带任何参数 —— 消耗多少、强化哪条全由服务端决定。
 * 客户端连“强化几次”都没法伪造；{@code kind} 只有下面三个合法值，
 * 其它值一律忽略（不回包、不动作）。</p>
 */
public record EnhanceActionPayload(int containerId, int kind) implements CustomPacketPayload {

    /** 请求刷新一次状态（界面刚打开时用，带节流）。 */
    public static final int KIND_REFRESH = 0;
    /** 一次性投入这一档所需的全部浓缩液态青金。 */
    public static final int KIND_INVEST = 1;
    /** 请求完成一次强化（进度必须已满）。 */
    public static final int KIND_ENHANCE = 2;

    public static final CustomPacketPayload.Type<EnhanceActionPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(SmallTools.MODID, "enhance_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, EnhanceActionPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, EnhanceActionPayload::containerId,
                    ByteBufCodecs.VAR_INT, EnhanceActionPayload::kind,
                    EnhanceActionPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EnhanceActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof net.minecraft.server.level.ServerPlayer player)) {
                return;
            }
            // 只认玩家当前真正打开的那个菜单
            if (!(player.containerMenu instanceof EnhancerMenu menu)
                    || menu.containerId != payload.containerId()) {
                return;
            }
            if (payload.kind() == KIND_INVEST) {
                // 成功和失败都已经在菜单内部 sync 过了，这里不再补发，
                // 否则一次点击要发两遍全量状态
                menu.invest(player);
            } else if (payload.kind() == KIND_ENHANCE) {
                menu.enhance();
            } else if (payload.kind() == KIND_REFRESH) {
                // 刷新是唯一需要回包的分支，带节流：两个 tick 内的重复请求直接丢弃
                if (menu.shouldAcceptRefresh()) {
                    EnhancerSyncPayload.send(player, menu);
                }
            }
        });
    }
}
