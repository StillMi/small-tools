package com.example.smalltools.net;

import com.example.smalltools.SmallTools;
import com.example.smalltools.item.AbstractPortalStoneItem;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.border.WorldBorder;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：把手上那块传送石绑定的目标坐标改掉。
 *
 * <p>服务端不信任客户端送来的任何东西，所以这里重新校验：</p>
 * <ul>
 *   <li>玩家<b>那只手</b>上确实拿着能设坐标的传送石（防止空手、别的物品，
 *       或者"界面上显示副手、服务端改了主手"这种错位）；</li>
 *   <li>X/Z 在世界边界以内（界外根本生成不出区块，门永远开不出来）；</li>
 *   <li>Y 在世界的可建范围内（防止把门建到虚空里）。</li>
 * </ul>
 *
 * <p><b>为什么带 {@code hand}：</b>以前服务端自己按 {@code InteractionHand.values()}
 * 找"第一块传送石"，但界面是按打开它的那只手预填的。双手各持一颗时，界面显示副手的坐标，
 * 服务端改的却是主手那颗；主手已经绑过就回 {@code target_locked}，玩家完全看不懂。
 * 现在手是明着传的，服务端只认那只手，不满足就直说。</p>
 */
public record SetTargetPayload(BlockPos target, InteractionHand hand) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SetTargetPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(SmallTools.MODID, "set_target"));

    /**
     * 手用 ordinal 编解码（{@code InteractionHand} 本身没有现成的 StreamCodec）。
     * 解码时对越界值兜底成主手，而不是抛异常 —— 一个坏包不该把连接踢掉。
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, SetTargetPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, SetTargetPayload::target,
                    ByteBufCodecs.VAR_INT, payload -> payload.hand().ordinal(),
                    (target, handOrdinal) -> new SetTargetPayload(target, handFromOrdinal(handOrdinal)));

    /** Config 里 {@code targetX / targetZ} 的取值范围，与世界边界取交集用。 */
    private static final int CONFIG_LIMIT = 30_000_000;

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static InteractionHand handFromOrdinal(int ordinal) {
        InteractionHand[] values = InteractionHand.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : InteractionHand.MAIN_HAND;
    }

    /** 服务端处理：校验 + 写入数据组件 + 反馈。 */
    public static void handle(SetTargetPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        // 方块和世界状态只在主线程可信，所以挪到主线程执行
        context.enqueueWork(() -> apply(player, payload));
    }

    private static void apply(ServerPlayer player, SetTargetPayload payload) {
        // 只认客户端说的那只手，再复核一遍那只手上到底拿着什么
        InteractionHand hand = payload.hand();
        ItemStack stack = player.getItemInHand(hand);
        // 只有能设坐标的石头（末影传送石）才接受这个包。这里以前是静默 return，
        // 玩家点了确定却什么都没发生，还以为界面卡了
        if (!(stack.getItem() instanceof AbstractPortalStoneItem stone) || !stone.allowsBinding()) {
            player.displayClientMessage(
                    Component.translatable("message.smalltools.target_not_supported")
                            .withStyle(ChatFormatting.RED), true);
            return;
        }
        // 一块石头只能设定一次，不信任客户端、服务端再挡一遍
        if (AbstractPortalStoneItem.boundTarget(stack) != null) {
            player.displayClientMessage(
                    Component.translatable("message.smalltools.target_locked")
                            .withStyle(ChatFormatting.RED), true);
            return;
        }

        BlockPos target = payload.target();

        // X/Z：世界边界之外不会生成区块，写进去也是一座永远开不出来的门。
        // 客户端只是把输入框放宽到 9 位数字，真正的边界在这儿定
        WorldBorder border = player.level().getWorldBorder();
        int minXZ = Math.max(-CONFIG_LIMIT, Mth.floor(border.getMinX()));
        int maxXZ = Math.min(CONFIG_LIMIT, Mth.floor(border.getMaxX()));
        // 失败提示用现成的 target_out_of_bounds（这条键本来就在语言文件里，不占位）
        if (target.getX() < minXZ || target.getX() > maxXZ
                || target.getZ() < minXZ || target.getZ() > maxXZ) {
            player.displayClientMessage(
                    Component.translatable("message.smalltools.target_out_of_bounds")
                            .withStyle(ChatFormatting.RED), true);
            return;
        }

        int minY = player.level().getMinBuildHeight();
        int maxY = player.level().getMaxBuildHeight() - 1;
        if (target.getY() < minY || target.getY() > maxY) {
            player.displayClientMessage(
                    Component.translatable("message.smalltools.target_out_of_range", minY, maxY)
                            .withStyle(ChatFormatting.RED), true);
            return;
        }

        stack.set(SmallTools.PORTAL_TARGET.get(), target);
        player.displayClientMessage(
                Component.translatable("message.smalltools.target_set",
                        target.getX(), target.getY(), target.getZ())
                        .withStyle(ChatFormatting.LIGHT_PURPLE), true);
    }
}
