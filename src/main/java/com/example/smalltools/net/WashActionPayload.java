package com.example.smalltools.net;

import com.example.smalltools.SmallTools;
import com.example.smalltools.menu.WashStoneMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：在洗炼界面里点了第 {@code index} 个东西。
 *
 * <p>两种用途共用一个包：</p>
 * <ul>
 *   <li>{@code kind = 0} —— 选了一个<b>词条</b>，请求服务端随机三条候选；</li>
 *   <li>{@code kind = 1} —— 选了一个<b>候选</b>，请求服务端完成替换。</li>
 * </ul>
 *
 * <p>只带下标，不带附魔 ID：候选由服务端生成并持有，客户端无法伪造出想要的附魔。</p>
 */
public record WashActionPayload(int containerId, int kind, int index) implements CustomPacketPayload {

    public static final int KIND_ENTRY = 0;
    public static final int KIND_CANDIDATE = 1;
    /** 界面刚打开时请求一次状态。 */
    public static final int KIND_REFRESH = 2;

    public static final CustomPacketPayload.Type<WashActionPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(SmallTools.MODID, "wash_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WashActionPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, WashActionPayload::containerId,
                    ByteBufCodecs.VAR_INT, WashActionPayload::kind,
                    ByteBufCodecs.VAR_INT, WashActionPayload::index,
                    WashActionPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(WashActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof net.minecraft.server.level.ServerPlayer player)) {
                return;
            }
            // 只认玩家当前真正打开的那个菜单，防止用一个陈旧的 containerId 操作别的界面
            if (!(player.containerMenu instanceof WashStoneMenu menu)
                    || menu.containerId != payload.containerId()) {
                return;
            }
            if (!(player.level() instanceof net.minecraft.server.level.ServerLevel level)) {
                return;
            }

            if (payload.kind() == KIND_REFRESH) {
                menu.refresh(level);
            } else if (payload.kind() == KIND_ENTRY) {
                menu.chooseEntry(level, payload.index());
            } else if (payload.kind() == KIND_CANDIDATE) {
                menu.applyCandidate(player, payload.index());
            }
            // 状态变化后推一份新的给客户端
            WashStoneSyncPayload.send(player, menu);
        });
    }
}
